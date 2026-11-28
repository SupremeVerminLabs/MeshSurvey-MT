package meshsurvey.core.community

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import meshsurvey.core.map.CellStats
import meshsurvey.core.map.Grid
import meshsurvey.core.model.FixSource
import meshsurvey.core.model.Observation
import meshsurvey.core.model.RxStatus
import meshsurvey.core.model.Source
import meshsurvey.core.util.Geo
import kotlin.math.round

/** Where community data goes. OFF disables both contributing and downloading. */
enum class Destination { DEFAULT, CUSTOM, OFF }

/** Area whose receptions are never shared (home, work, …). */
data class PrivacyZone(val lat: Double, val lon: Double, val radiusM: Double = 250.0, val label: String = "Home")

/**
 * Community settings. Nothing is uploaded without an explicit action by the user (a prompt answered with "upload", the Upload
 * button of a session) or the separate automatic-upload permission, which is off by default and never switched on by the app.
 */
data class ShareSettings(
    val destination: Destination = Destination.DEFAULT,
    val customEndpoint: String = "",
    /** Download combined community squares for the map (independent of contributing). */
    val downloadMap: Boolean = true,
    /** Ask after each finished session whether to contribute it. */
    val promptAfterSession: Boolean = true,
    /** Standing permission to upload finished sessions without asking. Off by default. */
    val autoUpload: Boolean = false,
    val zones: List<PrivacyZone> = emptyList(),
    /** The user has looked at privacy zones (set at least one, or chose to continue without). Required before a first upload. */
    val zonesConfirmed: Boolean = false,
    // optional categories (off by default; the required measurement fields are always sent)
    val shareRadioModel: Boolean = false,
    val shareAntennaCategory: Boolean = false,
    val shareAntennaInternalExternal: Boolean = false,
    val shareInstallation: Boolean = false,
    val shareTxPower: Boolean = false,
    /** Share squares carrying quality flags (they stay out of trusted community statistics until reviewed). */
    val includeFlagged: Boolean = true,
) {
    val endpoint: String get() = when (destination) { Destination.DEFAULT -> Community.DEFAULT_ENDPOINT; Destination.CUSTOM -> customEndpoint.trim(); Destination.OFF -> "" }
    /** Stable name of the destination for per-destination upload status. */
    val destinationKey: String get() = when (destination) { Destination.DEFAULT -> "default"; Destination.CUSTOM -> "custom:" + customEndpoint.trim(); Destination.OFF -> "off" }
    val destinationLabel: String get() = when (destination) {
        Destination.DEFAULT -> "MeshSurvey community sheet"; Destination.CUSTOM -> "custom sheet " + customEndpoint.trim().take(48); Destination.OFF -> "none"
    }
}

/** One shared row: a summary of receptions in one square, on one preset, heard from one node, on one day. */
data class ShareRow(
    val cellId: String, val lat: Double, val lon: Double, val day: String, val protocol: String, val preset: String,
    val heardFrom: String, val n: Int, val nDirect: Int, val snrMedian: Double?, val snrBest: Double?, val rssiMedian: Double?,
    val flags: List<String>,
)

/**
 * Builds the anonymous community summary. Privacy rules (enforced here, before anything leaves the device):
 *  - only receptions measured by this device's own radios (no simulated, imported or mock-location data);
 *  - positions snapped to squares of [CELL_M] (about 1/3 acre); each row carries the square's centre, never the real point;
 *  - time reduced to the local calendar day; no device id, no own radio's node id, no message contents;
 *  - nothing from inside the home privacy zone; receptions without a position or delivered late are left out.
 * Unusual values are kept and tagged in `flags` (machine-readable codes), not dropped.
 */
object Community {
    const val SCHEMA = 2
    const val CONTRIBUTION_TAG = "meshsurvey-session"
    /** The project's community sheet (Google Apps Script web app). Users can choose another compatible one or turn it off. */
    const val DEFAULT_ENDPOINT = "https://script.google.com/macros/s/AKfycbyMg2uGOFlK3JxYiMAaOucHSo5NmEjMK_IgjwuAlKoNLxVCgQTu54kBJ8i8lHB8C-A/exec"
    /** Rows per upload part. */
    const val PART_ROWS = 300
    /** √(1/3 acre) ≈ 36.7 m. */
    const val CELL_M = 36.7

