package sh.margot.dash.services.smspool

import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import sh.margot.dash.databinding.ActivitySmspoolPlansBinding
import sh.margot.dash.security.CredentialStore
import sh.margot.dash.services.clickableRow

/** Country -> plan list -> buy, backed by the same key SmsPoolCardController uses. */
class SmsPoolPlansActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySmspoolPlansBinding
    private var apiKey: String? = null
    private var allCountries: List<SmsPoolApiClient.Country> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySmspoolPlansBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        onBackPressedDispatcher.addCallback(this) {
            if (binding.planContainer.visibility == View.VISIBLE) showCountries() else finish()
        }

        binding.searchInput.doAfterTextChanged { renderCountries(it?.toString().orEmpty()) }

        apiKey = CredentialStore.getSecret(this, SmsPoolApiClient.SERVICE_ID)
        val key = apiKey
        if (key == null) {
            finish()
            return
        }
        loadCountries(key)
        loadBalance(key)
    }

    private fun loadBalance(key: String) = lifecycleScope.launch {
        SmsPoolApiClient.getBalance(key).onSuccess { binding.toolbar.subtitle = "Balance: \$$it" }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun showCountries() {
        binding.planContainer.visibility = View.GONE
        binding.countryContainer.visibility = View.VISIBLE
        binding.searchInput.visibility = View.VISIBLE
    }

    private fun loadCountries(key: String) = lifecycleScope.launch {
        SmsPoolApiClient.getCountries(key)
            .onSuccess { countries ->
                allCountries = countries.sortedBy { it.name }
                renderCountries(binding.searchInput.text?.toString().orEmpty())
            }
            .onFailure { toast("Couldn't load countries") }
    }

    private fun renderCountries(query: String) {
        val key = apiKey ?: return
        val filtered = if (query.isBlank()) allCountries
        else allCountries.filter { it.name.contains(query.trim(), ignoreCase = true) }
        binding.countryContainer.removeAllViews()
        filtered.forEach { country ->
            binding.countryContainer.addView(
                clickableRow(this, "${country.name}  •  from \$${country.price}") {
                    loadPlans(key, country)
                }
            )
        }
    }

    private fun loadPlans(key: String, country: SmsPoolApiClient.Country) = lifecycleScope.launch {
        SmsPoolApiClient.getPlans(key, country.countryCode)
            .onSuccess { plans ->
                val sorted = plans.sortedBy { it.price.toDoubleOrNull() ?: 0.0 }
                // Hide "bad deals": a plan another plan beats or ties on every axis (data,
                // duration, speed, price) and beats on at least one — i.e. Pareto-dominated.
                val (good, hidden) = sorted.partition { !isDominated(it, sorted) }

                binding.planContainer.removeAllViews()
                good.forEach { addPlanRow(key, country, it, sorted) }
                if (hidden.isNotEmpty()) {
                    lateinit var moreRow: View
                    moreRow = clickableRow(this@SmsPoolPlansActivity, "Show ${hidden.size} more plans") {
                        binding.planContainer.removeView(moreRow)
                        binding.planContainer.addView(makeDivider())
                        binding.planContainer.addView(
                            makeHint("These have a better-value alternative for the same or less. You can still buy them.")
                        )
                        hidden.forEach { addPlanRow(key, country, it, sorted) }
                    }
                    binding.planContainer.addView(moreRow)
                }

                binding.searchInput.visibility = View.GONE
                binding.countryContainer.visibility = View.GONE
                binding.planContainer.visibility = View.VISIBLE
            }
            .onFailure { toast("Couldn't load plans") }
    }

    private fun addPlanRow(
        key: String, country: SmsPoolApiClient.Country,
        plan: SmsPoolApiClient.Plan, all: List<SmsPoolApiClient.Plan>
    ) {
        binding.planContainer.addView(
            clickableRow(this, "${formatData(plan.dataInGb)} · ${plan.duration} days — \$${plan.price} (${plan.speed})") {
                val better = betterAlternative(plan, all)
                if (better == null) confirmBuy(key, country, plan)
                else suggestBetter(key, country, plan, better)
            }
        )
    }

    /** First modal for a worse-value plan: point at the better one, but still allow buying this. */
    private fun suggestBetter(
        key: String, country: SmsPoolApiClient.Country,
        plan: SmsPoolApiClient.Plan, better: SmsPoolApiClient.Plan
    ) {
        AlertDialog.Builder(this)
            .setTitle("There's a better deal")
            .setMessage(
                "Better deal, for the same or less:\n" +
                    "${formatData(better.dataInGb)} · ${better.duration} days — \$${better.price}\n\n" +
                    "You picked:\n" +
                    "${formatData(plan.dataInGb)} · ${plan.duration} days — \$${plan.price}"
            )
            .setPositiveButton("Buy better deal") { _, _ -> confirmBuy(key, country, better) }
            .setNeutralButton("Buy this anyway") { _, _ -> confirmBuy(key, country, plan) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Cheapest plan that matches-or-beats [a] on data, duration and speed while costing no more. */
    private fun betterAlternative(a: SmsPoolApiClient.Plan, all: List<SmsPoolApiClient.Plan>): SmsPoolApiClient.Plan? {
        return all.filter { isDominated(a, listOf(it)) }
            .minByOrNull { it.price.toDoubleOrNull() ?: Double.MAX_VALUE }
    }

    private fun makeDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(12); bottomMargin = dp(4) }
        setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorOutlineVariant))
    }

    private fun makeHint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun resolveColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data
    }

    private fun speedRank(s: String) = when {
        s.contains("5G") -> 3
        s.contains("4G") -> 2
        s.contains("3G") -> 1
        else -> 0
    }

    private fun isDominated(a: SmsPoolApiClient.Plan, all: List<SmsPoolApiClient.Plan>): Boolean {
        val aPrice = a.price.toDoubleOrNull() ?: return false
        return all.any { b ->
            if (b === a) return@any false
            val bPrice = b.price.toDoubleOrNull() ?: return@any false
            val beatsOrTies = b.dataInGb >= a.dataInGb && b.duration >= a.duration &&
                speedRank(b.speed) >= speedRank(a.speed) && bPrice <= aPrice
            val strictlyBeats = b.dataInGb > a.dataInGb || b.duration > a.duration ||
                speedRank(b.speed) > speedRank(a.speed) || bPrice < aPrice
            beatsOrTies && strictlyBeats
        }
    }

    private fun confirmBuy(key: String, country: SmsPoolApiClient.Country, plan: SmsPoolApiClient.Plan) {
        AlertDialog.Builder(this)
            .setTitle("Buy eSIM")
            .setMessage("${country.name} — ${formatData(plan.dataInGb)} · ${plan.duration} days for \$${plan.price}?")
            .setPositiveButton("Buy") { _, _ -> buy(key, plan) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Doesn't finish() after showing the dialog — that would destroy the activity and dismiss
    // it instantly. The dashboard still picks up the new eSIM on its own next refresh.
    private fun buy(key: String, plan: SmsPoolApiClient.Plan) = lifecycleScope.launch {
        SmsPoolApiClient.purchase(key, plan.id)
            .onSuccess { transactionId ->
                toast("Purchased")
                SmsPoolApiClient.getProfile(key, transactionId)
                    .onSuccess { EsimInstallDialog.show(this@SmsPoolPlansActivity, it) }
                    .onFailure { toast("Purchased, but couldn't load install info") }
            }
            .onFailure { e ->
                AlertDialog.Builder(this@SmsPoolPlansActivity)
                    .setTitle("Couldn't buy")
                    .setMessage(e.message ?: "Purchase failed")
                    .setPositiveButton("OK", null)
                    .show()
            }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
