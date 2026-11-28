package meshsurvey.core.community

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import meshsurvey.core.model.SessionStatus
import meshsurvey.core.model.Source
import meshsurvey.core.model.SurveySession
import meshsurvey.core.store.ObsFilter
import meshsurvey.core.store.SqlStore
import meshsurvey.core.util.newId

/** Upload state of one session for one destination (persisted). "Not uploaded" = no record. */
enum class ContributionStatus(val label: String) {
    PENDING("Pending"), UPLOADING("Uploading"), PARTIAL("Partially uploaded"), UPLOADED("Uploaded"), FAILED("Failed"),
    EXCLUDED("Excluded"), WITHDRAWN("Withdrawn")
}

data class Contribution(
    val sessionId: String,
    val destination: String,
    val status: ContributionStatus,
    val sessionKey: String,
    /** The upload parts exactly as first prepared (JSON array of strings): retries resend the same parts, never a rebuilt set. */
    val partsJson: String?,
    val acked: Set<Int>,
    val acceptedCells: Int,
    val cells: Int,
    /** Receipt from the sheet (JSON): accepted batch ids and counts. */
    val receipt: String?,
    /** Private withdrawal token (only its SHA-256 was sent). Never shown on any public map response. */
    val deleteToken: String?,
    val uploadedMs: Long?,
    val error: String?,
    val nextRetryMs: Long?,
    val updatedMs: Long,
) {
    val parts: List<String> get() = partsJson?.let { j -> runCatching { Json.parseToJsonElement(j).jsonArray.map { (it as JsonPrimitive).content } }.getOrNull() } ?: emptyList()
}

/** Shown before contributing (end-of-session prompt and Preview). */
data class SessionSummary(
    val session: SurveySession,
    val durationMs: Long,
    val observations: Int,
    val rows: List<ShareRow>,
    val excluded: Community.Excluded,
    val flaggedRows: Int,
    val flaggedLeftOut: Int,
    val equipment: Map<String, String>,
    val destination: String,
    /** Reason the session cannot be contributed, or null. */
    val ineligible: String?,
) {
    val cells get() = rows.map { it.cellId }.distinct().size
}

/**
 * Community contributions, one survey session at a time. Rules:
 *  - nothing is sent without an explicit user action, or the separate auto-upload permission (off by default);
 *  - simulated and unknown-provenance sessions are never eligible; privacy zones are applied before the payload is built;
 *  - parts are frozen on the first attempt and resent unchanged, the sheet answers "already accepted" for parts it has, and a
 *    session is "Uploaded" only when every part is acknowledged; status is kept per destination;
 *  - failures wait (backoff from 30 min up to 24 h) — there is no tight retry loop;
 *  - local data is never changed or deleted by sharing, declining or withdrawing.
 * Network access is injected so this runs on every platform and in tests.
 */
class Contributions(private val store: SqlStore, private val appVersion: String) {
    companion object {
        const val KEY_SETTINGS = "community_settings_v2"
        const val KEY_SETTINGS_V1 = "community_settings"
        const val KEY_SUBMITTER = "community_submitter"
        const val FIRST_RETRY_MS = 30 * 60_000L
        const val MAX_RETRY_MS = 24 * 3_600_000L
    }

    var settings: ShareSettings = load(); private set
    fun update(s: ShareSettings) { settings = s; store.setMeta(KEY_SETTINGS, encode(s)) }

    /** Random token per install (not the device id): lets the sheet rate-limit and count distinct contributors. */
    val submitter: String get() = store.getMeta(KEY_SUBMITTER) ?: newId(bytes = 8).also { store.setMeta(KEY_SUBMITTER, it) }

    fun status(sessionId: String): Contribution? = store.contribution(sessionId, settings.destinationKey)
    fun statuses(): Map<String, Contribution> = store.contributions(settings.destinationKey)

