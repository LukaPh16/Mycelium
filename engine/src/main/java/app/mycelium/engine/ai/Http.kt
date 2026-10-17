package app.mycelium.engine.ai

import java.net.HttpURLConnection
import java.net.URL

data class HttpReply(val status: Int, val body: String)

/** POST over HttpURLConnection. Never logs: headers carry the API key. */
typealias Transport = (url: String, headers: Map<String, String>, body: String, timeoutMs: Int) -> HttpReply

object Http {
    val post: Transport = { url, headers, body, timeoutMs ->
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.doOutput = true
            c.useCaches = false
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            HttpReply(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }
}
