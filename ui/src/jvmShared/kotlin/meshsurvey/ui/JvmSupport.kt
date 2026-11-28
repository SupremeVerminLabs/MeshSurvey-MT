package meshsurvey.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import meshsurvey.core.map.MbTiles
import meshsurvey.core.model.Fix
import meshsurvey.core.util.Clock
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import kotlin.concurrent.fixedRateTimer

class JvmClock : Clock {
    override fun wallMs() = System.currentTimeMillis()
    override fun monotonicMs() = System.nanoTime() / 1_000_000
}

/**
 * Disk-cached tile fetching. Honours the OSM tile usage policy: identifying User-Agent,
 * cache reuse (7 days before refresh), no bulk downloads (tiles are only fetched when viewed).
 */
class TileDiskCache(private val dir: File, private val userAgent: String, private val openMbtiles: (String) -> MbTiles?) {
    private val maxAgeMs = 7L * 24 * 3600 * 1000
    private val mbtiles = HashMap<String, MbTiles?>()

    /** "mbtiles:/path/to/file.mbtiles#z/x/y" -> tile from the local file (never touches the network). */
    private fun fromMbtiles(url: String): ByteArray? {
        val path = url.removePrefix("mbtiles:").substringBefore('#')
        val zxy = url.substringAfter('#', "").split('/').mapNotNull { it.toIntOrNull() }
        if (zxy.size != 3) return null
        val m = synchronized(mbtiles) { mbtiles.getOrPut(path) { openMbtiles(path) } } ?: return null
        return synchronized(m) { m.tile(zxy[0], zxy[1], zxy[2]) }
    }

    suspend fun bytes(url: String, offlineOnly: Boolean): ByteArray? = withContext(Dispatchers.IO) {
        if (url.startsWith("mbtiles:")) return@withContext fromMbtiles(url)
        if (url.startsWith("file:")) return@withContext File(URI(url)).takeIf { it.isFile }?.readBytes()
        val f = File(dir, url.removePrefix("https://").removePrefix("http://").replace(Regex("[^A-Za-z0-9._/-]"), "_"))
        val fresh = f.isFile && System.currentTimeMillis() - f.lastModified() < maxAgeMs
        if (f.isFile && (fresh || offlineOnly)) return@withContext f.readBytes()
        if (offlineOnly) return@withContext null
        try {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", userAgent)
            c.connectTimeout = 8_000; c.readTimeout = 15_000
            if (c.responseCode != 200) return@withContext if (f.isFile) f.readBytes() else null
            val b = c.inputStream.use { it.readBytes() }
            f.parentFile?.mkdirs(); f.writeBytes(b)
            b
        } catch (_: Exception) {
            if (f.isFile) f.readBytes() else null
        }
    }
}

object Formats {
    private val time = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.US) }
    private val dateTime = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    fun time(ms: Long): String = time.get().format(Date(ms))
    fun dateTime(ms: Long): String = dateTime.get().format(Date(ms))
    private val day = ThreadLocal.withInitial { SimpleDateFormat("EEE d MMM yyyy", Locale.US) }
    fun day(ms: Long): String = day.get().format(Date(ms))
    fun utcOffsetMs(ms: Long): Long = java.util.TimeZone.getDefault().getOffset(ms).toLong()
    /** Reads the bundled places file (gzip TSV). */
    fun places(input: java.io.InputStream?): meshsurvey.core.history.Places? = input?.use { s ->
        java.util.zip.GZIPInputStream(s).bufferedReader().useLines { meshsurvey.core.history.Places.parse(it) }
    }
}

/**
 * Minimal HTTP for community sharing (both platforms). Google Apps Script web apps answer a POST with a redirect to the
 * result, which is fetched with GET (the script has already run by then).
 */
object Http {
    fun post(url: String, body: String): String = request(url, "POST", body)
    fun get(url: String): String = request(url, "GET", null)

    private fun request(start: String, method: String, body: String?): String {
        var url = start; var m = method; var b = body
        repeat(5) {
            val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 30_000; requestMethod = m
                setRequestProperty("User-Agent", "MeshSurvey (community sharing)")
                if (b != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
            }
            try {
                if (b != null) c.outputStream.use { it.write(b.encodeToByteArray()) }
                val code = c.responseCode
                if (code in 300..399) {
                    url = c.getHeaderField("Location") ?: throw java.io.IOException("redirect without location")
                    if (code != 307 && code != 308) { m = "GET"; b = null }
                    return@repeat
                }
                val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                if (code >= 400) throw java.io.IOException("HTTP $code ${text.take(120)}")
                return text
            } finally { c.disconnect() }
        }
        throw java.io.IOException("too many redirects")
    }
}

/** Simulated GPS: posts one simulated fix per second on the UI thread. */
class SimGps(private val clock: Clock, private val post: (Runnable) -> Unit) {
    private var timer: Timer? = null
    fun start(onFix: (Fix) -> Unit) {
        stop()
        timer = fixedRateTimer("sim-gps", daemon = true, period = 1000) {
            post(Runnable { onFix(SimSupport.location(clock).current()) })
        }
    }
    fun stop() { timer?.cancel(); timer = null }
}
