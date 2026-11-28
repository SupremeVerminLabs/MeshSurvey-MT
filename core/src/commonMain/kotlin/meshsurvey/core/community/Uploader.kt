package meshsurvey.core.community

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import meshsurvey.core.store.ObsFilter
import meshsurvey.core.store.SqlStore
import meshsurvey.core.util.newId

/**
 * Optional sharing to a community Google Sheet (through the Apps Script in tools/community). Local data is never changed or
 * deleted by sharing. Uploads go from a watermark (last successfully shared reception time) forward, so a failed upload is
 * simply retried next time; nothing is queued separately.
 *
 * Network access is injected ([post]) so this works on every platform and in tests. Never called for simulated databases.
 */
class Uploader(private val store: SqlStore, private val appVersion: String) {
    companion object {
        const val KEY_SETTINGS = "community_settings"
        const val KEY_WATERMARK = "community_watermark"
        const val KEY_SUBMITTER = "community_submitter"
        const val KEY_LAST = "community_last_result"
        /** Receptions younger than this wait for the next upload (the day's summary is still growing). */
        const val SETTLE_MS = 5 * 60_000L
        const val AUTO_INTERVAL_MS = 30 * 60_000L
        const val CHUNK_ROWS = 400
    }

    var settings: ShareSettings = load(); private set
    val lastResult: String? get() = store.getMeta(KEY_LAST)
    val watermark: Long get() = store.getMeta(KEY_WATERMARK)?.toLongOrNull() ?: 0L
    private var lastAttemptMs = 0L

    /** Random token per install (not the device id); lets the script count distinct contributors and rate-limit. */
    val submitter: String get() = store.getMeta(KEY_SUBMITTER) ?: newId(bytes = 8).also { store.setMeta(KEY_SUBMITTER, it) }

    fun update(s: ShareSettings) { settings = s; store.setMeta(KEY_SETTINGS, encode(s)) }

    data class Preview(val rows: List<ShareRow>, val excluded: Community.Excluded, val fromMs: Long, val toMs: Long) {
        val chunks get() = rows.chunked(CHUNK_ROWS)
    }

    fun preview(nowMs: Long, dayOf: (Long) -> String, ownNodes: Set<Long>): Preview {
        val from = watermark; val to = nowMs - SETTLE_MS
        val obs = if (to > from) store.observations(ObsFilter(fromMs = from + 1, toMs = to + 1)) else emptyList()
        val (rows, ex) = Community.build(obs, settings, dayOf, ownNodes)
        return Preview(rows, ex, from, to)
    }

    /**
     * Sends everything not yet shared, in chunks. The watermark moves only when every chunk was accepted; a retry resends the
     * same batch id and chunk numbers, which the script skips if it already has them. [post] performs the HTTP POST (JSON body)
     * and returns the response text, or throws.
     */
    fun upload(nowMs: Long, dayOf: (Long) -> String, ownNodes: Set<Long>, post: (String, String) -> String): String {
        lastAttemptMs = nowMs
        val s = settings
        if (!s.enabled || !s.includeMeasurements) return "Sharing is off"
        if (s.endpoint.isBlank()) return "No community address set"
        if (s.homeZoneOn && (s.homeLat == null || s.homeLon == null)) return "Set the home privacy zone first (or turn it off)"
        val p = preview(nowMs, dayOf, ownNodes)
        if (p.rows.isEmpty()) { store.setMeta(KEY_WATERMARK, p.toMs.coerceAtLeast(watermark).toString()); return record("Nothing new to share") }
        val batch = "${submitter.take(6)}-${p.fromMs}-${p.toMs}"
        val chunks = p.chunks
        for ((i, c) in chunks.withIndex()) {
            val reply = try { post(s.endpoint, Community.payload(c, submitter, appVersion, batch, i, chunks.size)) } catch (e: Exception) {
                return record("Upload failed at part ${i + 1} of ${chunks.size} (will retry): ${e.message ?: e::class.simpleName}")
            }
            val ok = parseOk(reply)
            if (!ok.first) return record("The community sheet refused the upload: ${ok.second ?: reply.take(120)}")
        }
        store.setMeta(KEY_WATERMARK, p.toMs.toString())
        return record("Shared ${p.rows.size} squares in ${chunks.size} part(s)")
    }

    /** True when auto-upload is on and the last attempt is old enough. */
    fun autoDue(nowMs: Long) = settings.enabled && settings.autoUpload && settings.endpoint.isNotBlank() && nowMs - lastAttemptMs >= AUTO_INTERVAL_MS

    fun reportOddity(text: String, lat: Double?, lon: Double?, day: String, post: (String, String) -> String): String {
        val s = settings
        if (!s.enabled || !s.includeOddities) return "Oddity reports are not being shared (Settings → Community)"
        if (s.endpoint.isBlank()) return "No community address set"
        return try {
            val r = parseOk(post(s.endpoint, Community.oddityPayload(text, lat, lon, day, submitter, appVersion, s)))
            if (r.first) "Report sent" else "Report refused: ${r.second}"
        } catch (e: Exception) { "Report failed: ${e.message}" }
    }

    private fun record(m: String): String { store.setMeta(KEY_LAST, m); return m }

    private fun parseOk(reply: String): Pair<Boolean, String?> = try {
        val o = Json.parseToJsonElement(reply) as JsonObject
        ((o["ok"] as? JsonPrimitive)?.booleanOrNull == true) to ((o["message"] as? JsonPrimitive)?.content)
    } catch (_: Exception) { false to "unexpected reply (is the address the script's web-app URL?)" }

    private fun load(): ShareSettings {
        val o = store.getMeta(KEY_SETTINGS)?.let { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() } ?: return ShareSettings()
        fun b(k: String, d: Boolean) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: d
        fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
        return ShareSettings(b("enabled", false), (o["endpoint"] as? JsonPrimitive)?.content ?: "", b("measurements", true), b("oddities", true),
            b("auto", false), d("home_lat"), d("home_lon"), d("home_radius_m") ?: 250.0, b("home_zone", true))
    }

    private fun encode(s: ShareSettings) = buildJsonObject {
        put("enabled", s.enabled); put("endpoint", s.endpoint); put("measurements", s.includeMeasurements); put("oddities", s.includeOddities)
        put("auto", s.autoUpload); put("home_lat", s.homeLat); put("home_lon", s.homeLon); put("home_radius_m", s.homeRadiusM); put("home_zone", s.homeZoneOn)
    }.toString()
}
