package fr.foulee.app

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AuthException(message: String) : IOException(message)

/**
 * Garmin Connect login the way the mobile app does it (same flow as the garth library):
 * SSO mobile sign-in -> service ticket -> OAuth1 token -> OAuth2 token, refreshed from the OAuth1 token.
 * Garmin has no public API for individuals and can change this flow at any time.
 */
class GarminAuth(context: Context) {
    private val settings = Settings(context)

    sealed class Login {
        object Done : Login()
        class NeedsMfa(val method: String) : Login()
    }

    fun login(email: String, password: String): Login {
        // The OAuth consumer key comes from another host: fetch it while the two sign-in requests run
        prefetchConsumer()
        Http.request("GET", "$SSO/mobile/sso/en/sign-in?clientId=$CLIENT_ID", pageHeaders())
        val body = JSONObject().put("username", email).put("password", password).put("rememberMe", false).put("captchaToken", "")
        val response = Http.request("POST", "$SSO/mobile/api/login?$loginQuery", jsonHeaders(), body.toString())
        val json = parse(response, "connexion")
        return when (val type = json.optJSONObject("responseStatus")?.optString("type")) {
            "SUCCESSFUL" -> {
                finish(json.getString("serviceTicketId"))
                Login.Done
            }
            "MFA_REQUIRED" -> {
                mfaMethod = json.optJSONObject("customerMfaInfo")?.optString("mfaLastMethodUsed")?.takeIf { it.isNotEmpty() } ?: "email"
                Login.NeedsMfa(mfaMethod)
            }
            else -> throw AuthException("Connexion refusée par Garmin ($type). Vérifie l'e-mail et le mot de passe.")
        }
    }

    fun verifyMfa(code: String) {
        val body = JSONObject().put("mfaMethod", mfaMethod).put("mfaVerificationCode", code).put("rememberMyBrowser", false).put("reconsentList", org.json.JSONArray()).put("mfaSetup", false)
        val json = parse(Http.request("POST", "$SSO/mobile/api/mfa/verifyCode?$loginQuery", jsonHeaders(), body.toString()), "code MFA")
        if (json.optJSONObject("responseStatus")?.optString("type") != "SUCCESSFUL") throw AuthException("Code MFA refusé.")
        finish(json.getString("serviceTicketId"))
    }

    /** GET on the Garmin API with the OAuth2 token, refreshed when it is about to expire. */
    fun get(path: String): JSONObject {
        val body = getText(path)
        return if (body.trimStart().startsWith("[")) JSONObject().put("items", org.json.JSONArray(body)) else JSONObject(body)
    }

    private fun getText(path: String): String {
        if (!settings.loggedIn) throw AuthException("Pas connecté à Garmin.")
        // Calls can run at the same time: only one of them renews the token
        synchronized(this) {
            if (settings.accessToken == null || settings.accessExpiresAt - 60 < System.currentTimeMillis() / 1000) refresh()
        }
        var response = Http.request("GET", API + path, apiHeaders())
        if (response.code == 401) {
            synchronized(this) { refresh() }
            response = Http.request("GET", API + path, apiHeaders())
        }
        // Garmin throttles a burst of calls (429) and has hiccups (5xx): wait and ask again, a few times, before giving up
        var attempt = 0
        while ((response.code == 429 || response.code in 500..599) && attempt < 3) {
            Thread.sleep(2000L shl attempt)
            attempt++
            response = Http.request("GET", API + path, apiHeaders())
        }
        if (response.code == 401 || response.code == 403) throw AuthException("Session Garmin expirée, reconnecte-toi.")
        if (response.code !in 200..299) throw IOException("Garmin HTTP ${response.code} sur $path")
        return response.body
    }

    private fun apiHeaders() = mapOf("Authorization" to "Bearer ${settings.accessToken}", "User-Agent" to UA_APP)

    private fun finish(ticket: String) {
        // Sets the load-balancer cookie; failing here is not fatal
        runCatching { Http.request("GET", "$SSO/portal/sso/embed", pageHeaders()) }

        val query = mapOf("ticket" to ticket, "login-url" to SERVICE, "accepts-mfa-tokens" to "true")
        val url = "$API/oauth-service/oauth/preauthorized"
        val response = Http.request("GET", "$url?${Http.query(query)}", mapOf("User-Agent" to UA_APP, "Authorization" to oauth1Header("GET", url, query, emptyMap(), null, null)))
        if (response.code !in 200..299) throw AuthException("Garmin a refusé le ticket de connexion (HTTP ${response.code}).")
        val token = Http.parseQuery(response.body)
        settings.oauth1Token = token["oauth_token"] ?: throw AuthException("Réponse Garmin inattendue.")
        settings.oauth1Secret = token["oauth_token_secret"] ?: throw AuthException("Réponse Garmin inattendue.")
        refresh(audience = true)
        refreshProfile()
    }

