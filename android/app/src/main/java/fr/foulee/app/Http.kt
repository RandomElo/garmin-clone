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

    /**
     * Plain HTTP straight to an IP address, with the real name in the Host header. For when the system resolver cannot find the name but the
     * DNS servers can: HttpURLConnection would refuse clear text to a bare address, a socket does not care. Only for http, never for https.
     */
    fun rawRequest(ip: String, port: Int, host: String, method: String, path: String, headers: Map<String, String>, body: String?, readTimeoutMs: Int): Response {
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(ip, port), 15_000)
            socket.soTimeout = readTimeoutMs
            val payload = body?.toByteArray()
            val head = StringBuilder("$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n")
            headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
            if (payload != null) head.append("Content-Length: ${payload.size}\r\n")
            head.append("\r\n")
            socket.getOutputStream().apply { write(head.toString().toByteArray()); if (payload != null) write(payload); flush() }
            val raw = socket.getInputStream().readBytes()
            val split = String(raw, Charsets.ISO_8859_1).indexOf("\r\n\r\n")
            if (split < 0) throw java.io.IOException("Réponse HTTP illisible")
            val lines = String(raw, 0, split, Charsets.ISO_8859_1).split("\r\n")
            val code = lines.first().split(" ").getOrNull(1)?.toIntOrNull() ?: throw java.io.IOException("Réponse HTTP illisible")
            var bytes = raw.copyOfRange(split + 4, raw.size)
            if (lines.any { it.startsWith("Transfer-Encoding:", ignoreCase = true) && it.contains("chunked", ignoreCase = true) }) {
                val out = java.io.ByteArrayOutputStream()
                var pos = 0
                while (pos < bytes.size) {
                    val eol = String(bytes, pos, bytes.size - pos, Charsets.ISO_8859_1).indexOf("\r\n")
                    if (eol < 0) break
                    val size = String(bytes, pos, eol, Charsets.ISO_8859_1).trim().substringBefore(';').toInt(16)
                    if (size == 0) break
                    out.write(bytes, pos + eol + 2, size)
                    pos += eol + 2 + size + 2
                }
                bytes = out.toByteArray()
            }
            return Response(code, String(bytes, Charsets.UTF_8))
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
