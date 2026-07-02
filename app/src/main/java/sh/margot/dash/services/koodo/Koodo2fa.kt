package sh.margot.dash.services.koodo

import android.content.Context

/**
 * Remembers whether the Koodo account uses SMS 2FA. Once known, the card stops silently
 * re-authenticating on launch (which could make Koodo send a fresh SMS code every time) and
 * instead waits for the user to tap sign-in before contacting Koodo at all.
 */
object Koodo2fa {
    private const val PREFS = "koodo_state"
    private const val KEY = "uses_2fa"

    fun isKnown(context: Context) = prefs(context).getBoolean(KEY, false)

    fun set(context: Context, usesTwoFactor: Boolean) =
        prefs(context).edit().putBoolean(KEY, usesTwoFactor).apply()

    fun clear(context: Context) = prefs(context).edit().remove(KEY).apply()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
