package fr.foulee.app

import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Plain HttpURLConnection wrapper. The cookie jar matters: the Garmin login is a few requests in one session. */
object Http {
    class Response(val code: Int, val body: String)

    init {
        if (CookieHandler.getDefault() == null) CookieHandler.setDefault(CookieManager())
    }

    fun request(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null, readTimeoutMs: Int = 30_000): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = readTimeoutMs
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            return Response(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }

    /** RFC 3986 percent-encoding, as OAuth 1.0 requires. */
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

    fun query(params: Map<String, String>): String = params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }

    fun parseQuery(text: String): Map<String, String> =
        text.split("&").filter { it.contains("=") }.associate { part ->
            val (key, value) = part.split("=", limit = 2)
            java.net.URLDecoder.decode(key, "UTF-8") to java.net.URLDecoder.decode(value, "UTF-8")
        }
}