    /** Optional equipment categories the user chose to share (general categories only: no names, makes, callsigns or notes). */
    fun equipmentFor(session: SurveySession): Map<String, String> {
        val s = settings
        val p = session.profileId?.let { id -> store.profiles(includeDeleted = true).firstOrNull { it.id == id } }
        val m = LinkedHashMap<String, String>()
        if (s.shareRadioModel) session.hwModel?.let { m["radio_model"] = meshsurvey.core.proto.MeshEnums.hwModelName(it) }
        if (s.shareAntennaCategory) p?.antennaGainDbi?.let { g -> m["antenna_category"] = when { g < 3 -> "gain_below_3dbi"; g < 6 -> "gain_3_to_6dbi"; else -> "gain_6dbi_plus" } }
        if (s.shareAntennaInternalExternal) p?.antennaType?.let { m["antenna"] = it.name.lowercase() }
        if (s.shareInstallation) listOfNotNull(p?.placement?.name?.lowercase(), p?.mounting?.name?.lowercase()).takeIf { it.isNotEmpty() }?.let { m["installation"] = it.joinToString("_") }
        if (s.shareTxPower) session.txPowerDbm?.let { m["tx_power_dbm"] = if (it == 0) "firmware_default" else it.toString() }
        return m
    }

    /** What would be shared from [sessionId], after privacy filtering. */
    fun summary(sessionId: String, dayOf: (Long) -> String, ownNodes: Set<Long>, nowMs: Long): SessionSummary? {
        val session = store.sessions().firstOrNull { it.id == sessionId } ?: return null
        val obs = store.observations(ObsFilter(sessionId = sessionId))
        val (all, ex) = Community.build(obs, settings, dayOf, ownNodes)
        val flagged = all.filter { r -> r.flags.any { it != Community.Flag.SINGLE_RECEPTION && it != Community.Flag.HEARD_FROM_AMBIGUOUS } }
        val rows = if (settings.includeFlagged) all else all - flagged.toSet()
        val inelig = when {
            session.source != Source.LOCAL_RF -> "simulated or unknown-provenance data is never shared"
            session.status == SessionStatus.ACTIVE && session.originDevice == null -> "still recording"
            settings.destination == Destination.OFF -> "community sharing is turned off"
            rows.isEmpty() -> "nothing eligible (no positions, or everything inside a privacy zone)"
            else -> null
        }
        return SessionSummary(session, (session.endedMs ?: nowMs) - session.startedMs, obs.size, rows, ex,
            if (settings.includeFlagged) flagged.size else 0, if (settings.includeFlagged) 0 else flagged.size, equipmentFor(session),
            settings.destinationLabel, inelig)
    }

