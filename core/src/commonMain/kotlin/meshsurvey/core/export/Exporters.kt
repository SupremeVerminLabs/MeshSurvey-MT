package meshsurvey.core.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import meshsurvey.core.model.DeliveryMode
import meshsurvey.core.model.Participation
import meshsurvey.core.model.ImportRecord
import meshsurvey.core.model.LoggingWindow
import meshsurvey.core.model.Observation
import meshsurvey.core.model.PacketType
import meshsurvey.core.model.RxStatus
import meshsurvey.core.model.Source
import meshsurvey.core.model.SurveySession
import meshsurvey.core.model.SessionStatus
import meshsurvey.core.model.TestMode
import meshsurvey.core.model.TestRole
import meshsurvey.core.model.TestRx
import meshsurvey.core.model.TestSession
import meshsurvey.core.model.TestTx
import meshsurvey.core.model.TrackPoint
import meshsurvey.core.model.TxStatus
import meshsurvey.core.proto.BROADCAST
import meshsurvey.core.proto.MeshEnums
import meshsurvey.core.store.Store
import meshsurvey.core.util.Sha256

object Csv {
    private fun esc(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
    private fun row(vararg v: Any?) = v.joinToString(",") { esc(it) } + "\n"
    private fun node(n: Long?) = n?.let { if (it == BROADCAST) "^all" else MeshEnums.nodeId(it) }

    fun observations(list: List<Observation>): String = buildString {
        append(row("rx_ms", "session_id", "receiver", "lat", "lon", "gps_accuracy_m", "fix_age_ms", "rssi", "snr", "packet_id",
            "from", "to", "channel", "portnum", "type", "status", "hop_start", "hop_limit", "hops_away", "relay_byte",
            "payload_len", "preset", "region", "frequency_mhz", "frequency_source", "node_rx_time", "summary", "source", "origin_device",
            "fix_source", "position_age_ms", "confidence", "confidence_reasons", "protocol", "heard_id"))
        for (o in list) {
            val c = meshsurvey.core.quality.ConfidenceRules.classify(o)
            append(row(o.rxMs, o.sessionId, node(o.receiverNode), o.lat, o.lon, o.accuracyM, o.fixAgeMs, o.rssi, o.snr,
                o.packetId, node(o.fromNode), node(o.toNode), o.channel, o.portnum, o.type, o.status, o.hopStart, o.hopLimit, o.hopsAway,
                o.relayByte?.let { it.toString(16).padStart(2, '0') }, o.payloadLen, o.preset, o.region, o.frequencyMhz, o.frequencySource, o.nodeRxTime,
                o.summary, o.source, o.originDevice, o.fixSource, o.positionAgeMs, c.cls.name, c.reasons.joinToString("; "), o.protocol, o.heardId))
        }
    }

    fun tests(tx: List<TestTx>): String = buildString {
        append(row("test_session_id", "seq", "sent_ms", "packet_id", "from", "to", "lat", "lon", "gps_accuracy_m", "fix_age_ms",
            "want_ack", "delivery_mode", "hop_limit", "preset", "frequency_mhz", "frequency_source", "airtime_ms_per_tx",
            "status", "status_ms", "routing_error", "source", "origin_device"))
        for (t in tx) append(row(t.testSessionId, t.seq, t.sentMs, t.packetId, node(t.fromNode), node(t.toNode), t.lat, t.lon,
            t.accuracyM, t.fixAgeMs, t.wantAck, t.deliveryMode, t.hopLimit, t.preset, t.frequencyMhz, t.frequencySource,
            t.airtimeMs?.let { kotlin.math.round(it * 10) / 10 }, t.status, t.statusMs, t.routingError, t.source, t.originDevice))
    }
}

/** RFC 7946 GeoJSON: reception points, test transmissions and track lines. */
object GeoJson {
    fun export(obs: List<Observation>, track: List<TrackPoint>, tests: List<TestTx>): String {
        val fc = buildJsonObject {
            put("type", "FeatureCollection")
            put("features", buildJsonArray {
                for (o in obs) {
                    val lat = o.lat ?: continue; val lon = o.lon ?: continue
                    add(feature(point(lat, lon), buildJsonObject {
                        put("kind", "observation"); put("rx_ms", o.rxMs); put("rssi", o.rssi); put("snr", o.snr)
                        put("from", MeshEnums.nodeId(o.fromNode)); put("type", o.type.name); put("status", o.status.name)
                        put("hops_away", o.hopsAway); put("gps_accuracy_m", o.accuracyM); put("preset", o.preset)
                        put("frequency_mhz", o.frequencyMhz); put("frequency_source", o.frequencySource)
                        put("source", o.source.name); put("measured", o.source != Source.SIMULATED)
                    }))
                }
                for (t in tests) {
                    val lat = t.lat ?: continue; val lon = t.lon ?: continue
                    add(feature(point(lat, lon), buildJsonObject {
                        put("kind", "test_tx"); put("test_session_id", t.testSessionId); put("seq", t.seq)
                        put("sent_ms", t.sentMs); put("status", t.status.name); put("routing_error", t.routingError)
                        put("to", MeshEnums.nodeId(t.toNode)); put("gps_accuracy_m", t.accuracyM); put("source", t.source.name)
                        put("delivery_mode", t.deliveryMode?.name); put("preset", t.preset); put("frequency_mhz", t.frequencyMhz)
                    }))
                }
                track.groupBy { it.sessionId ?: "" }.forEach { (sid, pts) ->
                    if (pts.size < 2) return@forEach
                    add(feature(buildJsonObject {
                        put("type", "LineString")
                        put("coordinates", buildJsonArray { pts.forEach { add(buildJsonArray { add(JsonPrimitive(it.lon)); add(JsonPrimitive(it.lat)) }) } })
                    }, buildJsonObject { put("kind", "track"); put("session_id", sid); put("source", pts.first().source.name) }))
                }
            })
        }
        return fc.toString()
    }

