package sh.margot.dash.services

import android.content.Context

/** Persists each card's last rendered state so a reload shows the last-known data immediately instead of empty, while the real (possibly updated) data loads in the background. */
object CardStateCache {
    private const val PREFS = "dash_card_cache"

    fun save(context: Context, cardId: String, json: String) {
        prefs(context).edit().putString(cardId, json).apply()
    }

    fun load(context: Context, cardId: String): String? = prefs(context).getString(cardId, null)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