    /**
     * Uploads (or resumes) one session. [post] performs an HTTP POST with a JSON body and returns the response text, or throws.
     * Returns a message for the user. Safe to call again at any time: acknowledged parts are not resent.
     */
    fun upload(sessionId: String, dayOf: (Long) -> String, ownNodes: Set<Long>, nowMs: Long, post: (String, String) -> String): String {
        val s = settings
        val dest = s.destinationKey
        if (s.destination == Destination.OFF || s.endpoint.isBlank()) return "Community sharing is turned off"
        if (!s.zonesConfirmed) return "Set up privacy zones first (Settings → Community)"
        var c = store.contribution(sessionId, dest)
        if (c?.status == ContributionStatus.UPLOADED) return "Already uploaded"
        if (c?.status == ContributionStatus.WITHDRAWN) return "This session was withdrawn; it is not uploaded again"
        if (c == null || c.partsJson == null) {
            val sum = summary(sessionId, dayOf, ownNodes, nowMs) ?: return "Session not found"
            if (sum.ineligible != null) {
                if (sum.session.source != Source.LOCAL_RF || sum.rows.isEmpty())
                    store.saveContribution(Contribution(sessionId, dest, ContributionStatus.EXCLUDED, Community.sessionKey(sessionId), null, emptySet(), 0, 0,
                        null, null, null, sum.ineligible, null, nowMs))
                return "Not uploaded: ${sum.ineligible}"
            }
            val token = newId(bytes = 16)
            val chunks = sum.rows.chunked(Community.PART_ROWS)
            val parts = chunks.mapIndexed { i, rows -> Community.sessionPart(sum.session, rows, i, chunks.size, sum.equipment, submitter, appVersion, Community.sha(token)) }
            c = Contribution(sessionId, dest, ContributionStatus.PENDING, Community.sessionKey(sessionId),
                buildJsonArray { parts.forEach { add(JsonPrimitive(it)) } }.toString(), emptySet(), 0, sum.rows.size, null, token, null, null, null, nowMs)
            store.saveContribution(c)
        }
        val parts = c.parts
        var acked = c.acked; var accepted = c.acceptedCells
        val receipts = c.receipt?.let { runCatching { Json.parseToJsonElement(it).jsonArray.toMutableList() }.getOrNull() } ?: mutableListOf()
        store.saveContribution(c.copy(status = ContributionStatus.UPLOADING, updatedMs = nowMs))
        for ((i, body) in parts.withIndex()) {
            if (i in acked) continue
            val reply = try { post(s.endpoint, body) } catch (e: Exception) {
                return fail(c, acked, accepted, receipts, nowMs, "network: ${e.message ?: e::class.simpleName}")
            }
            val r = parseReply(reply)
            if (!r.ok) return fail(c, acked, accepted, receipts, nowMs, r.message ?: "refused", retry = r.retry)
            acked = acked + i; accepted += r.accepted
            r.receipt?.let { receipts += it }
            store.saveContribution(c.copy(status = ContributionStatus.UPLOADING, acked = acked, acceptedCells = accepted,
                receipt = JsonArray(receipts).toString(), updatedMs = nowMs))
        }
        store.saveContribution(c.copy(status = ContributionStatus.UPLOADED, acked = acked, acceptedCells = accepted, receipt = JsonArray(receipts).toString(),
            uploadedMs = nowMs, error = null, nextRetryMs = null, updatedMs = nowMs))
        return "Uploaded: $accepted of ${c.cells} squares accepted in ${parts.size} part(s)"
    }

    private fun fail(c: Contribution, acked: Set<Int>, accepted: Int, receipts: List<kotlinx.serialization.json.JsonElement>, nowMs: Long, why: String, retry: Boolean = true): String {
        val prevWait = c.nextRetryMs?.let { (it - c.updatedMs).coerceAtLeast(FIRST_RETRY_MS) } ?: (FIRST_RETRY_MS / 2)
        val wait = (prevWait * 2).coerceIn(FIRST_RETRY_MS, MAX_RETRY_MS)
        store.saveContribution(c.copy(status = if (acked.isEmpty()) ContributionStatus.FAILED else ContributionStatus.PARTIAL, acked = acked, acceptedCells = accepted,
            receipt = JsonArray(receipts).toString(), error = why, nextRetryMs = if (retry) nowMs + wait else null, updatedMs = nowMs))
        return (if (acked.isEmpty()) "Upload failed" else "Partly uploaded (${acked.size} of ${c.parts.size} parts)") + ": $why" +
            if (retry) " — will resume without duplicating" else ""
    }

    /**
     * Sessions the auto-upload permission may send now: finished, never decided, or failed with a retry time that has passed.
     * Empty unless the user turned automatic upload on.
     */
    fun autoCandidates(nowMs: Long): List<String> {
        val s = settings
        if (!s.autoUpload || s.destination == Destination.OFF || !s.zonesConfirmed) return emptyList()
        val st = statuses()
        return store.sessions().filter { it.status != SessionStatus.ACTIVE && it.source == Source.LOCAL_RF }.map { it.id }.filter { id ->
            val c = st[id]
            c == null || ((c.status == ContributionStatus.FAILED || c.status == ContributionStatus.PARTIAL || c.status == ContributionStatus.UPLOADING ||
                c.status == ContributionStatus.PENDING) && (c.nextRetryMs ?: 0) <= nowMs)
        }
    }