    /** Display name (used in API paths), first name and picture. */
    fun refreshProfile() {
        val profile = get("/userprofile-service/socialProfile")
        settings.displayName = profile.optString("displayName").takeIf { it.isNotEmpty() }
        settings.firstName = profile.optString("fullName").trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() }
        settings.photoUrl = profile.optString("profileImageUrlMedium").takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun refresh(audience: Boolean = false) {
        val form = if (audience) mapOf("audience" to "GARMIN_CONNECT_MOBILE_ANDROID_DI") else emptyMap()
        val url = "$API/oauth-service/oauth/exchange/user/2.0"
        val headers = mapOf(
            "User-Agent" to UA_APP,
            "Content-Type" to "application/x-www-form-urlencoded",
            "Authorization" to oauth1Header("POST", url, emptyMap(), form, settings.oauth1Token, settings.oauth1Secret),
        )
        val response = Http.request("POST", url, headers, Http.query(form))
        if (response.code !in 200..299) throw AuthException("Garmin a refusé le renouvellement de session (HTTP ${response.code}).")
        val json = JSONObject(response.body)
        settings.accessToken = json.getString("access_token")
        settings.accessExpiresAt = System.currentTimeMillis() / 1000 + json.optLong("expires_in", 3600)
    }

    @Synchronized
    private fun consumer(): Pair<String, String> {
        cachedConsumer?.let { return it }
        val json = JSONObject(Http.request("GET", CONSUMER_URL).body)
        return (json.getString("consumer_key") to json.getString("consumer_secret")).also { cachedConsumer = it }
    }

    private fun prefetchConsumer() {
        if (cachedConsumer == null) kotlin.concurrent.thread { runCatching { consumer() } }
    }

    /** OAuth 1.0 HMAC-SHA1 Authorization header; query and form parameters are part of the signature. */
    private fun oauth1Header(method: String, url: String, query: Map<String, String>, form: Map<String, String>, token: String?, tokenSecret: String?): String {
        val (key, secret) = consumer()
        val oauth = sortedMapOf(
            "oauth_consumer_key" to key,
            "oauth_nonce" to UUID.randomUUID().toString().replace("-", ""),
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to (System.currentTimeMillis() / 1000).toString(),
            "oauth_version" to "1.0",
        )
        if (token != null) oauth["oauth_token"] = token
        val all = (oauth + query + form).entries.map { Http.encode(it.key) to Http.encode(it.value) }.sortedWith(compareBy({ it.first }, { it.second }))
        val base = listOf(method, Http.encode(url), Http.encode(all.joinToString("&") { "${it.first}=${it.second}" })).joinToString("&")
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec("${Http.encode(secret)}&${Http.encode(tokenSecret ?: "")}".toByteArray(), "HmacSHA1")) }
        oauth["oauth_signature"] = android.util.Base64.encodeToString(mac.doFinal(base.toByteArray()), android.util.Base64.NO_WRAP)
        return "OAuth " + oauth.entries.joinToString(", ") { "${Http.encode(it.key)}=\"${Http.encode(it.value)}\"" }
    }

    private fun parse(response: Http.Response, what: String): JSONObject {
        if (response.code == 429) throw AuthException("Garmin limite les tentatives (429), réessaie plus tard.")
        return runCatching { JSONObject(response.body) }.getOrElse { throw AuthException("Réponse Garmin illisible pendant la $what (HTTP ${response.code}).") }
    }

    private fun pageHeaders() = mapOf("User-Agent" to UA_WEB, "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8", "Accept-Language" to "en-US,en;q=0.9")

    private fun jsonHeaders() = mapOf("User-Agent" to UA_WEB, "Content-Type" to "application/json", "Accept" to "application/json, text/plain, */*", "Origin" to SSO, "Referer" to "$SSO/mobile/sso/en/sign-in?clientId=$CLIENT_ID")

    private val loginQuery get() = Http.query(mapOf("clientId" to CLIENT_ID, "locale" to "en-US", "service" to SERVICE))

    private var mfaMethod = "email"

    companion object {
        private const val SSO = "https://sso.garmin.com"
        private const val API = "https://connectapi.garmin.com"
        private const val SERVICE = "https://mobile.integration.garmin.com/gcm/android"
        private const val CLIENT_ID = "GCM_ANDROID_DARK"
        private const val CONSUMER_URL = "https://thegarth.s3.amazonaws.com/oauth_consumer.json"
        private const val UA_APP = "com.garmin.android.apps.connectmobile"
        private const val UA_WEB = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
        private var cachedConsumer: Pair<String, String>? = null
    }
}
