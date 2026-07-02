package sh.margot.dash.services

import android.os.Handler
import android.os.Looper
import android.widget.TextView

/**
 * Wires a status label so a single tap arms a disconnect (label flips to [armedLabel]); a
 * second tap within [armedMillis] invokes [onConfirm] (passed to [bind]); no second tap within
 * that window silently reverts back to [connectedLabel]. Replaces a separate always-visible
 * "Disconnect" button.
 */
class DisconnectTap(
    private val statusView: TextView,
    private val connectedLabel: String = "Connected",
    private val armedLabel: String = "Disconnect?",
    private val armedMillis: Long = 5000L,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var armed = false
    private var revertRunnable: Runnable? = null

    /** Call each time the card renders as connected; wires the tap and shows the baseline label. */
    fun bind(onConfirm: () -> Unit) {
        reset()
        statusView.setOnClickListener {
            if (!armed) arm() else { reset(); onConfirm() }
        }
    }

    /** Call when the card is no longer connected, to drop the click handler and any pending timer. */
    fun unbind() {
        cancelPending()
        statusView.setOnClickListener(null)
    }

    private fun arm() {
        armed = true
        statusView.text = armedLabel
        cancelPending()
        val runnable = Runnable { reset() }
        revertRunnable = runnable
        handler.postDelayed(runnable, armedMillis)
    }

    private fun reset() {
        armed = false
        cancelPending()
        statusView.text = connectedLabel
    }

    private fun cancelPending() {
        revertRunnable?.let { handler.removeCallbacks(it) }
        revertRunnable = null
    }
}