    /** Asks the sheet to delete this session's contribution, using the private token. Local data is unchanged. */
    fun withdraw(sessionId: String, nowMs: Long, post: (String, String) -> String): String {
        val c = store.contribution(sessionId, settings.destinationKey) ?: return "Nothing was uploaded"
        val token = c.deleteToken ?: return "No withdrawal token for this upload"
        val body = buildJsonObject {
            put("schema", Community.SCHEMA); put("type", "withdraw"); put("session_key", c.sessionKey); put("token", token); put("submitter", submitter)
        }.toString()
        val r = try { parseReply(post(settings.endpoint, body)) } catch (e: Exception) { return "Withdrawal failed: ${e.message}" }
        if (!r.ok) return "Withdrawal refused: ${r.message}"
        store.saveContribution(c.copy(status = ContributionStatus.WITHDRAWN, updatedMs = nowMs, error = null, nextRetryMs = null))
        return "Withdrawn: ${r.message ?: "removed from the community sheet"}"
    }

    /** Marks a session as "keep local only" for this destination (the session itself is untouched). */
    fun keepLocal(sessionId: String, nowMs: Long) {
        if (store.contribution(sessionId, settings.destinationKey) != null) return
        store.saveContribution(Contribution(sessionId, settings.destinationKey, ContributionStatus.EXCLUDED, Community.sessionKey(sessionId), null, emptySet(),
            0, 0, null, null, null, "kept local by the user", null, nowMs))
    }

    /** Clears an "excluded" choice so the session counts as not uploaded again (it can always be uploaded by hand). */
    fun reconsider(sessionId: String) {
        val c = store.contribution(sessionId, settings.destinationKey) ?: return
        if (c.status == ContributionStatus.EXCLUDED) store.deleteContribution(sessionId, settings.destinationKey)
    }

    fun reportOddity(text: String, lat: Double?, lon: Double?, day: String, post: (String, String) -> String): String {
        val s = settings
        if (s.destination == Destination.OFF) return "Community sharing is turned off"
        return try {
            val r = parseReply(post(s.endpoint, Community.oddityPayload(text, lat, lon, day, submitter, appVersion, s)))
            if (r.ok) "Report sent" else "Report refused: ${r.message}"
        } catch (e: Exception) { "Report failed: ${e.message}" }
    }

    // ---------------- community map (download, cached) ----------------

    data class MapData(val cells: List<Community.CommunityCell>, val fetchedMs: Long?, val message: String?)

    /** Cached squares for an area (no network). */
    fun cachedMap(area: String): MapData {
        val c = store.communityCache(area) ?: return MapData(emptyList(), null, null)
        return MapData(Community.parseAggregate(c.first), c.third, null)
    }

    /**
     * Refreshes an area unless the cache is younger than [maxAgeMs] (force = manual refresh). The sheet answers "unchanged" when its
     * aggregate has not been rebuilt since the cached version, so the whole area is not downloaded again.
     */
    fun refreshMap(area: String, url: String, nowMs: Long, force: Boolean, maxAgeMs: Long, get: (String) -> String): MapData {
        val cached = store.communityCache(area)
        if (!force && cached != null && nowMs - cached.third < maxAgeMs) return cachedMap(area)
        val full = url + (cached?.second?.let { "&since=" + it } ?: "")
        val text = try { get(full) } catch (e: Exception) { return cachedMap(area).copy(message = "Community map not refreshed: ${e.message}") }
        val o = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: return cachedMap(area).copy(message = "Community map: unexpected reply")
        if ((o["ok"] as? JsonPrimitive)?.booleanOrNull != true) return cachedMap(area).copy(message = "Community map: ${(o["message"] as? JsonPrimitive)?.content}")
        val version = (o["version"] as? JsonPrimitive)?.content
        if ((o["unchanged"] as? JsonPrimitive)?.booleanOrNull == true && cached != null) {
            store.saveCommunityCache(area, cached.first, cached.second, nowMs); return cachedMap(area)
        }
        store.saveCommunityCache(area, text, version, nowMs)
        return cachedMap(area)
    }

