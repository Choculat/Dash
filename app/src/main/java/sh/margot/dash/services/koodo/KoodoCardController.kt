package sh.margot.dash.services.koodo

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import sh.margot.dash.databinding.CardKoodoBinding
import sh.margot.dash.security.CredentialStore
import sh.margot.dash.services.CardStateCache
import sh.margot.dash.services.DisconnectTap
import sh.margot.dash.services.ServiceCard
import java.text.SimpleDateFormat
import java.util.Locale

/** Renders the Koodo Mobile account as a single Dash card and owns its connect/disconnect lifecycle. */
class KoodoCardController(private val context: Context) : ServiceCard {

    override val id = KoodoApiClient.SERVICE_ID

    private sealed class State {
        data class Connected(val renews: String, val minutes: String, val texts: String) : State()
        data class Disconnected(val message: String, val needsVerification: Boolean) : State()
    }

    private fun State.toJson(): String = when (this) {
        is State.Connected -> JSONObject().put("type", "connected")
            .put("renews", renews).put("minutes", minutes).put("texts", texts)
        is State.Disconnected -> JSONObject().put("type", "disconnected")
            .put("message", message).put("needsVerification", needsVerification)
    }.toString()

    private fun parseState(json: String): State? = try {
        val o = JSONObject(json)
        when (o.getString("type")) {
            "connected" -> State.Connected(o.getString("renews"), o.getString("minutes"), o.getString("texts"))
            "disconnected" -> State.Disconnected(o.getString("message"), o.getBoolean("needsVerification"))
            else -> null
        }
    } catch (_: Exception) { null }

    private var lastState: State? = null
    private lateinit var disconnectTap: DisconnectTap

    override fun createView(inflater: LayoutInflater, parent: ViewGroup): View {
        val binding = CardKoodoBinding.inflate(inflater, parent, false)
        binding.connectButton.setOnClickListener {
            context.startActivity(Intent(context, KoodoLoginActivity::class.java))
        }
        disconnectTap = DisconnectTap(binding.statusText)

        // Show the last-known data immediately so the card isn't empty while the first
        // background refresh (which may take a moment, e.g. a Koodo reconnect) completes.
        CardStateCache.load(context, id)?.let(::parseState)?.let { cached ->
            applyState(binding, cached)
            lastState = cached
        }
        return binding.root
    }

    // Refreshes quietly: the card keeps showing whatever it already had until a fetch actually
    // completes with a different result, so a background check never flashes a transient
    // "Checking…"/"Reconnecting…" state at the user.
    override suspend fun refresh(view: View) {
        val binding = CardKoodoBinding.bind(view)
        // If we don't already have account data on screen but a reconnect/fetch is about to run,
        // show a "Checking…" state with the Connect button disabled so it can't be tapped
        // mid-load. It's re-enabled once we resolve to a disconnected/error state below.
        val willLoad = lastState !is State.Connected &&
            (CredentialStore.has(context, id) || KoodoApiClient.cookieJar.hasSessionFor(KoodoApiClient.HOST))
        if (willLoad) showLoading(binding)

        val newState = computeState()
        if (newState != lastState || willLoad) {
            applyState(binding, newState)
            lastState = newState
            CardStateCache.save(context, id, newState.toJson())
        }
    }

    private fun showLoading(binding: CardKoodoBinding) {
        disconnectTap.unbind()
        binding.statusText.text = "Checking…"
        binding.connectedGroup.visibility = View.GONE
        binding.disconnectedGroup.visibility = View.VISIBLE
        binding.disconnectedMessageText.text = "Checking your Koodo account…"
        binding.connectButton.isEnabled = false
    }

