package sh.margot.dash.services.smspool

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** SMSPool is authenticated with a single static per-account API key — no session/cookies. */
object SmsPoolApiClient {

    private const val TAG = "SmsPoolApi"
    const val SERVICE_ID = "smspool"
    private const val BASE_URL = "https://api.smspool.net"

    data class Country(val id: Int, val countryCode: String, val name: String, val price: String)
    data class Plan(val id: Int, val dataInGb: Double, val duration: Int, val price: String, val speed: String)
    data class EsimProfile(
        val smdp: String, val activationCode: String, val ac: String,
        val pin: String, val puk: String, val apn: String,
        val remainingData: String, val totalData: String
    )

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun getEsimHistory(apiKey: String, page: Int = 1, limit: Int = 20): Result<JSONObject> =
        post("esim/history", "key" to apiKey, "page" to page.toString(), "limit" to limit.toString())
            .mapCatching { JSONObject(it) }

    suspend fun getCountries(apiKey: String): Result<List<Country>> =
        post("esim/pricing", "key" to apiKey, "start" to "0", "length" to "300", "search" to "").mapCatching { text ->
            val data = JSONObject(text).getJSONArray("data")
            List(data.length()) { i ->
                val o = data.getJSONObject(i)
                Country(o.getInt("ID"), o.getString("countryCode"), o.getString("name"), o.getString("price"))
            }
        }

    suspend fun getPlans(apiKey: String, countryCode: String): Result<List<Plan>> =
        post("esim/plans", "key" to apiKey, "country" to countryCode).mapCatching { text ->
            val arr = JSONArray(text)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Plan(o.getInt("ID"), o.getDouble("dataInGb"), o.getInt("duration"), o.getString("price"), o.getString("speed"))
            }
        }

    /** Spends real money on the account tied to [apiKey]. Returns the new transactionId. */
    suspend fun purchase(apiKey: String, planId: Int): Result<String> =
        post("esim/purchase", "key" to apiKey, "plan" to planId.toString()).mapCatching { text ->
            val o = JSONObject(text)
            if (o.optInt("success") == 1) o.getString("transactionId")
            // SMSPool reports failures two ways: a top-level "message" (e.g. insufficient
            // balance) or an "errors" array (e.g. a bad/missing parameter). Surface either.
            else throw IOException(
                o.optString("message").ifBlank {
                    o.optJSONArray("errors")?.optJSONObject(0)?.optString("message").orEmpty()
                }.ifBlank { "Purchase failed" }
            )
        }

    suspend fun getProfile(apiKey: String, transactionId: String): Result<EsimProfile> =
        post("esim/profile", "key" to apiKey, "transactionId" to transactionId).mapCatching { text ->
            val o = JSONObject(text)
            EsimProfile(
                o.getString("smdp"), o.getString("activationCode"), o.getString("ac"),
                o.optString("pin"), o.optString("puk"), o.optString("apn"),
                o.optString("remainingData"), o.optString("totalData")
            )
        }

    suspend fun getBalance(apiKey: String): Result<String> =
        post("request/balance", "key" to apiKey).mapCatching { JSONObject(it).getString("balance") }

    private suspend fun post(path: String, vararg params: Pair<String, String>): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val body = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
                val req = Request.Builder().url("$BASE_URL/$path").post(body).build()
                httpClient.newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: "{}"
                    if (resp.isSuccessful) Result.success(text) else Result.failure(IOException("HTTP ${resp.code}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "$path: ${e.message}")
                Result.failure(e)
            }
        }
}
