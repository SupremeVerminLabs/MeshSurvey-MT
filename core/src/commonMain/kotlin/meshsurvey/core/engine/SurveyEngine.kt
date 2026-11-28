package meshsurvey.core.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import meshsurvey.core.abtest.AbState
import meshsurvey.core.abtest.AbTestController
import meshsurvey.core.abtest.ReferenceController
import meshsurvey.core.abtest.ReferenceState
import meshsurvey.core.radio.RadioMetrics
import meshsurvey.core.log.LoggerConfig
import meshsurvey.core.log.LoggerStats
import meshsurvey.core.log.ReceptionLogger
import meshsurvey.core.model.Fix
import meshsurvey.core.model.LoggingWindow
import meshsurvey.core.model.Observation
import meshsurvey.core.model.PresetChange
import meshsurvey.core.model.PresetChangeStatus
import meshsurvey.core.preset.ConfigBackupStore
import meshsurvey.core.preset.MemoryBackupStore
import meshsurvey.core.preset.PresetManager
import meshsurvey.core.model.SessionStatus
import meshsurvey.core.model.Source
import meshsurvey.core.model.TestRx
import meshsurvey.core.proto.NodeRecord
import meshsurvey.core.proto.PositionInfo
import meshsurvey.core.model.EquipmentProfile
import meshsurvey.core.radio.LinkKind
import meshsurvey.core.radio.RadioClient
import meshsurvey.core.radio.RadioInfo
import meshsurvey.core.radio.RadioLink
import meshsurvey.core.radio.RadioListener
import meshsurvey.core.radio.RadioState
import meshsurvey.core.radio.ReceivedPacket
import meshsurvey.core.store.SqlStore
import meshsurvey.core.util.Clock
import meshsurvey.core.util.newId

enum class Edition { LITE, ADVANCED }

const val TICK_GAP_MS = 5_000L
const val CLOCK_JUMP_MS = 2_000L

data class RadioStatus(val state: RadioState = RadioState.DISCONNECTED, val detail: String = "", val info: RadioInfo = RadioInfo())

/**
 * The single object a UI talks to. Platform code must:
 *  - call every method from one thread (UI/main thread),
 *  - call [tick] about once per second,
 *  - feed GPS fixes to [onFix],
 *  - call [onAppBackground] when the UI is no longer visible.
 */
