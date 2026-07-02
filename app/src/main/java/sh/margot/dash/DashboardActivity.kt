package sh.margot.dash

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import sh.margot.dash.databinding.ActivityDashboardBinding
import sh.margot.dash.services.PhoneSimsCardController
import sh.margot.dash.services.ServiceCard
import sh.margot.dash.services.koodo.KoodoCardController
import sh.margot.dash.services.smspool.SmsPoolCardController

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private val cards: List<ServiceCard> by lazy {
        listOf(KoodoCardController(this), SmsPoolCardController(this), PhoneSimsCardController(this))
    }
    private val cardViews = mutableMapOf<String, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.swipeRefresh.setColorSchemeResources(R.color.dash_primary)
        binding.swipeRefresh.setOnRefreshListener { refreshAll(showSpinner = true) }

        cards.forEach { card ->
            val view = card.createView(layoutInflater, binding.serviceContainer)
            binding.serviceContainer.addView(view)
            cardViews[card.id] = view
        }

        FreeDroidWarn.showWarningOnUpgrade(this)
    }

    override fun onResume() {
        super.onResume()
        // Silent: each card only touches its own UI if something it fetched actually changed,
        // so there's no need for a visible spinner on every app open/resume.
        refreshAll(showSpinner = false)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Re-render cards once the phone permission decision comes back (for PhoneSimsCardController).
        refreshAll(showSpinner = false)
    }

    private fun refreshAll(showSpinner: Boolean) {
        if (showSpinner) binding.swipeRefresh.isRefreshing = true
        var remaining = cards.size
        // Each card refreshes (and auto-reconnects) in its own independent coroutine, so a
        // slow/stuck service never delays the others from starting or finishing.
        cards.forEach { card ->
            lifecycleScope.launch {
                cardViews[card.id]?.let { card.refresh(it) }
                if (--remaining == 0 && showSpinner) binding.swipeRefresh.isRefreshing = false
            }
        }
    }
}