    private fun point(lat: Double, lon: Double) = buildJsonObject {
        put("type", "Point"); put("coordinates", buildJsonArray { add(JsonPrimitive(lon)); add(JsonPrimitive(lat)) })
    }
    private fun feature(geom: JsonObject, props: JsonObject) = buildJsonObject {
        put("type", "Feature"); put("geometry", geom); put("properties", props)
    }
}

data class ImportResult(
    val duplicateFile: Boolean,
    val ownExport: Boolean,
    val originDevice: String?,
    val observations: Int = 0,
    val track: Int = 0,
    val testTx: Int = 0,
    val testRx: Int = 0,
    val windows: Int = 0,
    val skippedRows: Int = 0,
    val error: String? = null,
)

/**
 * Full JSON bundle: the interchange format between devices (e.g. Device B -> Device A)
 * and the backup format. Sources are preserved as recorded; origin_device marks imported rows.
 */
object Bundle {
    const val FORMAT = "meshsurvey.bundle"
    /** 2: equipment profiles, radio-reported session snapshot, position_age_ms. v1 bundles still import. */
    const val VERSION = 2

    fun export(store: Store, originDevice: String, appVersion: String, nowMs: Long): String {
        val obs = store.observations()
        val tr = store.track()
        val tx = store.testTx()
        val rx = store.testRx()
        val sessions = store.sessions()
        val tsessions = store.testSessions()
        val root = buildJsonObject {
            put("format", FORMAT); put("version", VERSION); put("app", appVersion)
            put("exported_ms", nowMs); put("origin_device", originDevice)
            put("contains_simulated", (obs.any { it.source == Source.SIMULATED } || tx.any { it.source == Source.SIMULATED }))
            put("notes", "Stock Meshtastic firmware suppresses duplicate receptions; each observation is the first copy delivered to the client.")
            put("sessions", JsonArray(sessions.filter { it.originDevice == null }.map { s -> buildJsonObject {
                put("id", s.id); put("started_ms", s.startedMs); put("ended_ms", s.endedMs); put("status", s.status.name)
                put("receiver_node", s.receiverNode); put("link_kind", s.linkKind); put("firmware", s.firmware)
                put("preset", s.preset); put("region", s.region); put("source", s.source.name)
                put("duplicates_suppressed", s.duplicatesSuppressed); put("notes", s.notes)
                put("profile_id", s.profileId); put("hw_model", s.hwModel); put("tx_power_dbm", s.txPowerDbm)
                put("frequency_mhz", s.frequencyMhz); put("frequency_source", s.frequencySource); put("protocol", s.protocol)
            } }))
            // user-entered equipment descriptions referenced by sessions (own profiles only, deleted ones included so references resolve)
            put("equipment_profiles", JsonArray(store.profiles(includeDeleted = true).filter { it.originDevice == null }.map { meshsurvey.core.model.EquipmentCodec.toJson(it) }))
            put("observations", JsonArray(obs.filter { it.originDevice == null }.map { o -> buildJsonObject {
                put("session_id", o.sessionId); put("receiver_node", o.receiverNode); put("rx_ms", o.rxMs); put("node_rx_time", o.nodeRxTime)
                put("lat", o.lat); put("lon", o.lon); put("accuracy_m", o.accuracyM); put("fix_age_ms", o.fixAgeMs)
                put("rssi", o.rssi); put("snr", o.snr); put("packet_id", o.packetId); put("from", o.fromNode); put("to", o.toNode)
                put("channel", o.channel); put("portnum", o.portnum); put("type", o.type.name); put("status", o.status.name)
                put("hop_start", o.hopStart); put("hop_limit", o.hopLimit); put("hops_away", o.hopsAway); put("relay_byte", o.relayByte)
                put("payload_len", o.payloadLen); put("preset", o.preset); put("region", o.region); put("frequency_mhz", o.frequencyMhz)
                put("summary", o.summary); put("source", o.source.name); put("frequency_source", o.frequencySource); put("fix_source", o.fixSource)
                put("position_age_ms", o.positionAgeMs); put("protocol", o.protocol); put("heard_id", o.heardId)
            } }))
            put("track", JsonArray(tr.filter { it.originDevice == null }.map { t -> buildJsonObject {
                put("session_id", t.sessionId); put("ms", t.ms); put("lat", t.lat); put("lon", t.lon); put("accuracy_m", t.accuracyM)
                put("radio_ready", t.radioReady); put("source", t.source.name)
            } }))
            put("clock_events", JsonArray(store.clockEvents().map { e -> buildJsonObject {
                put("wall_ms", e.wallMs); put("kind", e.kind.name); put("magnitude_ms", e.magnitudeMs); put("session_id", e.sessionId)
            } }))
            put("logging_windows", JsonArray(store.windows(null).map { w -> buildJsonObject { put("start_ms", w.startMs); put("end_ms", w.endMs); put("receiver_node", w.receiverNode) } }))
            put("test_sessions", JsonArray(tsessions.filter { it.originDevice == null }.map { s -> buildJsonObject {
                put("id", s.id); put("role", s.role.name); put("mode", s.mode.name); put("peer_node", s.peerNode); put("local_node", s.localNode)
                put("started_ms", s.startedMs); put("ended_ms", s.endedMs); put("interval_s", s.intervalS); put("duration_s", s.durationS)
                put("end_reason", s.endReason); put("source", s.source.name); put("preset", s.preset); put("delivery_mode", s.deliveryMode?.name)
                put("participation", s.participation?.name); put("ref_code", s.refCode); put("planned_end_ms", s.plannedEndMs)
                put("air_util_tx_start", s.airUtilTxStart); put("ch_util_start", s.chUtilStart)
                put("air_util_tx_end", s.airUtilTxEnd); put("ch_util_end", s.chUtilEnd)
            } }))
            put("test_tx", JsonArray(tx.filter { it.originDevice == null }.map { t -> buildJsonObject {
                put("test_session_id", t.testSessionId); put("seq", t.seq); put("sent_ms", t.sentMs); put("packet_id", t.packetId)
                put("to", t.toNode); put("from", t.fromNode); put("lat", t.lat); put("lon", t.lon); put("accuracy_m", t.accuracyM)
                put("fix_age_ms", t.fixAgeMs); put("want_ack", t.wantAck); put("status", t.status.name); put("status_ms", t.statusMs)
                put("routing_error", t.routingError); put("source", t.source.name)
                put("delivery_mode", t.deliveryMode?.name); put("hop_limit", t.hopLimit); put("preset", t.preset)
                put("frequency_mhz", t.frequencyMhz); put("frequency_source", t.frequencySource); put("airtime_ms", t.airtimeMs)
            } }))
            put("test_rx", JsonArray(rx.filter { it.originDevice == null }.map { r -> buildJsonObject {
                put("test_session_id", r.testSessionId); put("seq", r.seq); put("from", r.fromNode); put("to", r.toNode)
                put("packet_id", r.packetId); put("rx_ms", r.rxMs); put("sender_time_s", r.senderTimeS); put("rssi", r.rssi)
                put("snr", r.snr); put("hop_start", r.hopStart); put("hop_limit", r.hopLimit); put("relay_byte", r.relayByte)
                put("lat", r.lat); put("lon", r.lon); put("accuracy_m", r.accuracyM); put("source", r.source.name)
                put("preset", r.preset); put("frequency_mhz", r.frequencyMhz); put("frequency_source", r.frequencySource)
                put("ref_session_id", r.refSessionId)
            } }))
        }
        return root.toString()
    }