    /** Codes used in the `flags` column (also documented in docs/COMMUNITY.md). */
    object Flag {
        const val GPS_POOR = "gps_poor"                         // position accuracy worse than 30 m
        const val GPS_ACCURACY_UNKNOWN = "gps_accuracy_unknown"
        const val GPS_STATIONARY_STALE = "gps_stationary_stale" // radio's own position, determined > 30 min earlier
        const val SNR_IMPLAUSIBLE = "snr_implausible"
        const val RSSI_IMPLAUSIBLE = "rssi_implausible"
        const val NEAR_NOISE = "near_noise"                     // median SNR below −5 dB: RSSI reads high
        const val SINGLE_RECEPTION = "single_reception"
        const val HEARD_FROM_AMBIGUOUS = "heard_from_ambiguous" // only a relay byte / key prefix is known
    }

    data class Excluded(val simulatedOrImported: Int = 0, val noPosition: Int = 0, val late: Int = 0, val homeZone: Int = 0, val mock: Int = 0) {
        val total get() = simulatedOrImported + noPosition + late + homeZone + mock
    }

    /** @param ownNodes this station's own radios: never named in `heard_from` (shown as "own_radio"). */
    fun build(obs: List<Observation>, settings: ShareSettings, dayOf: (Long) -> String, ownNodes: Set<Long> = emptySet()): Pair<List<ShareRow>, Excluded> {
        val ownIds = ownNodes.map { "mt:!" + it.toString(16).padStart(8, '0') }.toSet()
        val grid = Grid(CELL_M)
        var ex = Excluded()
        class Acc { val snr = ArrayList<Double>(); val rssi = ArrayList<Double>(); var n = 0; var direct = 0; val flags = LinkedHashSet<String>() }
        val groups = LinkedHashMap<List<String>, Pair<Long, Acc>>()
        for (o in obs) {
            // Only receptions measured by a real radio (here, or on the device that recorded an imported session); unknown provenance is not real.
            if (o.source != Source.LOCAL_RF) { ex = ex.copy(simulatedOrImported = ex.simulatedOrImported + 1); continue }
            val fs = o.fixSource?.let { runCatching { FixSource.valueOf(it) }.getOrNull() }
            if (fs == FixSource.SIMULATED) { ex = ex.copy(simulatedOrImported = ex.simulatedOrImported + 1); continue }
            if (fs == FixSource.MOCK) { ex = ex.copy(mock = ex.mock + 1); continue }
            if (o.status == RxStatus.QUEUED_LATE) { ex = ex.copy(late = ex.late + 1); continue }
            val lat = o.lat; val lon = o.lon
            if (lat == null || lon == null) { ex = ex.copy(noPosition = ex.noPosition + 1); continue }
            if (inHomeZone(lat, lon, settings)) { ex = ex.copy(homeZone = ex.homeZone + 1); continue }
            val key = grid.key(lat, lon)
            val heard = heardFrom(o).let { if (it in ownIds) "own_radio" else it }
            val k = listOf(key.toString(), dayOf(o.rxMs), o.protocol, o.preset ?: "unknown", heard)
            val a = groups.getOrPut(k) { key to Acc() }.second
            a.n++
            if (o.hopsAway == 0) a.direct++
            o.snr?.let { if (it > 30 || it < -40) a.flags += Flag.SNR_IMPLAUSIBLE else a.snr += it }
            o.rssi?.let { if (it > 0 || it < -150) a.flags += Flag.RSSI_IMPLAUSIBLE else a.rssi += it.toDouble() }
            val acc = o.accuracyM
            when {
                acc != null && acc > 30 -> a.flags += Flag.GPS_POOR
                acc == null && fs != FixSource.MANUAL && fs != FixSource.RADIO -> a.flags += Flag.GPS_ACCURACY_UNKNOWN
            }
            if (fs == FixSource.RADIO && (o.positionAgeMs ?: 0) > 30 * 60_000L) a.flags += Flag.GPS_STATIONARY_STALE
            if (heard.startsWith("mt:~") || (heard.startsWith("mc:") && heard.length <= 7)) a.flags += Flag.HEARD_FROM_AMBIGUOUS
        }
        val rows = groups.map { (k, v) ->
            val (key, a) = v
            val b = grid.bounds(key)
            val med = CellStats.median(a.snr)
            val flags = a.flags.toMutableList()
            if (med != null && med < -5) flags += Flag.NEAR_NOISE
            if (a.n == 1) flags += Flag.SINGLE_RECEPTION
            ShareRow(cellId(key), r5(b.centerLat), r5(b.centerLon), k[1], k[2], k[3], k[4], a.n, a.direct,
                med?.let(::r1), a.snr.maxOrNull()?.let(::r1), CellStats.median(a.rssi)?.let(::r1), flags.sorted())
        }
        return rows to ex
    }

    fun inHomeZone(lat: Double, lon: Double, s: ShareSettings): Boolean =
        s.zones.any { z -> Geo.distanceM(lat, lon, z.lat, z.lon) <= z.radiusM }

