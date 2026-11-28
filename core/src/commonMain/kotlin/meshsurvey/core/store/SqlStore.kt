package meshsurvey.core.store

import meshsurvey.core.model.ClockEvent
import meshsurvey.core.model.ClockEventKind
import meshsurvey.core.model.DeliveryMode
import meshsurvey.core.model.Participation
import meshsurvey.core.model.ImportRecord
import meshsurvey.core.model.PresetChange
import meshsurvey.core.model.PresetChangeStatus
import meshsurvey.core.util.hexToBytes
import meshsurvey.core.util.toHex
import meshsurvey.core.model.LoggingWindow
import meshsurvey.core.model.Observation
import meshsurvey.core.model.PacketType
import meshsurvey.core.model.RxStatus
import meshsurvey.core.model.SessionStatus
import meshsurvey.core.model.Source
import meshsurvey.core.model.SurveySession
import meshsurvey.core.model.TestDevice
import meshsurvey.core.model.TestMode
import meshsurvey.core.model.TestRole
import meshsurvey.core.model.TestRx
import meshsurvey.core.model.TestSession
import meshsurvey.core.model.TestTx
import meshsurvey.core.model.TrackPoint
import meshsurvey.core.model.TxStatus

/** Thin SQLite abstraction implemented per platform (Android SQLiteDatabase, desktop sqlite-jdbc). */
interface SqlDriver {
    /** Executes a statement; returns number of changed rows. */
    fun execute(sql: String, args: List<Any?> = emptyList()): Int
    /** Executes an INSERT; returns the new rowid (or -1 if ignored). */
    fun insert(sql: String, args: List<Any?> = emptyList()): Long
    fun <T> query(sql: String, args: List<Any?> = emptyList(), map: (SqlRow) -> T): List<T>
    fun <T> transaction(block: () -> T): T
    fun close()
}

interface SqlRow {
    fun long(i: Int): Long?
    fun double(i: Int): Double?
    fun string(i: Int): String?
    fun int(i: Int): Int? = long(i)?.toInt()
    fun blob(i: Int): ByteArray? = null
}

