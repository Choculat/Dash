package sh.margot.dash.services.koodo

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

object KoodoAuthService {

    private const val TAG = "KoodoAuth"
    private const val AUTH_GW = "https://auth-gateway.koodomobile.com"
    private const val WWW = "https://www.koodomobile.com"
    private val SELF = KoodoApiClient.BASE_URL
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private const val CLIENT_ID = "01786d92-7f47-4397-8dcd-fc6948320526"
    private const val BRAND_ID = "a29vZG9fc2FsdGVkX2NhcmFtZWxfICAgICAgICAgICA="
    private const val APP_ID = "KPRE-UCARE"
    private const val SCOPE =
        "1965 1966 1967 1968 1969 1970 1971 1972 1975 1977 1979 1995 S-1 email openid profile " +
            "1934 1882 2049 2576 4372 4399 2197 2211 2181 1365 1388 2002 381 382 1285 257"

    data class Profile(val phoneNumber: String?, val twoFactorFlag: Boolean)

    class AuthException(message: String, val invalidCredentials: Boolean = false) : Exception(message)

    suspend fun login(email: String, password: String): Result<Profile> = withContext(Dispatchers.IO) {
        try {
            val r1 = post(
                "$AUTH_GW/v1/flows/password_login_v1",
                JSONObject().put("loginid", email).put("password", password).put("authdomain", "koodoprepaid"),
                origin = WWW
            )
            if (r1.first == 401 || r1.first == 403)
                return@withContext Result.failure(AuthException("Invalid email or password", invalidCredentials = true))
            if (r1.first !in 200..299)
                return@withContext Result.failure(AuthException("Login failed (${r1.first})"))

            post("$AUTH_GW/v1/flows/login?acr=ci-loa3-kpre", JSONObject(), origin = WWW)
            authorize()

            val (code, body) = post(
                "$SELF/post-auth",
                JSONObject().put("brandId", BRAND_ID).put("provider", "koodo").put("applicationId", APP_ID),
                origin = SELF
            )
            if (code !in 200..299)
                return@withContext Result.failure(AuthException("Post-auth failed ($code)"))

            val json = JSONObject(body)
            val profile = Profile(
                phoneNumber = json.optString("phoneNumber").ifBlank { null },
                twoFactorFlag = json.optBoolean("twoFactorFlag", false)
            )

            val status = KoodoApiClient.checkStatus()
            if (status.getOrNull()?.optString("status") != "running")
                return@withContext Result.failure(AuthException("Session not active after login"))

            Result.success(profile)
        } catch (e: Exception) {
            Log.e(TAG, "login: ${e.message}")
            Result.failure(e)
        }
    }

    private fun authorize() {
        val url = Uri.parse("$AUTH_GW/as/authorization.oauth2").buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("service_type", "koodo_prepaid")
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", SELF)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("response_mode", "query")
            .appendQueryParameter("nonce", UUID.randomUUID().toString().replace("-", ""))
            .appendQueryParameter("state", UUID.randomUUID().toString().replace("-", ""))
            .appendQueryParameter("from", "login")
            .build().toString()
        val req = Request.Builder().url(url).get()
            .header("Accept", "text/html").header("Referer", "$WWW/").build()
        KoodoApiClient.httpClient.newCall(req).execute().use { }
    }

    suspend fun generateOtp(profile: Profile): Result<String> = withContext(Dispatchers.IO) {
        try {
            val phone = profile.phoneNumber
                ?: return@withContext Result.failure(AuthException("No phone number on file"))
            val pretty = formatPhone(phone)
            val (code, body) = post(
                "$SELF/otp/generate",
                JSONObject().put("brandId", BRAND_ID).put("applicationId", APP_ID)
                    .put("sentBySms", true).put("receipent", pretty)
                    .put("phoneNumber", pretty).put("customerName", " ").put("language", "en"),
                origin = SELF
            )
            if (code !in 200..299) return@withContext Result.failure(AuthException("OTP send failed"))
            val secret = JSONObject(body).optString("secret")
            if (secret.isBlank()) return@withContext Result.failure(AuthException("No OTP secret returned"))
            Result.success(secret)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun validateOtp(secret: String, code: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().put("brandId", BRAND_ID).put("applicationId", APP_ID)
                .put("secret", secret).put("otp", code.trim())
            val (status, _) = post("$SELF/otp/validate", body, origin = SELF)
            Result.success(status in 200..299)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun formatPhone(raw: String): String {
        val d = raw.filter { it.isDigit() }.removePrefix("1")
        return if (d.length == 10) "${d.substring(0,3)} ${d.substring(3,6)} ${d.substring(6)}" else raw
    }

    private fun post(url: String, body: JSONObject, origin: String): Pair<Int, String> {
        val req = Request.Builder().url(url)
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "application/json")
            .header("Origin", origin)
            .header("Referer", "$origin/")
            .build()
        KoodoApiClient.httpClient.newCall(req).execute().use { resp ->
            return resp.code to (resp.body?.string() ?: "")
        }
    }
}