    /** "Heard from": the node heard directly ("mt:!a1b2c3d4", "mc:a3f1…"), or "unknown". The own radio is never in it. */
    private fun heardFrom(o: Observation): String = o.heardId?.let { if (it.startsWith("mc:")) it.take(11) else it } ?: "unknown"

    private fun cellId(key: Long) = "c" + (key shr 32).toInt() + "_" + key.toInt()
    private fun r5(v: Double) = round(v * 1e5) / 1e5
    private fun r1(v: Double) = round(v * 10) / 10

    /** Hex SHA-256. */
    fun sha(s: String) = meshsurvey.core.util.Sha256.hex(s.encodeToByteArray())

    /** Key under which a session is known to the community sheet: derived from its UUID, so the UUID itself is never sent. */
    fun sessionKey(sessionId: String) = sha("meshsurvey-session:$sessionId").take(24)

    /** Secondary consistency check (same session ⇒ same value) that does not reveal the start time. */
    fun startCheck(sessionId: String, startedMs: Long) = sha("meshsurvey-start:$sessionId:$startedMs").take(12)

    /**
     * One upload part of a session. Refuses anything not measured by a real radio: simulated and unknown-provenance sessions
     * can never be serialised for upload.
     */
    fun sessionPart(session: meshsurvey.core.model.SurveySession, rows: List<ShareRow>, part: Int, parts: Int, equipment: Map<String, String>,
                    submitter: String, appVersion: String, deleteHash: String): String {
        require(session.source == Source.LOCAL_RF) { "only sessions recorded by a real radio can be contributed (this one is ${session.source})" }
        val key = sessionKey(session.id)
        return buildJsonObject {
            put("schema", SCHEMA); put("type", "session_batch"); put("contribution_tag", CONTRIBUTION_TAG); put("provenance", "local_rf")
            put("submitter", submitter); put("app", appVersion); put("session_key", key); put("start_check", startCheck(session.id, session.startedMs))
            put("batch_id", "$key-${part + 1}of$parts"); put("part", part); put("parts", parts); put("delete_hash", deleteHash)
            put("equipment", buildJsonObject { equipment.forEach { (k, v) -> put(k, v) } })
            put("rows", buildJsonArray {
                rows.forEach { r -> add(buildJsonObject {
                    put("cell_id", r.cellId); put("lat", r.lat); put("lon", r.lon); put("day", r.day); put("protocol", r.protocol)
                    put("preset", r.preset); put("heard_from", r.heardFrom); put("n", r.n); put("n_direct", r.nDirect)
                    put("snr_median", r.snrMedian); put("snr_best", r.snrBest); put("rssi_median", r.rssiMedian); put("flags", r.flags.joinToString(";"))
                }) }
            })
        }.toString()
    }

    /** A user's "something looks wrong" report. Position (optional) is snapped to the same squares; home zone applies. */
    fun oddityPayload(text: String, lat: Double?, lon: Double?, day: String, submitter: String, appVersion: String, settings: ShareSettings): String {
        val g = Grid(CELL_M)
        val keep = lat != null && lon != null && !inHomeZone(lat, lon, settings)
        val b = if (keep) g.bounds(g.key(lat!!, lon!!)) else null
        return buildJsonObject {
            put("schema", SCHEMA); put("type", "oddity"); put("submitter", submitter); put("app", appVersion); put("provenance", "user_report")
            put("text", text.take(1000)); put("day", day)
            put("lat", b?.let { r5(it.centerLat) }); put("lon", b?.let { r5(it.centerLon) })
        }.toString()
    }

    /** A community square as returned by the script's aggregate (for the map layer). */
    /** [n] and [snrMedian] are trusted values (unflagged, or reviewed as ok); [pendingReview] counts flagged contributions not yet reviewed. */
    data class CommunityCell(val lat: Double, val lon: Double, val preset: String, val protocol: String, val snrMedian: Double?, val n: Int, val contributors: Int,
                             val pendingReview: Int)

    fun parseAggregate(json: String): List<CommunityCell> {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(json) as? JsonObject ?: return emptyList()
        val cells = root["cells"] as? JsonArray ?: return emptyList()
        return cells.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
            fun i(k: String) = (o[k] as? JsonPrimitive)?.intOrNull
            fun s(k: String) = (o[k] as? JsonPrimitive)?.content
            CommunityCell(d("lat") ?: return@mapNotNull null, d("lon") ?: return@mapNotNull null, s("preset") ?: "?", s("protocol") ?: "meshtastic",
                d("snr_median"), i("n") ?: 0, i("contributors") ?: 0, i("pending_review") ?: 0)
        }
    }
}
