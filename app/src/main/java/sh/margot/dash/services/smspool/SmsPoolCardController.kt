package sh.margot.dash.services.smspool

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import sh.margot.dash.databinding.CardSmspoolBinding
import sh.margot.dash.databinding.ItemEsimBinding
import sh.margot.dash.security.CredentialStore
import sh.margot.dash.services.CardStateCache
import sh.margot.dash.services.DisconnectTap
import sh.margot.dash.services.ServiceCard

/**
 * SMSPool eSIM history as a single Dash card. There's no session to keep alive here — the
 * stored API key just works or it doesn't — so unlike Koodo there's no silent auto-reconnect
 * step, only a connect screen (a WebView, since login is captcha-gated) and a static key.
 * Tapping an eSIM shows how to install it (see EsimInstallDialog); "Browse plans" buys new ones.
 */
class SmsPoolCardController(private val context: Context) : ServiceCard {

    override val id = SmsPoolApiClient.SERVICE_ID

    private data class EsimEntry(
        val transactionId: String,
        val name: String,
        val status: String,
        val dataLeft: String,
        val expiry: String
    )

    private sealed class State {
        data class Connected(val entries: List<EsimEntry>) : State()
        data class Disconnected(val message: String) : State()
    }

    private fun State.toJson(): String = when (this) {
        is State.Connected -> JSONObject().put("type", "connected").put("entries", JSONArray().apply {
            entries.forEach {
                put(JSONObject().put("id", it.transactionId).put("name", it.name)
                    .put("status", it.status).put("dataLeft", it.dataLeft).put("expiry", it.expiry))
            }
        })
        is State.Disconnected -> JSONObject().put("type", "disconnected").put("message", message)
    }.toString()

    private fun parseState(json: String): State? = try {
        val o = JSONObject(json)
        when (o.getString("type")) {
            "connected" -> {
                val arr = o.getJSONArray("entries")
                State.Connected(List(arr.length()) { i ->
                    val e = arr.getJSONObject(i)
                    EsimEntry(e.getString("id"), e.getString("name"), e.getString("status"),
                        e.optString("dataLeft", "—"), e.getString("expiry"))
                })
            }
            "disconnected" -> State.Disconnected(o.getString("message"))
            else -> null
        }
    } catch (_: Exception) { null }

    private var lastState: State? = null
    private lateinit var disconnectTap: DisconnectTap

    override fun createView(inflater: LayoutInflater, parent: ViewGroup): View {
        val binding = CardSmspoolBinding.inflate(inflater, parent, false)
        binding.connectButton.setOnClickListener {
            context.startActivity(Intent(context, SmsPoolLoginActivity::class.java))
        }
        binding.browseHeader.setOnClickListener {
            context.startActivity(Intent(context, SmsPoolPlansActivity::class.java))
        }
        disconnectTap = DisconnectTap(binding.statusText)

        // Show the last-known data immediately so the card isn't empty while the first
        // background fetch completes.
        CardStateCache.load(context, id)?.let(::parseState)?.let { cached ->
            applyState(binding, cached)
            lastState = cached
        }
        return binding.root
    }

    // Refreshes quietly: the card keeps showing whatever it already had until a fetch actually
    // completes with a different result, so a background check never flashes a transient
    // "Loading…" state at the user.
    override suspend fun refresh(view: View) {
        val binding = CardSmspoolBinding.bind(view)
        val newState = computeState()
        if (newState != lastState) {
            applyState(binding, newState)
            lastState = newState
            CardStateCache.save(context, id, newState.toJson())
        }
    }

    private suspend fun computeState(): State {
        val apiKey = CredentialStore.getSecret(context, id)
            ?: return State.Disconnected("Sign in to see your SMSPool eSIMs.")

        return SmsPoolApiClient.getEsimHistory(apiKey).fold(
            onSuccess = { State.Connected(buildEntries(apiKey, it)) },
            onFailure = { State.Disconnected("Couldn't load eSIM data — tap to retry.") }
        )
    }

    // history has the plan/status/expiry but not remaining data; that's only on esim/profile,
    // so fetch a profile per eSIM in parallel to fill in "Data left".
    private suspend fun buildEntries(apiKey: String, json: JSONObject): List<EsimEntry> = coroutineScope {
        val arr = json.optJSONArray("data") ?: return@coroutineScope emptyList()
        // Active eSIMs (status 2) first, then to-be-activated (1), then the rest.
        List(arr.length()) { i -> arr.getJSONObject(i) }
            .sortedByDescending { it.optInt("status") }
            .map { e ->
                async {
                    val txn = e.optString("transactionId")
                    val name = e.optString("name").ifBlank { e.optString("countryCode", "—") }
                    val status = when (e.optInt("status")) {
                        2 -> "Active"
                        1 -> "To be activated"
                        else -> "—"
                    }
                    val total = formatData(e.optDouble("dataInGb", 0.0))
                    val remaining = SmsPoolApiClient.getProfile(apiKey, txn).getOrNull()
                        ?.remainingData?.ifBlank { null }?.let { formatData(it) } ?: "—"
                    EsimEntry(txn, name, status, "$remaining / $total", e.optString("expiration", "—"))
                }
            }.awaitAll()
    }

    private fun applyState(binding: CardSmspoolBinding, state: State) {
        when (state) {
            is State.Connected -> {
                binding.esimListContainer.removeAllViews()
                val inflater = LayoutInflater.from(context)
                val rows = state.entries.ifEmpty { null }
                if (rows == null) {
                    binding.esimListContainer.addView(TextView(context).apply { text = "No eSIMs yet." })
                } else {
                    rows.forEach { entry ->
                        val item = ItemEsimBinding.inflate(inflater, binding.esimListContainer, false)
                        item.countryText.text = entry.name
                        item.statusText.text = entry.status
                        item.remainingText.text = entry.dataLeft
                        item.expiresText.text = entry.expiry
                        item.root.setOnClickListener { showInstallInfo(entry.transactionId) }
                        binding.esimListContainer.addView(item.root)
                    }
                }
                binding.connectedGroup.visibility = View.VISIBLE
                binding.disconnectedGroup.visibility = View.GONE
                // Chevron/header taps into Browse plans — only meaningful once signed in.
                binding.browseChevron.visibility = View.VISIBLE
                binding.browseHeader.isClickable = true
                disconnectTap.bind { confirmDisconnect(binding) }
            }
            is State.Disconnected -> {
                disconnectTap.unbind()
                binding.browseChevron.visibility = View.GONE
                binding.browseHeader.isClickable = false
                binding.statusText.text = "Disconnected"
                binding.connectedGroup.visibility = View.GONE
                binding.disconnectedGroup.visibility = View.VISIBLE
                binding.disconnectedMessageText.text = state.message
            }
        }
    }

    private fun showInstallInfo(transactionId: String) {
        val apiKey = CredentialStore.getSecret(context, id) ?: return
        val activity = context as AppCompatActivity
        activity.lifecycleScope.launch {
            SmsPoolApiClient.getProfile(apiKey, transactionId)
                .onSuccess { EsimInstallDialog.show(activity, it) }
                .onFailure { Toast.makeText(context, "Couldn't load install info", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun confirmDisconnect(binding: CardSmspoolBinding) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Disconnect SMSPool")
            .setMessage("This clears your stored API key.")
            .setPositiveButton("Disconnect") { _, _ ->
                CredentialStore.clear(context, id)
                val newState = State.Disconnected("Sign in to see your SMSPool eSIMs.")
                applyState(binding, newState)
                lastState = newState
                CardStateCache.save(context, id, newState.toJson())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
