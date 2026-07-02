package sh.margot.dash.services

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import sh.margot.dash.databinding.CardPhonesimsBinding
import sh.margot.dash.databinding.ItemSimBinding

/**
 * Lists the SIM/eSIM subscriptions installed on this phone via SubscriptionManager (needs the
 * ordinary READ_PHONE_STATE runtime permission). Shows what a non-privileged app is actually
 * allowed to see: display/carrier name, eSIM vs physical, country, and which one is the active
 * data SIM. It cannot read the ICCID (empty for third-party apps since Android 10), so it can't
 * link a listed eSIM back to a specific SMSPool purchase — that needs privileged access we can't get.
 */
class PhoneSimsCardController(private val context: Context) : ServiceCard {

    override val id = "phone_sims"

    companion object {
        const val PERMISSION_REQUEST = 42
    }

    override fun createView(inflater: LayoutInflater, parent: ViewGroup): View {
        val binding = CardPhonesimsBinding.inflate(inflater, parent, false)
        binding.grantButton.setOnClickListener {
            ActivityCompat.requestPermissions(
                context as AppCompatActivity,
                arrayOf(Manifest.permission.READ_PHONE_STATE), PERMISSION_REQUEST
            )
        }
        return binding.root
    }

    override suspend fun refresh(view: View) {
        val binding = CardPhonesimsBinding.bind(view)
        binding.simsContainer.removeAllViews()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            binding.simsContainer.addView(line("Grant the phone permission to list the SIMs installed on this device."))
            binding.grantButton.visibility = View.VISIBLE
            return
        }
        binding.grantButton.visibility = View.GONE

        val sm = context.getSystemService(SubscriptionManager::class.java)
        @Suppress("MissingPermission")
        val subs = try { sm?.activeSubscriptionInfoList } catch (_: SecurityException) { null }.orEmpty()
        if (subs.isEmpty()) {
            binding.simsContainer.addView(line("No active SIMs."))
            return
        }

        val activeData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            SubscriptionManager.getActiveDataSubscriptionId() else SubscriptionManager.getDefaultDataSubscriptionId()
        val defaultVoice = SubscriptionManager.getDefaultVoiceSubscriptionId()
        val defaultSms = SubscriptionManager.getDefaultSmsSubscriptionId()
        val baseTm = context.getSystemService(TelephonyManager::class.java)
        val inflater = LayoutInflater.from(context)

        subs.forEach { info ->
            val item = ItemSimBinding.inflate(inflater, binding.simsContainer, false)
            item.nameText.text = info.displayName?.toString()?.ifBlank { null }
                ?: info.carrierName?.toString()?.ifBlank { null } ?: "SIM"
            item.badgeText.text = if (info.isEmbedded) "eSIM" else "Physical"

            // Per-subscription view for the live network (roaming partner) and roaming state.
            val tm = baseTm?.createForSubscriptionId(info.subscriptionId)
            val homeOp = info.carrierName?.toString()?.ifBlank { null }
                ?: tm?.simOperatorName?.ifBlank { null }
            val liveNetwork = tm?.networkOperatorName?.ifBlank { null }
            val roaming = try { tm?.isNetworkRoaming } catch (_: SecurityException) { null }
            @Suppress("DEPRECATION") val number = info.number?.ifBlank { null }

            val roles = buildList {
                if (info.subscriptionId == activeData) add("data")
                if (info.subscriptionId == defaultVoice) add("calls")
                if (info.subscriptionId == defaultSms) add("texts")
            }

            item.detailsText.text = buildString {
                appendLine("Home carrier: ${homeOp ?: "—"}")
                if (liveNetwork != null && !liveNetwork.equals(homeOp, ignoreCase = true))
                    appendLine("On network: $liveNetwork" + if (roaming == true) " (roaming)" else "")
                else if (roaming == true) appendLine("Roaming: yes")
                appendLine("Country: ${info.countryIso?.uppercase()?.ifBlank { null } ?: "—"}")
                appendLine("Network code: ${info.mccString ?: "—"}/${info.mncString ?: "—"}")
                if (number != null) appendLine("Number: $number")
                appendLine("Slot: ${info.simSlotIndex}   ·   Default for: ${roles.ifEmpty { listOf("—") }.joinToString(", ")}")
            }.trimEnd()

            binding.simsContainer.addView(item.root)
        }
    }

    private fun line(text: String) = TextView(context).apply {
        this.text = text
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