    // ---------------- persistence ----------------

    private data class Reply(val ok: Boolean, val message: String?, val accepted: Int, val retry: Boolean, val receipt: JsonObject?)

    private fun parseReply(text: String): Reply = try {
        val o = Json.parseToJsonElement(text) as JsonObject
        val rc = o["receipt"] as? JsonObject
        Reply((o["ok"] as? JsonPrimitive)?.booleanOrNull == true, (o["message"] as? JsonPrimitive)?.content,
            (rc?.get("accepted") as? JsonPrimitive)?.intOrNull ?: 0, (o["retry"] as? JsonPrimitive)?.booleanOrNull ?: false, rc)
    } catch (_: Exception) { Reply(false, "unexpected reply (is the address a MeshSurvey community web app?)", 0, true, null) }

    private fun load(): ShareSettings {
        val o = store.getMeta(KEY_SETTINGS)?.let { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() }
        if (o == null) return migrateV1()
        fun b(k: String, d: Boolean) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: d
        val zones = (o["zones"] as? JsonArray)?.mapNotNull { e ->
            val z = e as? JsonObject ?: return@mapNotNull null
            fun d(k: String) = (z[k] as? JsonPrimitive)?.doubleOrNull
            PrivacyZone(d("lat") ?: return@mapNotNull null, d("lon") ?: return@mapNotNull null, d("radius_m") ?: 250.0, (z["label"] as? JsonPrimitive)?.content ?: "Zone")
        } ?: emptyList()
        return ShareSettings(
            destination = (o["destination"] as? JsonPrimitive)?.content?.let { v -> Destination.entries.firstOrNull { it.name == v } } ?: Destination.DEFAULT,
            customEndpoint = (o["custom_endpoint"] as? JsonPrimitive)?.content ?: "",
            downloadMap = b("download_map", true), promptAfterSession = b("prompt", true), autoUpload = b("auto", false), zones = zones,
            zonesConfirmed = b("zones_confirmed", false), shareRadioModel = b("radio_model", false), shareAntennaCategory = b("antenna_category", false),
            shareAntennaInternalExternal = b("antenna_int_ext", false), shareInstallation = b("installation", false), shareTxPower = b("tx_power", false),
            includeFlagged = b("include_flagged", true))
    }

    /** First version kept one home point and a single URL; carried over without granting any upload permission. */
    private fun migrateV1(): ShareSettings {
        val o = store.getMeta(KEY_SETTINGS_V1)?.let { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() } ?: return ShareSettings()
        fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
        val url = (o["endpoint"] as? JsonPrimitive)?.content.orEmpty()
        val zone = d("home_lat")?.let { la -> d("home_lon")?.let { lo -> PrivacyZone(la, lo, d("home_radius_m") ?: 250.0, "Home") } }
        return ShareSettings(destination = if (url.isBlank() || url == Community.DEFAULT_ENDPOINT) Destination.DEFAULT else Destination.CUSTOM,
            customEndpoint = if (url == Community.DEFAULT_ENDPOINT) "" else url, zones = listOfNotNull(zone), zonesConfirmed = zone != null)
    }

    private fun encode(s: ShareSettings) = buildJsonObject {
        put("destination", s.destination.name); put("custom_endpoint", s.customEndpoint); put("download_map", s.downloadMap); put("prompt", s.promptAfterSession)
        put("auto", s.autoUpload); put("zones_confirmed", s.zonesConfirmed); put("radio_model", s.shareRadioModel); put("antenna_category", s.shareAntennaCategory)
        put("antenna_int_ext", s.shareAntennaInternalExternal); put("installation", s.shareInstallation); put("tx_power", s.shareTxPower)
        put("include_flagged", s.includeFlagged)
        put("zones", buildJsonArray { s.zones.forEach { z -> add(buildJsonObject { put("lat", z.lat); put("lon", z.lon); put("radius_m", z.radiusM); put("label", z.label) }) } })
    }.toString()
}