    fun import(text: String, store: Store, ownDevice: String, nowMs: Long): ImportResult {
        val sha = Sha256.hex(text.encodeToByteArray())
        store.findImport(sha)?.let { return ImportResult(duplicateFile = true, ownExport = false, originDevice = it.originDevice) }
        val root: JsonObject = try { Json.parseToJsonElement(text).jsonObject } catch (e: Exception) {
            return ImportResult(false, false, null, error = "Not valid JSON: ${e.message}")
        }
        if (root.s("format") != FORMAT) return ImportResult(false, false, null, error = "Not a MeshSurvey bundle")
        val ver = root.i("version") ?: 0
        if (ver > VERSION) return ImportResult(false, false, null, error = "Bundle version $ver is newer than this app supports")
        val origin = root.s("origin_device") ?: return ImportResult(false, false, null, error = "Bundle has no origin_device")
        if (origin == ownDevice) return ImportResult(false, true, origin, error = "This is this device's own export")

        var nObs = 0; var nTr = 0; var nTx = 0; var nRx = 0; var nW = 0; var skipped = 0
        store.transaction {
            root.arr("observations").forEach { e -> val o = e.jsonObject
                try {
                    val id = store.insertObservation(Observation(
                        sessionId = o.s("session_id"), receiverNode = o.l("receiver_node"), rxMs = o.l("rx_ms")!!, nodeRxTime = o.l("node_rx_time"),
                        lat = o.d("lat"), lon = o.d("lon"), accuracyM = o.d("accuracy_m"), fixAgeMs = o.l("fix_age_ms"),
                        rssi = o.i("rssi"), snr = o.d("snr"), packetId = o.l("packet_id")!!, fromNode = o.l("from")!!, toNode = o.l("to")!!,
                        channel = o.i("channel"), portnum = o.i("portnum"), type = PacketType.valueOf(o.s("type")!!),
                        status = RxStatus.valueOf(o.s("status")!!), hopStart = o.i("hop_start"), hopLimit = o.i("hop_limit"),
                        hopsAway = o.i("hops_away"), relayByte = o.i("relay_byte"), payloadLen = o.i("payload_len"),
                        preset = o.s("preset"), region = o.s("region"), frequencyMhz = o.d("frequency_mhz"), summary = o.s("summary"),
                        source = src(o), originDevice = origin, frequencySource = o.s("frequency_source"), fixSource = o.s("fix_source"),
                        positionAgeMs = o.l("position_age_ms"), protocol = o.s("protocol") ?: "meshtastic", heardId = o.s("heard_id")))
                    if (id > 0) nObs++ else skipped++
                } catch (_: Exception) { skipped++ }
            }
            root.arr("sessions").forEach { e -> val s = e.jsonObject
                try {
                    store.insertSession(SurveySession(id = s.s("id")!!, startedMs = s.l("started_ms")!!, endedMs = s.l("ended_ms"),
                        status = s.s("status")?.let { v -> runCatching { SessionStatus.valueOf(v) }.getOrNull() } ?: SessionStatus.ENDED,
                        receiverNode = s.l("receiver_node"), linkKind = s.s("link_kind"), firmware = s.s("firmware"),
                        preset = s.s("preset"), region = s.s("region"), source = src(s), duplicatesSuppressed = s.b("duplicates_suppressed") ?: true,
                        notes = s.s("notes"), profileId = s.s("profile_id"), hwModel = s.i("hw_model"), txPowerDbm = s.i("tx_power_dbm"),
                        frequencyMhz = s.d("frequency_mhz"), frequencySource = s.s("frequency_source"), originDevice = origin,
                        protocol = s.s("protocol") ?: "meshtastic"))
                } catch (_: Exception) { skipped++ }
            }
            root.arr("equipment_profiles").forEach { e ->
                (e as? JsonObject)?.let { meshsurvey.core.model.EquipmentCodec.fromJson(it, origin) }?.let { store.upsertProfile(it) } ?: skipped++
            }
            root.arr("track").forEach { e -> val t = e.jsonObject
                try {
                    val id = store.insertTrack(TrackPoint(sessionId = t.s("session_id"), ms = t.l("ms")!!, lat = t.d("lat")!!, lon = t.d("lon")!!,
                        accuracyM = t.d("accuracy_m"), radioReady = t.b("radio_ready") ?: false, source = src(t), originDevice = origin))
                    if (id > 0) nTr++ else skipped++
                } catch (_: Exception) { skipped++ }
            }
            root.arr("logging_windows").forEach { e -> val w = e.jsonObject
                val s = w.l("start_ms"); val en = w.l("end_ms")
                if (s != null && en != null && en >= s) { store.insertWindow(LoggingWindow(s, en, origin, w.l("receiver_node"))); nW++ } else skipped++
            }
            root.arr("test_sessions").forEach { e -> val s = e.jsonObject
                try {
                    store.insertTestSession(TestSession(s.s("id")!!, TestRole.valueOf(s.s("role")!!), TestMode.valueOf(s.s("mode")!!),
                        s.l("peer_node")!!, s.l("local_node"), s.l("started_ms")!!, s.l("ended_ms"), s.i("interval_s"), s.i("duration_s"),
                        s.s("end_reason"), src(s), origin, s.s("preset"), s.s("delivery_mode")?.let { m -> runCatching { DeliveryMode.valueOf(m) }.getOrNull() },
                        s.s("participation")?.let { m -> runCatching { Participation.valueOf(m) }.getOrNull() }, s.i("ref_code"),
                        s.d("air_util_tx_start"), s.d("ch_util_start"), s.d("air_util_tx_end"), s.d("ch_util_end"), s.l("planned_end_ms")))
                } catch (_: Exception) { skipped++ }
            }
            root.arr("test_tx").forEach { e -> val t = e.jsonObject
                try {
                    val id = store.insertTestTx(TestTx(testSessionId = t.s("test_session_id")!!, seq = t.i("seq")!!, sentMs = t.l("sent_ms")!!,
                        packetId = t.l("packet_id"), toNode = t.l("to")!!, fromNode = t.l("from"), lat = t.d("lat"), lon = t.d("lon"),
                        accuracyM = t.d("accuracy_m"), fixAgeMs = t.l("fix_age_ms"), wantAck = t.b("want_ack") ?: false,
                        status = txStatus(t.s("status")!!, t.s("routing_error")), statusMs = t.l("status_ms"), routingError = t.s("routing_error"),
                        source = src(t), originDevice = origin, deliveryMode = t.s("delivery_mode")?.let { m -> runCatching { DeliveryMode.valueOf(m) }.getOrNull() },
                        hopLimit = t.i("hop_limit"), preset = t.s("preset"), frequencyMhz = t.d("frequency_mhz"),
                        frequencySource = t.s("frequency_source"), airtimeMs = t.d("airtime_ms")))
                    if (id > 0) nTx++ else skipped++
                } catch (_: Exception) { skipped++ }
            }
            root.arr("test_rx").forEach { e -> val r = e.jsonObject
                try {
                    val ok = store.insertTestRx(TestRx(testSessionId = r.s("test_session_id")!!, seq = r.i("seq")!!, fromNode = r.l("from")!!,
                        toNode = r.l("to"), packetId = r.l("packet_id")!!, rxMs = r.l("rx_ms")!!, senderTimeS = r.l("sender_time_s"),
                        rssi = r.i("rssi"), snr = r.d("snr"), hopStart = r.i("hop_start"), hopLimit = r.i("hop_limit"), relayByte = r.i("relay_byte"),
                        lat = r.d("lat"), lon = r.d("lon"), accuracyM = r.d("accuracy_m"), source = src(r), originDevice = origin,
                        preset = r.s("preset"), frequencyMhz = r.d("frequency_mhz"), frequencySource = r.s("frequency_source"),
                        refSessionId = r.s("ref_session_id")))
                    if (ok) nRx++ else skipped++
                } catch (_: Exception) { skipped++ }
            }
            store.insertImport(ImportRecord(sha, nowMs, origin, "bundle", nObs + nTr + nTx + nRx + nW))
        }
        return ImportResult(false, false, origin, nObs, nTr, nTx, nRx, nW, skipped)
    }

