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
) {
    val shared = SharedState(store)
    val primary = SurveyEngine(store, clock, edition, appVersion, configBackups = configBackups, primary = true, shared = shared)
    private val _receivers = MutableStateFlow(listOf(primary))
    val receivers: StateFlow<List<SurveyEngine>> = _receivers

    /** A new, unconnected engine for one more radio. */
    fun addReceiver(): SurveyEngine {
        val e = SurveyEngine(store, clock, edition, appVersion, configBackups = configBackups, primary = false, shared = shared)
        _receivers.value = _receivers.value + e
        return e
    }

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

    /** Is any radio connected or reconnecting? (keeps Android's foreground service alive) */
    fun anyWanted(): Boolean = _receivers.value.any { it.connectionWanted.value }
}
