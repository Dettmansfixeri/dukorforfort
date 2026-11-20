package se.dettmansfixeri.dukorforfort

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class SpeedLimit(val kmh: Int)

class TrafikverketException(message: String) : Exception(message)

/**
 * Hämtar hastighetsgräns via vår egen server, som lägger till API-nyckeln
 * och frågar Trafikverkets öppna API (NVDB, objekttyp Hastighetsgräns).
 */
object TrafikverketApi {
    private const val ENDPOINT = "https://dukorforfort.dettmansfixeri.se/api/hastighet.php"

    suspend fun fetchSpeedLimit(lat: Double, lon: Double): SpeedLimit? =
        withContext(Dispatchers.IO) {
            val (east, north) = Sweref99.fromWgs84(lat, lon)
            val body = String.format(Locale.US, "e=%.1f&n=%.1f", east, north)

            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 10000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                parse(text, code)
            } finally {
                conn.disconnect()
            }
        }

    internal fun parse(json: String, httpCode: Int = 200): SpeedLimit? {
        val o = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw TrafikverketException("Ogiltigt svar från servern (HTTP $httpCode)")
        }
        if (o.has("error")) throw TrafikverketException(o.optString("error"))
        if (o.isNull("limit")) return null
        return SpeedLimit(o.getInt("limit"))
    }
}