    /** Accepts v1 status names from older bundles. */
    private fun txStatus(s: String, err: String?): TxStatus = when (s) {
        "IMPLICIT_ACK" -> TxStatus.RELAY_OBSERVED
        "NOT_CONFIRMED" -> TxStatus.UNKNOWN
        "NOT_SENT" -> TxStatus.NOT_TRANSMITTED
        "FAILED" -> if (err in setOf("NO_INTERFACE", "TOO_LARGE", "DUTY_CYCLE_LIMIT", "BAD_REQUEST", "RATE_LIMIT_EXCEEDED")) TxStatus.NOT_TRANSMITTED else TxStatus.NO_ACK
        else -> TxStatus.valueOf(s)
    }

    private fun src(o: JsonObject): Source = o.s("source")?.let { runCatching { Source.valueOf(it) }.getOrNull() } ?: Source.IMPORTED
    private fun JsonObject.p(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }
    private fun JsonObject.s(k: String) = p(k)?.takeIf { it.isString }?.content
    private fun JsonObject.l(k: String) = p(k)?.longOrNull
    private fun JsonObject.i(k: String) = p(k)?.intOrNull
    private fun JsonObject.d(k: String) = p(k)?.doubleOrNull
    private fun JsonObject.b(k: String) = p(k)?.booleanOrNull
    private fun JsonObject.arr(k: String): List<JsonElement> = (this[k] as? JsonArray) ?: emptyList()

}

