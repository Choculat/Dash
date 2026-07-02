package sh.margot.dash.services.koodo

import android.content.Context
import android.content.SharedPreferences
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

class KoodoCookieJar : CookieJar {

    private val store = mutableMapOf<String, MutableList<Cookie>>()
    private var prefs: SharedPreferences? = null

    fun attach(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("koodo_cookies", Context.MODE_PRIVATE)
        load()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.getOrPut(url.host) { mutableListOf() }.apply {
            removeAll { existing -> cookies.any { it.name == existing.name } }
            addAll(cookies)
        }
        save()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store[url.host]?.filter { it.matches(url) } ?: emptyList()

    fun hasSessionFor(host: String) = !store[host].isNullOrEmpty()

    fun clear() {
        store.clear()
        prefs?.edit()?.remove("cookies_json")?.apply()
    }

    private fun save() {
        val arr = JSONArray()
        store.forEach { (host, cookies) ->
            cookies.forEach { arr.put(toJson(host, it)) }
        }
        prefs?.edit()?.putString("cookies_json", arr.toString())?.apply()
    }

    private fun load() {
        val raw = prefs?.getString("cookies_json", null) ?: return
        try {
            val arr = JSONArray(raw)
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val cookie = fromJson(o) ?: continue
                if (cookie.persistent && cookie.expiresAt < now) continue
                store.getOrPut(o.getString("host")) { mutableListOf() }.add(cookie)
            }
        } catch (_: Exception) {
            prefs?.edit()?.remove("cookies_json")?.apply()
        }
    }

    private fun toJson(host: String, c: Cookie) = JSONObject().apply {
        put("host", host); put("name", c.name); put("value", c.value)
        put("domain", c.domain); put("path", c.path); put("expiresAt", c.expiresAt)
        put("secure", c.secure); put("httpOnly", c.httpOnly)
        put("hostOnly", c.hostOnly); put("persistent", c.persistent)
    }

    private fun fromJson(o: JSONObject): Cookie? = try {
        Cookie.Builder()
            .name(o.getString("name")).value(o.getString("value")).path(o.getString("path"))
            .apply {
                val domain = o.getString("domain")
                if (o.getBoolean("hostOnly")) hostOnlyDomain(domain) else domain(domain)
                if (o.getBoolean("persistent")) expiresAt(o.getLong("expiresAt"))
                if (o.getBoolean("secure")) secure()
                if (o.getBoolean("httpOnly")) httpOnly()
            }.build()
    } catch (_: Exception) { null }
}
