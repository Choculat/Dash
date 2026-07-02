package sh.margot.dash.services.koodo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object KoodoApiClient {

    private const val TAG = "KoodoApi"
    const val SERVICE_ID = "koodo"
    const val BASE_URL = "https://prepaidselfserve.koodomobile.com"
    val HOST: String = BASE_URL.toHttpUrl().host
    private val JSON = "application/json; charset=utf-8".toMediaType()

    val cookieJar = KoodoCookieJar()

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) = cookieJar.attach(context)

    suspend fun checkStatus(): Result<JSONObject> =
        post("$BASE_URL/status", JSONObject().put("brand", "koodo"))

    suspend fun getPlanUsage(): Result<JSONObject> =
        proxy("retrievePlanUsage", JSONObject()
            .put("brandId", BRAND_ID).put("applicationId", APP_ID))

    suspend fun getFunds(): Result<JSONObject> =
        proxy("retrieveFunds", JSONObject()
            .put("brandId", BRAND_ID).put("applicationId", APP_ID))

    private suspend fun proxy(operation: String, params: JSONObject) =
        post("$BASE_URL/proxy", JSONObject()
            .put("service", "account")
            .put("operation", operation)
            .put("params", params))

    private suspend fun post(url: String, body: JSONObject): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(url)
                    .post(body.toString().toRequestBody(JSON))
                    .header("Accept", "application/json")
                    .header("Origin", BASE_URL)
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: "{}"
                    if (resp.isSuccessful) Result.success(JSONObject(text))
                    else Result.failure(IOException("HTTP ${resp.code}"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "$url: ${e.message}")
                Result.failure(e)
            }
        }

    private const val BRAND_ID = "a29vZG9fc2FsdGVkX2NhcmFtZWxfICAgICAgICAgICA="
    private const val APP_ID = "KPRE-UCARE"
}