/** Versioned schema. Append new migrations; never edit a released one. */
object Schema {
    val migrations: List<List<String>> = listOf(
        // v1 — Lite edition
        listOf(
            """CREATE TABLE kv(key TEXT PRIMARY KEY, value TEXT)""",
            """CREATE TABLE survey_session(
                id TEXT PRIMARY KEY, started_ms INTEGER NOT NULL, ended_ms INTEGER, status TEXT NOT NULL,
                receiver_node INTEGER, link_kind TEXT, firmware TEXT, preset TEXT, region TEXT,
                source TEXT NOT NULL, duplicates_suppressed INTEGER NOT NULL, notes TEXT)""",
            """CREATE TABLE observation(
                id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, receiver_node INTEGER,
                rx_ms INTEGER NOT NULL, node_rx_time INTEGER, lat REAL, lon REAL, accuracy_m REAL, fix_age_ms INTEGER,
                rssi INTEGER, snr REAL, packet_id INTEGER NOT NULL, from_node INTEGER NOT NULL, to_node INTEGER NOT NULL,
                channel INTEGER, portnum INTEGER, type TEXT NOT NULL, status TEXT NOT NULL,
                hop_start INTEGER, hop_limit INTEGER, hops_away INTEGER, relay_byte INTEGER, payload_len INTEGER,
                preset TEXT, region TEXT, frequency_mhz REAL, summary TEXT,
                source TEXT NOT NULL, origin_device TEXT NOT NULL DEFAULT '')""",
            """CREATE INDEX observation_time ON observation(rx_ms)""",
            """CREATE INDEX observation_pos ON observation(lat, lon)""",
            """CREATE UNIQUE INDEX observation_import_dedupe ON observation(origin_device, receiver_node, packet_id, rx_ms) WHERE origin_device <> ''""",
            """CREATE TABLE track_point(
                id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, ms INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL,
                accuracy_m REAL, radio_ready INTEGER NOT NULL, source TEXT NOT NULL, origin_device TEXT NOT NULL DEFAULT '')""",
            """CREATE INDEX track_time ON track_point(ms)""",
            """CREATE UNIQUE INDEX track_import_dedupe ON track_point(origin_device, ms, lat, lon) WHERE origin_device <> ''""",
            """CREATE TABLE logging_window(
                id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL,
                origin_device TEXT NOT NULL DEFAULT '', UNIQUE(origin_device, start_ms))""",
            """CREATE TABLE test_device(node_num INTEGER PRIMARY KEY, label TEXT NOT NULL, added_ms INTEGER NOT NULL)""",
            """CREATE TABLE test_session(
                id TEXT NOT NULL, role TEXT NOT NULL, mode TEXT NOT NULL, peer_node INTEGER NOT NULL, local_node INTEGER,
                started_ms INTEGER NOT NULL, ended_ms INTEGER, interval_s INTEGER, duration_s INTEGER, end_reason TEXT,
                source TEXT NOT NULL, origin_device TEXT NOT NULL DEFAULT '', PRIMARY KEY(id, role, origin_device))""",
            """CREATE TABLE test_tx(
                id INTEGER PRIMARY KEY AUTOINCREMENT, test_session_id TEXT NOT NULL, seq INTEGER NOT NULL, sent_ms INTEGER NOT NULL,
                packet_id INTEGER, to_node INTEGER NOT NULL, from_node INTEGER, lat REAL, lon REAL, accuracy_m REAL, fix_age_ms INTEGER,
                want_ack INTEGER NOT NULL, status TEXT NOT NULL, status_ms INTEGER, routing_error TEXT,
                source TEXT NOT NULL, origin_device TEXT NOT NULL DEFAULT '', UNIQUE(origin_device, test_session_id, seq))""",
            """CREATE TABLE test_rx(
                id INTEGER PRIMARY KEY AUTOINCREMENT, test_session_id TEXT NOT NULL, seq INTEGER NOT NULL, from_node INTEGER NOT NULL,
                to_node INTEGER, packet_id INTEGER NOT NULL, rx_ms INTEGER NOT NULL, sender_time_s INTEGER,
                rssi INTEGER, snr REAL, hop_start INTEGER, hop_limit INTEGER, relay_byte INTEGER,
                lat REAL, lon REAL, accuracy_m REAL, source TEXT NOT NULL, origin_device TEXT NOT NULL DEFAULT '',
                UNIQUE(origin_device, test_session_id, seq, packet_id))""",
            """CREATE TABLE node_name(node_num INTEGER PRIMARY KEY, long_name TEXT, short_name TEXT, role TEXT, updated_ms INTEGER NOT NULL)""",
            """CREATE TABLE import_record(sha256 TEXT PRIMARY KEY, imported_ms INTEGER NOT NULL, origin_device TEXT, kind TEXT NOT NULL, rows INTEGER NOT NULL)""",
        ),
        // v2 — delivery-status model verified against firmware, RF tagging, temporary preset changes
        listOf(
            """ALTER TABLE observation ADD COLUMN frequency_source TEXT""",
            """ALTER TABLE test_session ADD COLUMN preset TEXT""",
            """ALTER TABLE test_session ADD COLUMN delivery_mode TEXT""",
            """ALTER TABLE test_tx ADD COLUMN delivery_mode TEXT""",
            """ALTER TABLE test_tx ADD COLUMN hop_limit INTEGER""",
            """ALTER TABLE test_tx ADD COLUMN preset TEXT""",
            """ALTER TABLE test_tx ADD COLUMN frequency_mhz REAL""",
            """ALTER TABLE test_tx ADD COLUMN frequency_source TEXT""",
            """ALTER TABLE test_tx ADD COLUMN airtime_ms REAL""",
            """ALTER TABLE test_rx ADD COLUMN preset TEXT""",
            """ALTER TABLE test_rx ADD COLUMN frequency_mhz REAL""",
            """ALTER TABLE test_rx ADD COLUMN frequency_source TEXT""",
            // v1 frequencies were only ever configured overrides
            """UPDATE observation SET frequency_source='CONFIGURED_OVERRIDE' WHERE frequency_mhz IS NOT NULL""",
            // v1 status names -> v2
            """UPDATE test_tx SET status='RELAY_OBSERVED' WHERE status='IMPLICIT_ACK'""",
            """UPDATE test_tx SET status='UNKNOWN' WHERE status='NOT_CONFIRMED'""",
            """UPDATE test_tx SET status='NOT_TRANSMITTED' WHERE status='NOT_SENT'""",
            """UPDATE test_tx SET status=CASE WHEN routing_error IN ('NO_INTERFACE','TOO_LARGE','DUTY_CYCLE_LIMIT','BAD_REQUEST','RATE_LIMIT_EXCEEDED')
                THEN 'NOT_TRANSMITTED' ELSE 'NO_ACK' END WHERE status='FAILED'""",
            """CREATE TABLE preset_change(
                id INTEGER PRIMARY KEY AUTOINCREMENT, node_num INTEGER NOT NULL, created_ms INTEGER NOT NULL,
                original_raw TEXT NOT NULL, temp_raw TEXT NOT NULL, original_preset TEXT, target_preset TEXT NOT NULL,
                status TEXT NOT NULL, status_ms INTEGER NOT NULL, auto_restore INTEGER NOT NULL, note TEXT)""",
        ),
        // v3 — B participation, radio-reported airtime, GPS source, timing integrity
        listOf(
            """ALTER TABLE test_session ADD COLUMN participation TEXT""",
            """ALTER TABLE test_session ADD COLUMN ref_code INTEGER""",
            """ALTER TABLE test_session ADD COLUMN air_util_tx_start REAL""",
            """ALTER TABLE test_session ADD COLUMN ch_util_start REAL""",
            """ALTER TABLE test_session ADD COLUMN air_util_tx_end REAL""",
            """ALTER TABLE test_session ADD COLUMN ch_util_end REAL""",
            """ALTER TABLE test_session ADD COLUMN planned_end_ms INTEGER""",
            """ALTER TABLE test_rx ADD COLUMN ref_session_id TEXT""",
            """ALTER TABLE observation ADD COLUMN fix_source TEXT""",
            """CREATE INDEX test_tx_packet ON test_tx(packet_id)""",
            """CREATE TABLE clock_event(id INTEGER PRIMARY KEY AUTOINCREMENT, wall_ms INTEGER NOT NULL, kind TEXT NOT NULL,
                magnitude_ms INTEGER NOT NULL, session_id TEXT)""",
        ),
        // v4 — equipment profiles, radio-reported session snapshot, position provenance
        listOf(
            """CREATE TABLE equipment_profile(id TEXT NOT NULL, origin_device TEXT NOT NULL DEFAULT '', name TEXT NOT NULL,
                fields TEXT NOT NULL, created_ms INTEGER NOT NULL, updated_ms INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(id, origin_device))""",
            """ALTER TABLE survey_session ADD COLUMN profile_id TEXT""",
            """ALTER TABLE survey_session ADD COLUMN hw_model INTEGER""",
            """ALTER TABLE survey_session ADD COLUMN tx_power_dbm INTEGER""",
            """ALTER TABLE survey_session ADD COLUMN frequency_mhz REAL""",
            """ALTER TABLE survey_session ADD COLUMN frequency_source TEXT""",
            """ALTER TABLE observation ADD COLUMN position_age_ms INTEGER""",
        ),
        // v5 — sessions imported from other devices (for multi-device comparison)
        listOf(
            """ALTER TABLE survey_session ADD COLUMN origin_device TEXT NOT NULL DEFAULT ''""",
        ),
        // v6 — several radios at once: logging windows per receiver (table rebuilt for the new unique key)
        listOf(
            """CREATE TABLE logging_window_v6(
                id INTEGER PRIMARY KEY AUTOINCREMENT, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL,
                origin_device TEXT NOT NULL DEFAULT '', receiver_node INTEGER NOT NULL DEFAULT -1,
                UNIQUE(origin_device, receiver_node, start_ms))""",
            """INSERT INTO logging_window_v6(start_ms,end_ms,origin_device,receiver_node) SELECT start_ms,end_ms,origin_device,-1 FROM logging_window""",
            """DROP TABLE logging_window""",
            """ALTER TABLE logging_window_v6 RENAME TO logging_window""",
        ),
    )
    val version get() = migrations.size
}

