package meshsurvey.core.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import meshsurvey.core.model.EquipmentProfile
import meshsurvey.core.model.Fix
import meshsurvey.core.preset.ConfigBackupStore
import meshsurvey.core.preset.MemoryBackupStore
import meshsurvey.core.store.SqlStore
import meshsurvey.core.util.Clock

/** State shared by every engine of one app instance. */
class SharedState(private val store: SqlStore) {
    private val _profiles = MutableStateFlow(store.profiles())
    val profiles: StateFlow<List<EquipmentProfile>> = _profiles
    fun refreshProfiles() { _profiles.value = store.profiles() }

    private val _rxOnly = MutableStateFlow(store.getMeta("rx_only") == "1")
    /**
     * RX-only: this app makes no transmissions at all (no tests, no temporary preset changes) on any radio.
     * Radios still transmit on their own (node info, telemetry, position, relaying) as configured in their firmware.
     */
    val rxOnly: StateFlow<Boolean> = _rxOnly
    fun setRxOnly(on: Boolean) { _rxOnly.value = on; store.setMeta("rx_only", if (on) "1" else "0") }
}

/**
 * All radios connected to this app. Each radio has its own [SurveyEngine] and logs as an independent receiver
 * (own sessions, own logging windows, own preset and equipment profile), into the same local database.
 * The station's position source (GPS, Windows location, fixed position, or one radio's GPS) applies to all of
 * them, since they are at the same place.
 */
class Station(
    val store: SqlStore,
    val clock: Clock,
    val edition: Edition = Edition.LITE,
    val appVersion: String = "0.1.0",
    val configBackups: ConfigBackupStore = MemoryBackupStore(),
    /**
     * Opens the separate database used by simulated radios. Simulated data never enters [store], so it cannot be
     * exported or shared as real measurements. Null = an in-memory-only simulation is not available on this platform.
     */
    private val simStoreFactory: (() -> SqlStore)? = null,
) {
    val shared = SharedState(store)
    val primary = SurveyEngine(store, clock, edition, appVersion, configBackups = configBackups, primary = true, shared = shared)
    private val _receivers = MutableStateFlow(listOf(primary))

    /** A radio's position broadcast heard by another connected radio carries its DOP and satellites: pass them on. */
    private fun wire(e: SurveyEngine) = e.apply {
        onPositionHeard = { from, pos -> _receivers.value.firstOrNull { it !== e && it.radio.info.myNodeNum == from }?.radioGps?.onQualityHeard(pos) }
    }
    init { wire(primary) }
    val receivers: StateFlow<List<SurveyEngine>> = _receivers

    /** A new, unconnected engine for one more radio. */
    fun addReceiver(): SurveyEngine {
        val e = wire(SurveyEngine(store, clock, edition, appVersion, configBackups = configBackups, primary = false, shared = shared))
        _receivers.value = _receivers.value + e
        return e
    }

    private var simStore: SqlStore? = null

    /** An engine for a simulated radio, writing only to the separate simulation database. */
    fun addSimulatedReceiver(): SurveyEngine {
        val f = simStoreFactory ?: throw IllegalStateException("Simulation is not available in this build")
        val sStore = simStore ?: f().also { simStore = it }
        val e = SurveyEngine(sStore, clock, edition, appVersion, primary = false, shared = SharedState(sStore))
        _receivers.value = _receivers.value + e
        return e
    }

    /** True for engines writing to the simulation database. */
    fun isSimulated(e: SurveyEngine) = e.store !== store

    /** Disconnects and forgets an additional receiver (the primary engine always stays). */
    fun removeReceiver(e: SurveyEngine) {
        if (e === primary) { e.disconnect(); return }
        e.disconnect()
        _receivers.value = _receivers.value - e
    }

    /** Call about once per second. */
    fun tick() = _receivers.value.forEach { it.tick() }

    /** A position for the whole station: every receiver logs with it. */
    fun onFix(f: Fix) = _receivers.value.forEach { it.onFix(f) }

    fun onAppBackground() = _receivers.value.forEach { it.onAppBackground() }

    /** Turns RX-only on/off for every radio; turning it on stops any running automated test session. */
    fun setRxOnly(on: Boolean) {
        shared.setRxOnly(on)
        if (on) _receivers.value.forEach { e -> if (e.ab.state.autoRunning) e.ab.stop("RX-only mode turned on") }
    }

    /** Is any radio connected or reconnecting? (keeps Android's foreground service alive) */
    fun anyWanted(): Boolean = _receivers.value.any { it.connectionWanted.value }
}