class SurveyEngine(
    val store: SqlStore,
    val clock: Clock,
    val edition: Edition = Edition.LITE,
    val appVersion: String = "0.1.0",
    loggerConfig: LoggerConfig = LoggerConfig(),
    /** Durable second copy of temporary preset changes (platforms pass a file-based store). */
    val configBackups: ConfigBackupStore = MemoryBackupStore(),
    /**
     * One engine per connected radio. The primary engine does the once-per-app chores (crash recovery, backup
     * reconciliation, recording app suspensions); additional engines are created by [Station] for more radios.
     */
    val primary: Boolean = true,
    /** State shared by all engines of one app (equipment profile list). */
    val shared: SharedState = SharedState(store),
) : RadioListener {

    val deviceId: String = store.getMeta("device_id") ?: newId(bytes = 6).also { store.setMeta("device_id", it) }
    val recoveredOnStart: Int = if (primary) store.recoverInterrupted(clock.wallMs()) else 0

    val radio = RadioClient(clock)
    /** The connected radio's own position as a receiver position source (stationary receivers). */
    val radioGps = RadioGpsSource(clock)
    private val _radioPosition = MutableStateFlow<PositionInfo?>(null)
    /** Latest position the radio reported about itself (null = none yet). */
    val radioPosition: StateFlow<PositionInfo?> = _radioPosition
    private var lastOwnInfoPollMono = 0L

    // ---------------- equipment profiles (optional, user-entered) ----------------
    val profiles: StateFlow<List<EquipmentProfile>> = shared.profiles
    /** Each radio has its own active profile (keyed by node number), so two radios can carry two antennas. */
    private fun profileKey(node: Long?) = if (node == null) "active_profile_id" else "active_profile_id:$node"
    private val _activeProfileId = MutableStateFlow(if (primary) store.getMeta("active_profile_id")?.ifEmpty { null } else null)
    /** Profile recorded with new survey sessions (null = none). */
    val activeProfileId: StateFlow<String?> = _activeProfileId

    fun saveProfile(p: EquipmentProfile) {
        val now = clock.wallMs()
        store.upsertProfile(p.copy(createdMs = if (p.createdMs == 0L) now else p.createdMs, updatedMs = now, originDevice = null))
        shared.refreshProfiles()
    }

    fun deleteProfile(id: String) {
        store.deleteProfile(id)
        if (_activeProfileId.value == id) setActiveProfile(null)
        shared.refreshProfiles()
    }

    /**
     * Each survey session belongs to one profile, so changing it while logging ends the current session and
     * starts a new one (recorded in the event log); nothing already logged is re-attributed.
     */
    fun setActiveProfile(id: String?) {
        if (id == _activeProfileId.value) return
        _activeProfileId.value = id
        store.setMeta(profileKey(radio.info.myNodeNum), id ?: "")
        if (logger.session != null && radio.isReady) {
            logger.endSession()
            val s = logger.startSession(radio.info, source(), id)
            event("Equipment profile changed to ${profiles.value.firstOrNull { it.id == id }?.name ?: "none"}; new session ${s.id.take(6)}")
        }
    }
    val logger = ReceptionLogger(store, clock, loggerConfig)
    val presets = PresetManager(store, radio, clock, configBackups)
    private val _metrics = MutableStateFlow<RadioMetrics?>(null)
    /** Airtime/channel use REPORTED by the connected radio about itself (not estimated by the app). */
    val radioMetrics: StateFlow<RadioMetrics?> = _metrics
    val ab = AbTestController(store, radio, clock, { logger.lastFix }, logger::fixAgeMs, ::source, { presets.sendBlocker() },
        metrics = { _metrics.value })
    val reference = ReferenceController(store, clock, { logger.freshFix() }, ::source, { radio.info })
    private val _ref = MutableStateFlow(ReferenceState())
    val referenceState: StateFlow<ReferenceState> = _ref

    private val _radio = MutableStateFlow(RadioStatus())
    val radioStatus: StateFlow<RadioStatus> = _radio
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix
    private val _stats = MutableStateFlow(LoggerStats())
    val stats: StateFlow<LoggerStats> = _stats
    private val _recent = MutableStateFlow<List<Observation>>(emptyList())
    val recent: StateFlow<List<Observation>> = _recent
    private val _nodes = MutableStateFlow<Map<Long, NodeRecord>>(emptyMap())
    val nodes: StateFlow<Map<Long, NodeRecord>> = _nodes
    private val _ab = MutableStateFlow(AbState())
    val abState: StateFlow<AbState> = _ab
    private val _testRx = MutableStateFlow<List<TestRx>>(emptyList())
    val testRxRecent: StateFlow<List<TestRx>> = _testRx
    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events
    /** Bumped whenever stored data changes, so map views know to re-query. */
    private val _preset = MutableStateFlow<PresetChange?>(null)
    /** Open temporary preset change (any radio), or null. Surfaced as a persistent banner. */
    val presetChange: StateFlow<PresetChange?> = _preset
    private val _dataVersion = MutableStateFlow(0L)
    val dataVersion: StateFlow<Long> = _dataVersion

    data class TimingState(val tickGaps: Int = 0, val longestGapMs: Long = 0, val clockJumps: Int = 0, val lastJumpMs: Long = 0)
    private val _timing = MutableStateFlow(TimingState())
    /** Suspensions and clock jumps since start (also stored as clock events and exported). */
    val timing: StateFlow<TimingState> = _timing
    private var lastTickMono: Long? = null
    private var lastTickWall: Long? = null

    private var windowStart: Long? = null
    private var lastWindowWrite = 0L

    init {
        radio.addListener(this)
        logger.addObservationListener { o ->
            _recent.value = (listOf(o) + _recent.value).take(300)
            _stats.value = logger.stats
            _dataVersion.value++
        }
        var wasAuto = false
        ab.addListener {
            _ab.value = it; _dataVersion.value++
            if (wasAuto && !it.autoRunning) {
                val open = presets.openFor(radio.info.myNodeNum)
                if (open != null && open.autoRestore && open.status == PresetChangeStatus.ACTIVE && radio.isReady) {
                    val err = presets.restore()
                    event(if (err == null) "Session ended: restoring original LoRa configuration (${open.originalPreset})" else "Automatic restore failed: $err")
                }
            }
            wasAuto = it.autoRunning
        }
        presets.addListener { _preset.value = it; it?.let { c -> event("Preset change ${c.targetPreset}: ${c.status}${c.note?.let { n -> " ($n)" } ?: ""}") } }
        val recoveredBackups = if (primary) presets.reconcileBackups() else 0
        if (recoveredBackups > 0) event("Recovered $recoveredBackups configuration backup(s) missing from the database")
        _preset.value = presets.open
        reference.addListener { r -> _testRx.value = (listOf(r) + _testRx.value).take(100); _dataVersion.value++ }
        reference.addStateListener { _ref.value = it }
        if (recoveredOnStart > 0) event("Recovered $recoveredOnStart session(s) left open by a previous run (marked interrupted)")
    }

    fun source(): Source = if (radio.linkKind == LinkKind.SIMULATED) Source.SIMULATED else Source.LOCAL_RF

    // ---- connection management with automatic reconnect ----
    private var linkFactory: (() -> RadioLink)? = null
    private var reconnectAtMono: Long? = null
    private var backoffMs = 5_000L
    private var switching = false
    private val _wanted = MutableStateFlow(false)
    /** True from the user's Connect until their Disconnect (including while reconnecting). Drives Android's foreground service. */
    val connectionWanted: StateFlow<Boolean> = _wanted
    val reconnectInS: Int? get() = reconnectAtMono?.let { ((it - clock.monotonicMs() + 999) / 1000).toInt().coerceAtLeast(0) }

    /** Connect a single-use link with no automatic reconnect (tests, one-off connections). */
    fun connect(link: RadioLink) { linkFactory = null; reconnectAtMono = null; _wanted.value = true; switchTo(link) }

    /** Connect and keep reconnecting (5 s doubling to 60 s) after unexpected drops until [disconnect]. */
    fun connect(factory: () -> RadioLink) {
        linkFactory = factory; reconnectAtMono = null; backoffMs = 5_000; _wanted.value = true
        switchTo(factory())
    }

    fun disconnect() { linkFactory = null; reconnectAtMono = null; _wanted.value = false; switchTo(null) }

    private fun switchTo(link: RadioLink?) {
        switching = true
        radioGps.reset(); _radioPosition.value = null
        try { if (link == null) radio.disconnect() else radio.connect(link) } finally { switching = false }
    }

    fun onFix(f: Fix) {
        logger.onFix(f)
        _fix.value = logger.lastFix
        _stats.value = logger.stats
    }

    fun onAppBackground() = ab.onAppBackground()

    fun tick() {
        checkTiming()
        val due = reconnectAtMono
        val f = linkFactory
        if (due != null && f != null && clock.monotonicMs() >= due) {
            reconnectAtMono = null
            event("Reconnecting…")
            try { switchTo(f()) } catch (e: Exception) { scheduleReconnect("reconnect failed: ${e.message}") }
        }
        radio.tick()
        // Re-read the radio's own full-precision position once a minute (local phone API only, no RF) while radio GPS is
        // in use, and also while the radio has not reported any position yet, so a GPS that gets its first fix after
        // connecting is picked up within a minute instead of waiting for the radio's next position broadcast.
        if ((radioGps.isEnabled || _radioPosition.value == null) && radio.isReady && clock.monotonicMs() - lastOwnInfoPollMono >= 60_000) {
            lastOwnInfoPollMono = clock.monotonicMs()
            radio.requestOwnInfo()
        }
        radioGps.tick()
        ab.tick()
        reference.tick()
        presets.tick()
        val ws = windowStart
        val now = clock.wallMs()
        if (ws != null && now - lastWindowWrite >= 30_000) {
            store.insertWindow(LoggingWindow(ws, now, null, radio.info.myNodeNum)); lastWindowWrite = now
        }
    }

    fun setLoggerConfig(c: LoggerConfig) { logger.config = c }

    /**
     * Timestamp integrity: ticks are expected about every second. A long monotonic gap means the app
     * was suspended (Android Doze, OS sleep); a wall-clock delta that disagrees with the monotonic delta
     * means the system clock was changed. Both are recorded and exported with the survey data.
     */
    private fun checkTiming() {
        val mono = clock.monotonicMs(); val wall = clock.wallMs()
        val lm = lastTickMono; val lw = lastTickWall
        lastTickMono = mono; lastTickWall = wall
        if (lm == null || lw == null) return
        val gap = mono - lm
        val jump = (wall - lw) - gap
        val sid = logger.session?.id
        if (gap > TICK_GAP_MS) {
            if (primary) store.insertClockEvent(meshsurvey.core.model.ClockEvent(wallMs = wall, kind = meshsurvey.core.model.ClockEventKind.TICK_GAP, magnitudeMs = gap, sessionId = sid))
            _timing.value = _timing.value.let { it.copy(tickGaps = it.tickGaps + 1, longestGapMs = maxOf(it.longestGapMs, gap)) }
            event("App was suspended for ${gap / 1000} s" + if (sid != null) " during logging (receptions in that time may be missing)" else "")
        }
        if (kotlin.math.abs(jump) > CLOCK_JUMP_MS) {
            if (primary) store.insertClockEvent(meshsurvey.core.model.ClockEvent(wallMs = wall, kind = meshsurvey.core.model.ClockEventKind.CLOCK_JUMP, magnitudeMs = jump, sessionId = sid))
            _timing.value = _timing.value.let { it.copy(clockJumps = it.clockJumps + 1, lastJumpMs = jump) }
            event("System clock changed by ${jump / 1000} s; timestamps before and after are not directly comparable")
        }
    }

    private fun scheduleReconnect(why: String) {
        reconnectAtMono = clock.monotonicMs() + backoffMs
        event("Connection lost ($why); retrying in ${backoffMs / 1000} s")
        backoffMs = (backoffMs * 2).coerceAtMost(60_000)
    }

    fun event(s: String) { _events.value = (listOf(s) + _events.value).take(200) }

    // ---------------- RadioListener ----------------

    override fun onRadioState(state: RadioState, detail: String) {
        val wasReady = logger.radioReady
        if (state == RadioState.READY && !wasReady) {
            logger.radioReady = true
            // this radio's own remembered profile, if one was set for its node number
            store.getMeta(profileKey(radio.info.myNodeNum))?.let { _activeProfileId.value = it.ifEmpty { null } }
            val s = logger.startSession(radio.info, source(), _activeProfileId.value)
            windowStart = clock.wallMs(); lastWindowWrite = 0
            store.insertWindow(LoggingWindow(windowStart!!, clock.wallMs(), null, radio.info.myNodeNum))
            event("Radio ready (${radio.info.preset ?: "preset unknown"}); logging session ${s.id.take(6)} started" +
                if (source() == Source.SIMULATED) " — SIMULATED DATA" else "")
        } else if (state != RadioState.READY && wasReady) {
            logger.radioReady = false
            windowStart?.let { store.insertWindow(LoggingWindow(it, clock.wallMs(), null, radio.info.myNodeNum)) }
            windowStart = null
            logger.endSession(if (state == RadioState.DISCONNECTED) SessionStatus.ENDED else SessionStatus.INTERRUPTED)
            event("Radio ${state.name.lowercase()}: $detail; logging session closed")
        }
        if (state == RadioState.READY) { backoffMs = 5_000; presets.onConfig(radio.info) }
        if ((state == RadioState.DISCONNECTED || state == RadioState.FAILED) && !switching && linkFactory != null && reconnectAtMono == null)
            scheduleReconnect(detail)
        ab.onRadioState(state)
        _radio.value = RadioStatus(state, detail, radio.info)
    }

    override fun onRadioInfo(info: RadioInfo) { _radio.value = _radio.value.copy(info = info) }

    override fun onQueueStatus(res: Int, free: Int, maxLen: Int, meshPacketId: Long) = ab.onQueueStatus(res, free, maxLen, meshPacketId)

    override fun onOwnPosition(pos: PositionInfo, fromConfig: Boolean) {
        _radioPosition.value = pos
        radioGps.onReport(pos, fromConfig)
    }

    private fun updateMetrics(m: meshsurvey.core.proto.DeviceMetricsInfo) {
        if (m.channelUtilization == null && m.airUtilTx == null && m.batteryLevel == null && m.voltage == null) return
        val prev = _metrics.value
        _metrics.value = RadioMetrics(m.channelUtilization?.toDouble() ?: prev?.channelUtilPct, m.airUtilTx?.toDouble() ?: prev?.airUtilTxPct,
            clock.wallMs(), m.batteryLevel ?: prev?.batteryLevel, m.voltage?.toDouble() ?: prev?.voltage)
    }

    override fun onNode(node: NodeRecord) {
        if (node.num == radio.info.myNodeNum) node.metrics?.let { updateMetrics(it) }
        _nodes.value = radio.nodes.toMap()
        if (node.longName != null || node.shortName != null)
            store.upsertNodeName(node.num, node.longName, node.shortName, node.role?.let { meshsurvey.core.proto.MeshEnums.role(it) }, clock.wallMs())
    }

    override fun onPacket(rx: ReceivedPacket) {
        val p = rx.packet
        if (p.from == radio.info.myNodeNum && p.portnum == meshsurvey.core.proto.Port.TELEMETRY)
            p.payload?.let { pl -> runCatching { meshsurvey.core.proto.MeshProto.decodeTelemetryDeviceMetrics(pl) }.getOrNull()?.let { updateMetrics(it) } }
        logger.onPacket(rx, radio.info)
        ab.onPacket(rx)
        reference.onPacket(rx, radio.info.myNodeNum)
        _stats.value = logger.stats
    }

    override fun onDebugText(text: String) { if (text.isNotBlank()) event("radio: ${text.take(160)}") }
}
