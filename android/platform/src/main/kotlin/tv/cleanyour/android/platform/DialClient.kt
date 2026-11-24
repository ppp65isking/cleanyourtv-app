package tv.cleanyour.android.platform

import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

// DIAL (Discovery and Launch) over plain HTTP to the TV's port 8008: the protocol every Google
// Cast TV speaks. GET /apps lists the DIAL apps the TV offers; POST /apps/<name> launches one
// (201 Created when it starts). No Cast SDK, no account, no receiver of ours: the TV's own
// receiver does the work, which is why this only opens apps and never mirrors the screen.
// A TV that does not answer is not a DIAL TV (or has it off); the caller reports that plainly.
object DialClient {
    const val PORT = 8008

    data class DialApp(val name: String, val state: String?)

    suspend fun apps(host: String, timeoutMs: Int = 3_000): List<DialApp> = withContext(Dispatchers.IO) {
        val body = get("http://$host:$PORT/apps", timeoutMs) ?: return@withContext emptyList()
        parseApps(body)
    }

    suspend fun launch(host: String, app: String, timeoutMs: Int = 5_000): Boolean = withContext(Dispatchers.IO) {
        post("http://$host:$PORT/apps/$app", timeoutMs)
    }

    private fun get(url: String, timeoutMs: Int): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "GET"
        }
        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun post(url: String, timeoutMs: Int): Boolean {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "text/plain")
        }
        return try {
            conn.outputStream.use { it.write(ByteArray(0)) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    // The <service> list DIAL returns; only <name> and <state> inside each one are read.
    internal fun parseApps(xml: String): List<DialApp> {
        val apps = mutableListOf<DialApp>()
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(StringReader(xml)) }
        var name: String? = null
        var state: String? = null
        var inService = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "service" -> { inService = true; name = null; state = null }
                    "name" -> if (inService) name = parser.nextText()
                    "state" -> if (inService) state = parser.nextText()
                }
                XmlPullParser.END_TAG -> if (parser.name == "service" && inService) {
                    name?.let { apps += DialApp(it, state) }
                    inService = false
                }
            }
            event = parser.next()
        }
        return apps
    }
}