class SqlStore(private val db: SqlDriver) : Store {

    init { migrate() }

    private fun migrate() {
        val current = db.query("PRAGMA user_version") { it.long(0) ?: 0 }.firstOrNull()?.toInt() ?: 0
        if (current > Schema.version) throw IllegalStateException("database is newer (v$current) than this app (v${Schema.version})")
        for (v in current until Schema.version) {
            db.transaction {
                Schema.migrations[v].forEach { db.execute(it) }
                db.execute("PRAGMA user_version = ${v + 1}")
            }
        }
    }

    fun schemaVersion(): Int = db.query("PRAGMA user_version") { it.int(0) ?: 0 }.first()

    fun getMeta(key: String): String? = db.query("SELECT value FROM kv WHERE key=?", listOf(key)) { it.string(0) }.firstOrNull()
    fun setMeta(key: String, value: String) { db.execute("INSERT OR REPLACE INTO kv(key,value) VALUES(?,?)", listOf(key, value)) }

    private fun b(v: Boolean) = if (v) 1 else 0
    private fun o(s: String?) = s ?: ""
    private fun un(s: String?) = s?.takeIf { it.isNotEmpty() }

    // ---------------- sessions ----------------

    override fun insertSession(s: SurveySession) {
        db.execute("""INSERT OR IGNORE INTO survey_session(id,started_ms,ended_ms,status,receiver_node,link_kind,firmware,preset,region,source,duplicates_suppressed,notes,
            profile_id,hw_model,tx_power_dbm,frequency_mhz,frequency_source,origin_device)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            listOf(s.id, s.startedMs, s.endedMs, s.status.name, s.receiverNode, s.linkKind, s.firmware, s.preset, s.region,
                s.source.name, b(s.duplicatesSuppressed), s.notes, s.profileId, s.hwModel, s.txPowerDbm, s.frequencyMhz, s.frequencySource,
                o(s.originDevice)))
    }

    override fun endSession(id: String, endedMs: Long, status: SessionStatus) {
        db.execute("UPDATE survey_session SET ended_ms=?, status=? WHERE id=? AND origin_device=''", listOf(endedMs, status.name, id))
    }

    override fun sessions(): List<SurveySession> = db.query(
        "SELECT id,started_ms,ended_ms,status,receiver_node,link_kind,firmware,preset,region,source,duplicates_suppressed,notes," +
            "profile_id,hw_model,tx_power_dbm,frequency_mhz,frequency_source,origin_device FROM survey_session ORDER BY started_ms DESC"
    ) { r ->
        SurveySession(r.string(0)!!, r.long(1)!!, r.long(2), SessionStatus.valueOf(r.string(3)!!), r.long(4), r.string(5),
            r.string(6), r.string(7), r.string(8), Source.valueOf(r.string(9)!!), r.long(10) == 1L, r.string(11),
            r.string(12), r.int(13), r.int(14), r.double(15), r.string(16), un(r.string(17)))
    }

    // ---------------- equipment profiles ----------------

    override fun upsertProfile(p: meshsurvey.core.model.EquipmentProfile) {
        db.execute("""INSERT OR REPLACE INTO equipment_profile(id,origin_device,name,fields,created_ms,updated_ms,deleted) VALUES(?,?,?,?,?,?,0)""",
            listOf(p.id, o(p.originDevice), p.name, meshsurvey.core.model.EquipmentCodec.encode(p), p.createdMs, p.updatedMs))
    }

    override fun deleteProfile(id: String) {
        db.execute("UPDATE equipment_profile SET deleted=1 WHERE id=? AND origin_device=''", listOf(id))
    }

    override fun profiles(includeDeleted: Boolean): List<meshsurvey.core.model.EquipmentProfile> = db.query(
        "SELECT fields, origin_device FROM equipment_profile" + (if (includeDeleted) "" else " WHERE deleted=0") + " ORDER BY name"
    ) { r -> meshsurvey.core.model.EquipmentCodec.decode(r.string(0)!!, un(r.string(1))) }.filterNotNull()

    override fun recoverInterrupted(nowMs: Long): Int = db.transaction {
        // end time = last thing we logged for that session, if any
        val n = db.execute("""UPDATE survey_session SET status='INTERRUPTED',
            ended_ms=COALESCE((SELECT MAX(rx_ms) FROM observation WHERE session_id=survey_session.id),
                              (SELECT MAX(ms) FROM track_point WHERE session_id=survey_session.id), started_ms)
            WHERE status='ACTIVE' AND origin_device=''""")
        val t = db.execute("""UPDATE test_session SET end_reason='interrupted (app closed or crashed)',
            ended_ms=COALESCE((SELECT MAX(sent_ms) FROM test_tx WHERE test_session_id=test_session.id AND test_tx.origin_device=test_session.origin_device), started_ms)
            WHERE ended_ms IS NULL AND origin_device='' AND role='A_SURVEY'""")
        // B reference sessions: closed at the last reception (or start); never silently extended
        val b = db.execute("""UPDATE test_session SET end_reason='interrupted (app closed or crashed)',
            ended_ms=COALESCE((SELECT MAX(rx_ms) FROM test_rx WHERE ref_session_id=test_session.id AND test_rx.origin_device=''), started_ms)
            WHERE ended_ms IS NULL AND origin_device='' AND role='B_REFERENCE' AND ref_code IS NOT NULL""")
        // tests still awaiting an outcome: unknown (a late ACK can still upgrade them via packet-id lookup)
        db.execute("""UPDATE test_tx SET status='UNKNOWN', routing_error='INTERRUPTED', status_ms=? WHERE status='SENT' AND origin_device=''""", listOf(nowMs))
        n + t + b
    }

    // ---------------- observations ----------------

    override fun insertObservation(o: Observation): Long = db.insert(
        """INSERT OR IGNORE INTO observation(session_id,receiver_node,rx_ms,node_rx_time,lat,lon,accuracy_m,fix_age_ms,rssi,snr,packet_id,
            from_node,to_node,channel,portnum,type,status,hop_start,hop_limit,hops_away,relay_byte,payload_len,preset,region,frequency_mhz,
            summary,source,origin_device,frequency_source,fix_source,position_age_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        listOf(o.sessionId, o.receiverNode, o.rxMs, o.nodeRxTime, o.lat, o.lon, o.accuracyM, o.fixAgeMs, o.rssi, o.snr, o.packetId,
            o.fromNode, o.toNode, o.channel, o.portnum, o.type.name, o.status.name, o.hopStart, o.hopLimit, o.hopsAway, o.relayByte,
            o.payloadLen, o.preset, o.region, o.frequencyMhz, o.summary, o.source.name, o(o.originDevice), o.frequencySource, o.fixSource,
            o.positionAgeMs)
    )

    private fun where(f: ObsFilter, args: MutableList<Any?>): String {
        val w = ArrayList<String>()
        f.fromMs?.let { w += "rx_ms >= ?"; args += it }
        f.toMs?.let { w += "rx_ms < ?"; args += it }
        f.preset?.let { w += "preset = ?"; args += it }
        f.minSnr?.let { w += "snr >= ?"; args += it }
        f.minRssi?.let { w += "rssi >= ?"; args += it }
        f.maxAccuracyM?.let { w += "accuracy_m IS NOT NULL AND accuracy_m <= ?"; args += it }
        f.sessionId?.let { w += "session_id = ?"; args += it }
        f.receiverNode?.let { w += "receiver_node = ?"; args += it }
        f.fromNodes?.let { s -> w += if (s.isEmpty()) "0" else "from_node IN (${s.joinToString(",") { "?" }})"; args.addAll(s) }
        f.types?.let { s -> w += if (s.isEmpty()) "0" else "type IN (${s.joinToString(",") { "?" }})"; args.addAll(s.map { it.name }) }
        f.sources?.let { s -> w += if (s.isEmpty()) "0" else "source IN (${s.joinToString(",") { "?" }})"; args.addAll(s.map { it.name }) }
        if (f.withPositionOnly) w += "lat IS NOT NULL AND lon IS NOT NULL"
        f.bbox?.let { bb -> w += "lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?"; args.addAll(listOf(bb[0], bb[1], bb[2], bb[3])) }
        return if (w.isEmpty()) "1" else w.joinToString(" AND ")
    }

    override fun observations(f: ObsFilter): List<Observation> {
        val args = ArrayList<Any?>()
        val sql = """SELECT id,session_id,receiver_node,rx_ms,node_rx_time,lat,lon,accuracy_m,fix_age_ms,rssi,snr,packet_id,from_node,to_node,
            channel,portnum,type,status,hop_start,hop_limit,hops_away,relay_byte,payload_len,preset,region,frequency_mhz,summary,source,origin_device,
            frequency_source,fix_source,position_age_ms FROM observation WHERE ${where(f, args)} ORDER BY rx_ms""" + (f.limit?.let { " LIMIT $it" } ?: "")
        return db.query(sql, args) { r ->
            Observation(r.long(0)!!, r.string(1), r.long(2), r.long(3)!!, r.long(4), r.double(5), r.double(6), r.double(7), r.long(8),
                r.int(9), r.double(10), r.long(11)!!, r.long(12)!!, r.long(13)!!, r.int(14), r.int(15),
                PacketType.valueOf(r.string(16)!!), RxStatus.valueOf(r.string(17)!!), r.int(18), r.int(19), r.int(20), r.int(21),
                r.int(22), r.string(23), r.string(24), r.double(25), r.string(26), Source.valueOf(r.string(27)!!), un(r.string(28)), r.string(29), r.string(30),
                r.long(31))
        }
    }

    override fun countObservations(f: ObsFilter): Int {
        val args = ArrayList<Any?>()
        return db.query("SELECT COUNT(*) FROM observation WHERE ${where(f, args)}", args) { it.int(0) ?: 0 }.first()
    }

    override fun insertTrack(t: TrackPoint): Long = db.insert(
        "INSERT OR IGNORE INTO track_point(session_id,ms,lat,lon,accuracy_m,radio_ready,source,origin_device) VALUES(?,?,?,?,?,?,?,?)",
        listOf(t.sessionId, t.ms, t.lat, t.lon, t.accuracyM, b(t.radioReady), t.source.name, o(t.originDevice))
    )

    override fun track(fromMs: Long?, toMs: Long?, sessionId: String?): List<TrackPoint> {
        val w = ArrayList<String>(); val a = ArrayList<Any?>()
        fromMs?.let { w += "ms >= ?"; a += it }
        toMs?.let { w += "ms < ?"; a += it }
        sessionId?.let { w += "session_id = ?"; a += it }
        return db.query("SELECT id,session_id,ms,lat,lon,accuracy_m,radio_ready,source,origin_device FROM track_point WHERE " +
            (if (w.isEmpty()) "1" else w.joinToString(" AND ")) + " ORDER BY ms", a) { r ->
            TrackPoint(r.long(0)!!, r.string(1), r.long(2)!!, r.double(3)!!, r.double(4)!!, r.double(5), r.long(6) == 1L,
                Source.valueOf(r.string(7)!!), un(r.string(8)))
        }
    }

    override fun insertWindow(w: LoggingWindow) {
        db.execute("INSERT OR REPLACE INTO logging_window(start_ms,end_ms,origin_device,receiver_node) VALUES(?,?,?,?)",
            listOf(w.startMs, w.endMs, o(w.originDevice), w.receiverNode ?: -1L))
    }

    override fun windows(originDevice: String?): List<LoggingWindow> =
        db.query("SELECT start_ms,end_ms,origin_device,receiver_node FROM logging_window WHERE origin_device=? ORDER BY start_ms", listOf(o(originDevice))) {
            LoggingWindow(it.long(0)!!, it.long(1)!!, un(it.string(2)), it.long(3)?.takeIf { n -> n != -1L })
        }

    // ---------------- A/B ----------------

    override fun upsertTestDevice(d: TestDevice) {
        db.execute("INSERT OR REPLACE INTO test_device(node_num,label,added_ms) VALUES(?,?,?)", listOf(d.nodeNum, d.label, d.addedMs))
    }
    override fun removeTestDevice(nodeNum: Long) { db.execute("DELETE FROM test_device WHERE node_num=?", listOf(nodeNum)) }
    override fun testDevices(): List<TestDevice> =
        db.query("SELECT node_num,label,added_ms FROM test_device ORDER BY label") { TestDevice(it.long(0)!!, it.string(1)!!, it.long(2)!!) }

    override fun insertTestSession(s: TestSession) {
        db.execute("""INSERT OR IGNORE INTO test_session(id,role,mode,peer_node,local_node,started_ms,ended_ms,interval_s,duration_s,end_reason,source,origin_device,preset,delivery_mode,
            participation,ref_code,air_util_tx_start,ch_util_start,air_util_tx_end,ch_util_end,planned_end_ms)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            listOf(s.id, s.role.name, s.mode.name, s.peerNode, s.localNode, s.startedMs, s.endedMs, s.intervalS, s.durationS, s.endReason,
                s.source.name, o(s.originDevice), s.preset, s.deliveryMode?.name, s.participation?.name, s.refCode,
                s.airUtilTxStart, s.chUtilStart, s.airUtilTxEnd, s.chUtilEnd, s.plannedEndMs))
    }

    override fun endTestSession(id: String, endedMs: Long, reason: String) {
        db.execute("UPDATE test_session SET ended_ms=?, end_reason=? WHERE id=? AND origin_device='' AND ended_ms IS NULL", listOf(endedMs, reason, id))
    }

    override fun findTestTxByPacketId(packetId: Long, sinceMs: Long): TestTx? =
        testTxWhere("WHERE packet_id=? AND origin_device='' AND sent_ms >= ?", listOf(packetId, sinceMs)).lastOrNull()

    override fun insertClockEvent(e: ClockEvent) {
        db.execute("INSERT INTO clock_event(wall_ms,kind,magnitude_ms,session_id) VALUES(?,?,?,?)", listOf(e.wallMs, e.kind.name, e.magnitudeMs, e.sessionId))
    }

    override fun clockEvents(sessionId: String?): List<ClockEvent> {
        val a = ArrayList<Any?>()
        val w = sessionId?.let { a += it; "WHERE session_id=?" } ?: ""
        return db.query("SELECT id,wall_ms,kind,magnitude_ms,session_id FROM clock_event $w ORDER BY wall_ms", a) {
            ClockEvent(it.long(0)!!, it.long(1)!!, ClockEventKind.valueOf(it.string(2)!!), it.long(3)!!, it.string(4))
        }
    }

    override fun testSessions(): List<TestSession> = db.query(
        """SELECT id,role,mode,peer_node,local_node,started_ms,ended_ms,interval_s,duration_s,end_reason,source,origin_device,preset,delivery_mode,
            participation,ref_code,air_util_tx_start,ch_util_start,air_util_tx_end,ch_util_end,planned_end_ms FROM test_session ORDER BY started_ms DESC"""
    ) { r ->
        TestSession(r.string(0)!!, TestRole.valueOf(r.string(1)!!), TestMode.valueOf(r.string(2)!!), r.long(3)!!, r.long(4), r.long(5)!!,
            r.long(6), r.int(7), r.int(8), r.string(9), Source.valueOf(r.string(10)!!), un(r.string(11)), r.string(12),
            r.string(13)?.let { DeliveryMode.valueOf(it) }, r.string(14)?.let { Participation.valueOf(it) }, r.int(15),
            r.double(16), r.double(17), r.double(18), r.double(19), r.long(20))
    }

    /** Records radio-reported airtime at session end (A) and closes it. */
    override fun setTestSessionAirtimeEnd(id: String, airUtilTx: Double?, chUtil: Double?) {
        db.execute("UPDATE test_session SET air_util_tx_end=?, ch_util_end=? WHERE id=? AND origin_device=''", listOf(airUtilTx, chUtil, id))
    }

    override fun insertTestTx(t: TestTx): Long = db.insert(
        """INSERT OR IGNORE INTO test_tx(test_session_id,seq,sent_ms,packet_id,to_node,from_node,lat,lon,accuracy_m,fix_age_ms,want_ack,status,status_ms,routing_error,source,origin_device,
            delivery_mode,hop_limit,preset,frequency_mhz,frequency_source,airtime_ms)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        listOf(t.testSessionId, t.seq, t.sentMs, t.packetId, t.toNode, t.fromNode, t.lat, t.lon, t.accuracyM, t.fixAgeMs, b(t.wantAck),
            t.status.name, t.statusMs, t.routingError, t.source.name, o(t.originDevice), t.deliveryMode?.name, t.hopLimit, t.preset,
            t.frequencyMhz, t.frequencySource, t.airtimeMs)
    )

    override fun updateTestTx(id: Long, status: TxStatus, statusMs: Long, routingError: String?) {
        db.execute("UPDATE test_tx SET status=?, status_ms=?, routing_error=? WHERE id=?", listOf(status.name, statusMs, routingError, id))
    }

    override fun testTx(sessionId: String?): List<TestTx> {
        val a = ArrayList<Any?>()
        val w = sessionId?.let { a += it; "WHERE test_session_id=?" } ?: ""
        return testTxWhere(w, a)
    }

    private fun testTxWhere(w: String, a: List<Any?>): List<TestTx> {
        return db.query("""SELECT id,test_session_id,seq,sent_ms,packet_id,to_node,from_node,lat,lon,accuracy_m,fix_age_ms,want_ack,status,status_ms,routing_error,source,origin_device,
            delivery_mode,hop_limit,preset,frequency_mhz,frequency_source,airtime_ms
            FROM test_tx $w ORDER BY sent_ms""", a) { r ->
            TestTx(r.long(0)!!, r.string(1)!!, r.int(2)!!, r.long(3)!!, r.long(4), r.long(5)!!, r.long(6), r.double(7), r.double(8), r.double(9),
                r.long(10), r.long(11) == 1L, TxStatus.valueOf(r.string(12)!!), r.long(13), r.string(14), Source.valueOf(r.string(15)!!), un(r.string(16)),
                r.string(17)?.let { DeliveryMode.valueOf(it) }, r.int(18), r.string(19), r.double(20), r.string(21), r.double(22))
        }
    }

    override fun insertTestRx(r: TestRx): Boolean = db.execute(
        """INSERT OR IGNORE INTO test_rx(test_session_id,seq,from_node,to_node,packet_id,rx_ms,sender_time_s,rssi,snr,hop_start,hop_limit,relay_byte,lat,lon,accuracy_m,source,origin_device,
            preset,frequency_mhz,frequency_source,ref_session_id)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        listOf(r.testSessionId, r.seq, r.fromNode, r.toNode, r.packetId, r.rxMs, r.senderTimeS, r.rssi, r.snr, r.hopStart, r.hopLimit, r.relayByte,
            r.lat, r.lon, r.accuracyM, r.source.name, o(r.originDevice), r.preset, r.frequencyMhz, r.frequencySource, r.refSessionId)
    ) > 0

    override fun testRx(sessionId: String?): List<TestRx> {
        val a = ArrayList<Any?>()
        val w = sessionId?.let { a += it; "WHERE test_session_id=?" } ?: ""
        return db.query("""SELECT id,test_session_id,seq,from_node,to_node,packet_id,rx_ms,sender_time_s,rssi,snr,hop_start,hop_limit,relay_byte,lat,lon,accuracy_m,source,origin_device,
            preset,frequency_mhz,frequency_source,ref_session_id
            FROM test_rx $w ORDER BY rx_ms""", a) { r ->
            TestRx(r.long(0)!!, r.string(1)!!, r.int(2)!!, r.long(3)!!, r.long(4), r.long(5)!!, r.long(6)!!, r.long(7), r.int(8), r.double(9),
                r.int(10), r.int(11), r.int(12), r.double(13), r.double(14), r.double(15), Source.valueOf(r.string(16)!!), un(r.string(17)),
                r.string(18), r.double(19), r.string(20), r.string(21))
        }
    }

    // ---------------- node names (local display only, never uploaded) ----------------

    override fun upsertNodeName(num: Long, longName: String?, shortName: String?, role: String?, nowMs: Long) {
        db.execute("""INSERT INTO node_name(node_num,long_name,short_name,role,updated_ms) VALUES(?,?,?,?,?)
            ON CONFLICT(node_num) DO UPDATE SET long_name=COALESCE(excluded.long_name,long_name),
            short_name=COALESCE(excluded.short_name,short_name), role=COALESCE(excluded.role,role), updated_ms=excluded.updated_ms""",
            listOf(num, longName, shortName, role, nowMs))
    }

    override fun nodeNames(): Map<Long, NodeName> =
        db.query("SELECT node_num,long_name,short_name,role FROM node_name") { NodeName(it.long(0)!!, it.string(1), it.string(2), it.string(3)) }
            .associateBy { it.num }

    // ---------------- temporary preset changes ----------------

    override fun insertPresetChange(c: PresetChange): Long = db.insert(
        """INSERT INTO preset_change(node_num,created_ms,original_raw,temp_raw,original_preset,target_preset,status,status_ms,auto_restore,note)
            VALUES(?,?,?,?,?,?,?,?,?,?)""",
        listOf(c.nodeNum, c.createdMs, c.originalRaw.toHex(), c.tempRaw.toHex(), c.originalPreset, c.targetPreset, c.status.name, c.statusMs,
            b(c.autoRestore), c.note))

    override fun updatePresetChange(id: Long, status: PresetChangeStatus, statusMs: Long, note: String?) {
        db.execute("UPDATE preset_change SET status=?, status_ms=?, note=COALESCE(?, note) WHERE id=?", listOf(status.name, statusMs, note, id))
    }

    override fun presetChanges(): List<PresetChange> = db.query(
        "SELECT id,node_num,created_ms,original_raw,temp_raw,original_preset,target_preset,status,status_ms,auto_restore,note FROM preset_change ORDER BY id"
    ) { r ->
        PresetChange(r.long(0)!!, r.long(1)!!, r.long(2)!!, r.string(3)!!.hexToBytes()!!, r.string(4)!!.hexToBytes()!!, r.string(5), r.string(6)!!,
            PresetChangeStatus.valueOf(r.string(7)!!), r.long(8)!!, r.long(9) == 1L, r.string(10))
    }

    // ---------------- imports ----------------

    override fun findImport(sha256: String): ImportRecord? =
        db.query("SELECT sha256,imported_ms,origin_device,kind,rows FROM import_record WHERE sha256=?", listOf(sha256)) {
            ImportRecord(it.string(0)!!, it.long(1)!!, it.string(2), it.string(3)!!, it.int(4)!!)
        }.firstOrNull()

    override fun insertImport(r: ImportRecord) {
        db.execute("INSERT INTO import_record(sha256,imported_ms,origin_device,kind,rows) VALUES(?,?,?,?,?)",
            listOf(r.sha256, r.importedMs, r.originDevice, r.kind, r.rows))
    }

    override fun <T> transaction(block: () -> T): T = db.transaction(block)

    override fun clearAll() {
        db.transaction {
            listOf("observation", "track_point", "logging_window", "survey_session", "test_session", "test_tx", "test_rx", "import_record", "clock_event")
                .forEach { db.execute("DELETE FROM $it") }
        }
    }
}

