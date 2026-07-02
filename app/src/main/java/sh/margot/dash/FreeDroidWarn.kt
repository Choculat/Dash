package sh.margot.dash

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

/**
 * Vendored (and ported to Kotlin) from woheller69/FreeDroidWarn (Apache-2.0):
 * https://github.com/woheller69/FreeDroidWarn
 *
 * Shows a one-time-per-app-version dialog warning the user about Google's 2026/2027
 * developer-verification requirement, which will stop unverified apps from running on certified
 * Android devices. Only re-shows on a version bump (after the user acknowledges with OK).
 */
object FreeDroidWarn {

    fun showWarningOnUpgrade(context: Context) {
        val prefs = context.getSharedPreferences("freedroidwarn", Context.MODE_PRIVATE)
        val build = context.packageManager.getPackageInfo(context.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toInt()
            else @Suppress("DEPRECATION") it.versionCode
        }
        if (build <= prefs.getInt("versionCodeWarn", 0)) return

        val dialog = AlertDialog.Builder(context)
            .setMessage(R.string.dialog_Warning)
            .setNegativeButton(R.string.dialog_more_info) { _, _ -> open(context, "https://keepandroidopen.org") }
            .setNeutralButton(R.string.solution) { _, _ ->
                open(context, "https://github.com/woheller69/FreeDroidWarn?tab=readme-ov-file#solutions")
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.edit().putInt("versionCodeWarn", build).apply()
            }
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            ?.setTextColor(ContextCompat.getColor(context, android.R.color.holo_red_dark))
    }

    private fun open(context: Context, url: String) =
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
