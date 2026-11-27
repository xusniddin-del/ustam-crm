package uz.ustam.sms

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Ustam CRM serveriga so'rov. Apps Script javobi 302 orqali keladi — HttpURLConnection uni o'zi kuzatadi. */
object Api {
    fun post(url: String, body: JSONObject): JSONObject {
        var target = url
        var payload: ByteArray? = body.toString().toByteArray(Charsets.UTF_8)
        repeat(5) {
            val c = URL(target).openConnection() as HttpURLConnection
            c.connectTimeout = 20000
            c.readTimeout = 45000
            c.instanceFollowRedirects = false
            if (payload != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "text/plain;charset=utf-8")
                c.outputStream.use { it.write(payload) }
            } else {
                c.requestMethod = "GET"
            }
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw Exception("Server yo'naltirishi noto'g'ri")
                c.disconnect()
                target = loc
                payload = null // Apps Script javobi GET orqali olinadi
                return@repeat
            }
            val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            c.disconnect()
            if (code !in 200..299) throw Exception("Server xatosi: $code")
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw Exception("Server javobi noto'g'ri. Server manzilini tekshiring.")
            }
        }
        throw Exception("Juda ko'p yo'naltirish")
    }
}
