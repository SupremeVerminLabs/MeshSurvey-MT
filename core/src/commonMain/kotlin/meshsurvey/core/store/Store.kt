package meshsurvey.core.store

import meshsurvey.core.model.ImportRecord
import meshsurvey.core.model.LoggingWindow
import meshsurvey.core.model.Observation
import meshsurvey.core.model.PacketType
import meshsurvey.core.model.Source
import meshsurvey.core.model.SurveySession
import meshsurvey.core.model.TestDevice
import meshsurvey.core.model.TestRx
import meshsurvey.core.model.TestSession
import meshsurvey.core.model.TestTx
import meshsurvey.core.model.TrackPoint
import meshsurvey.core.model.TxStatus

data class NodeName(val num: Long, val longName: String?, val shortName: String?, val role: String?) {
    val label: String get() = listOfNotNull(shortName, longName).joinToString(" · ").ifEmpty { meshsurvey.core.proto.MeshEnums.nodeId(num) }
}

/** Query filter shared by map, monitor and exports. Null = no constraint. */
data class ObsFilter(
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val preset: String? = null,
    val minSnr: Double? = null,
    val minRssi: Int? = null,
    val maxAccuracyM: Double? = null,
    val fromNodes: Set<Long>? = null,
    val types: Set<PacketType>? = null,
    val sources: Set<Source>? = null,
    val sessionId: String? = null,
    /** Only receptions by this radio (node number); null = all receivers. */
    val receiverNode: Long? = null,
    /** "meshtastic" or "meshcore"; null = both. */
    val protocol: String? = null,
    /** Only receptions heard directly from these nodes (group keys from [meshsurvey.core.map.Heard.group]); null = all. */
    val heardGroups: Set<String>? = null,
    /** Only receptions with exactly these heard_id values (e.g. "mt:!a4c62e61" = heard directly from that node). */
    val heardIds: Set<String>? = null,
    val withPositionOnly: Boolean = false,
    val bbox: DoubleArray? = null, // latMin, latMax, lonMin, lonMax
    val limit: Int? = null,
)

interface Store {
    // sessions
    fun insertSession(s: SurveySession)
    fun endSession(id: String, endedMs: Long, status: meshsurvey.core.model.SessionStatus)
    fun sessions(): List<SurveySession>
    /** Crash recovery: any session/test session still ACTIVE/open is marked interrupted. Returns count. */
    fun recoverInterrupted(nowMs: Long): Int

    // observations & track
    fun insertObservation(o: Observation): Long
    fun observations(f: ObsFilter = ObsFilter()): List<Observation>
    fun countObservations(f: ObsFilter = ObsFilter()): Int
    fun insertTrack(t: TrackPoint): Long
    fun track(fromMs: Long? = null, toMs: Long? = null, sessionId: String? = null): List<TrackPoint>

    // logging windows (connected periods)
    fun insertWindow(w: LoggingWindow)
    fun windows(originDevice: String? = null): List<LoggingWindow>

    // A/B testing
    fun upsertTestDevice(d: TestDevice)
    fun removeTestDevice(nodeNum: Long)
    fun testDevices(): List<TestDevice>
    fun insertTestSession(s: TestSession)
    fun endTestSession(id: String, endedMs: Long, reason: String)
    fun setTestSessionAirtimeEnd(id: String, airUtilTx: Double?, chUtil: Double?)
    /** Own test transmission by packet id (for ACKs that arrive after the timeout or after a restart). */
    fun findTestTxByPacketId(packetId: Long, sinceMs: Long): TestTx?
    fun insertClockEvent(e: meshsurvey.core.model.ClockEvent)
    fun clockEvents(sessionId: String? = null): List<meshsurvey.core.model.ClockEvent>
    fun testSessions(): List<TestSession>
    fun insertTestTx(t: TestTx): Long
    fun updateTestTx(id: Long, status: TxStatus, statusMs: Long, routingError: String?)
    fun testTx(sessionId: String? = null): List<TestTx>
    /** Returns false if an identical reception (same origin/session/seq/packet) already exists. */
    fun insertTestRx(r: TestRx): Boolean
    fun testRx(sessionId: String? = null): List<TestRx>

    // equipment profiles (user-entered; soft-deleted so old sessions keep their reference)
    fun upsertProfile(p: meshsurvey.core.model.EquipmentProfile)
    fun deleteProfile(id: String)
    /** All profiles, including ones imported from other devices; deleted ones only if [includeDeleted]. */
    fun profiles(includeDeleted: Boolean = false): List<meshsurvey.core.model.EquipmentProfile>

    // node names for display
    fun upsertNodeName(num: Long, longName: String?, shortName: String?, role: String?, nowMs: Long)
    fun nodeNames(): Map<Long, NodeName>

    // temporary preset changes (byte-exact original config kept for restore)
    fun insertPresetChange(c: meshsurvey.core.model.PresetChange): Long
    fun updatePresetChange(id: Long, status: meshsurvey.core.model.PresetChangeStatus, statusMs: Long, note: String?)
    fun presetChanges(): List<meshsurvey.core.model.PresetChange>

    // imports
    fun findImport(sha256: String): ImportRecord?
    fun insertImport(r: ImportRecord)

    fun <T> transaction(block: () -> T): T
    fun clearAll()
}