    private suspend fun computeState(): State {
        val sessionValid = KoodoApiClient.cookieJar.hasSessionFor(KoodoApiClient.HOST) &&
            KoodoApiClient.checkStatus().getOrNull()?.optString("status") == "running"
        if (sessionValid) return fetchConnectedState()

        val creds = CredentialStore.get(context, id)
            ?: return State.Disconnected("Sign in to see your Koodo account details.", needsVerification = false)

        // If we know this account uses 2FA, don't silently re-authenticate on launch — that could
        // make Koodo text a fresh code every open. Wait for the user to tap sign-in instead.
        if (Koodo2fa.isKnown(context))
            return State.Disconnected("Your Koodo session expired. Tap to sign in and enter your code.", needsVerification = true)

        return KoodoAuthService.login(creds.email, creds.password).fold(
            onSuccess = { profile ->
                Koodo2fa.set(context, profile.twoFactorFlag)
                if (profile.twoFactorFlag && !profile.phoneNumber.isNullOrBlank())
                    State.Disconnected("Sign back in to finish verifying your Koodo account.", needsVerification = true)
                else fetchConnectedState()
            },
            onFailure = { e ->
                if (e is KoodoAuthService.AuthException && e.invalidCredentials) {
                    // Stored password no longer works (e.g. changed on Koodo's side) — forget it.
                    CredentialStore.clear(context, id)
                    State.Disconnected("Sign in to see your Koodo account details.", needsVerification = false)
                } else {
                    // Network/server hiccup — keep the stored credentials, next refresh may succeed.
                    State.Disconnected("Couldn't reconnect — tap to try again.", needsVerification = false)
                }
            }
        )
    }

    private suspend fun fetchConnectedState(): State.Connected = coroutineScope {
        val planD = async { KoodoApiClient.getPlanUsage() }
        val fundsD = async { KoodoApiClient.getFunds() }

        val planData = planD.await().getOrNull()
            ?.optJSONObject("response")?.optJSONObject("data")
        val expiryRaw = fundsD.await().getOrNull()
            ?.optJSONObject("response")?.optJSONObject("data")?.optString("expiryDate") ?: ""

        var minutes = "—"
        var texts = "—"
        planData?.optJSONArray("bundles")?.let { bundles ->
            for (i in 0 until bundles.length()) {
                val b = bundles.getJSONObject(i)
                val used = b.optString("personalUsed", "0").toLongOrNull() ?: 0L
                val limit = b.optString("personalLimit", "0").toLongOrNull() ?: 0L
                when (b.optString("unitType")) {
                    "0" -> minutes = "${used / 60} / ${limit / 60} used"
                    "2" -> texts = "$used / $limit used"
                }
            }
        }
        State.Connected(formatRenewal(expiryRaw), minutes, texts)
    }

    private fun applyState(binding: CardKoodoBinding, state: State) {
        when (state) {
            is State.Connected -> {
                binding.renewsText.text = state.renews
                binding.minutesText.text = state.minutes
                binding.textsText.text = state.texts
                binding.connectedGroup.visibility = View.VISIBLE
                binding.disconnectedGroup.visibility = View.GONE
                disconnectTap.bind { confirmDisconnect(binding) }
            }
            is State.Disconnected -> {
                disconnectTap.unbind()
                binding.statusText.text = if (state.needsVerification) "Needs verification" else "Disconnected"
                binding.connectedGroup.visibility = View.GONE
                binding.disconnectedGroup.visibility = View.VISIBLE
                binding.disconnectedMessageText.text = state.message
                binding.connectButton.text = if (state.needsVerification) "Finish sign-in" else "Connect"
                binding.connectButton.isEnabled = true
            }
        }
    }

    private fun confirmDisconnect(binding: CardKoodoBinding) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Disconnect Koodo Mobile")
            .setMessage("This clears your saved session and stored credentials.")
            .setPositiveButton("Disconnect") { _, _ ->
                KoodoApiClient.cookieJar.clear()
                CredentialStore.clear(context, id)
                Koodo2fa.clear(context)
                val newState = State.Disconnected("Sign in to see your Koodo account details.", needsVerification = false)
                applyState(binding, newState)
                lastState = newState
                CardStateCache.save(context, id, newState.toJson())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun formatRenewal(raw: String): String = try {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(raw.take(10))!!
        val days = ((date.time - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
        "${SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(date)} ($days days)"
    } catch (_: Exception) { "—" }
}
