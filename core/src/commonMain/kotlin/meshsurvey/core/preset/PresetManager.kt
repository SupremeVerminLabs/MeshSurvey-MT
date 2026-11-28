package meshsurvey.core.preset

import meshsurvey.core.model.PresetChange
import meshsurvey.core.model.PresetChangeStatus
import meshsurvey.core.proto.MeshEnums
import meshsurvey.core.proto.MeshProto
import meshsurvey.core.proto.Wire
import meshsurvey.core.proto.f
import meshsurvey.core.radio.FrequencyCalc
import meshsurvey.core.radio.FrequencyInfo
import meshsurvey.core.radio.Presets
import meshsurvey.core.radio.RadioClient
import meshsurvey.core.radio.RadioInfo
import meshsurvey.core.radio.RadioState
import meshsurvey.core.radio.Regions
import meshsurvey.core.radio.TestBudget
import meshsurvey.core.store.Store
import meshsurvey.core.util.Clock

/** What a temporary preset change would do. Shown to the user in full before anything is sent. */
data class PresetPlan(
    val targetPreset: Int,
    val targetName: String,
    val currentName: String?,
    val originalRaw: ByteArray,
    val tempRaw: ByteArray,
    val frequencyBefore: FrequencyInfo,
    val frequencyAfter: FrequencyInfo,
    val budgetAcked: TestBudget?,
    val budgetDirect: TestBudget?,
    val warnings: List<String>,
    val blockers: List<String>,
) {
    val canApply get() = blockers.isEmpty()
}

/**
 * Temporary modem-preset changes for testing. Rules:
 *  - Nothing is sent without an explicit [apply] call made from a confirmation dialog showing the [PresetPlan].
 *  - Only use_preset (1) and modem_preset (2) are changed; every other LoRaConfig field is preserved byte-for-byte.
 *  - The exact original LoRaConfig bytes are stored before sending and are what [restore] sends back.
 *  - The change and its restore are confirmed by re-reading the radio's config; unconfirmed states are shown, never assumed.
 *  - An open change survives app restarts and is surfaced on the next connection.
 */
class PresetManager(
    private val store: Store,
    private val radio: RadioClient,
    private val clock: Clock,
    private val backups: ConfigBackupStore = MemoryBackupStore(),
) {
    companion object {
        const val CONFIRM_TIMEOUT_MS = 180_000L
        /** If the radio applies the change without rebooting, re-read config after this delay. */
        const val REFRESH_AFTER_MS = 8_000L
        /** Firmware range whose preset/region/admin behaviour was read from source (see docs/FIRMWARE_VERIFICATION.md). */
        val MIN_FIRMWARE = 2 to 3
        val MAX_FIRMWARE = 2 to 7

        private val LORA_FIELD_NAMES = mapOf(1 to "use_preset", 2 to "modem_preset", 3 to "bandwidth", 4 to "spread_factor",
            5 to "coding_rate", 6 to "frequency_offset", 7 to "region", 8 to "hop_limit", 9 to "tx_enabled", 10 to "tx_power",
            11 to "channel_num (frequency slot)", 12 to "override_duty_cycle", 13 to "sx126x_rx_boosted_gain", 14 to "override_frequency",
            15 to "pa_fan_disabled", 103 to "ignore_incoming", 104 to "ignore_mqtt", 105 to "config_ok_to_mqtt", 106 to "fem_lna_mode",
            107 to "serial_hal_only")

        fun fieldName(n: Int) = LORA_FIELD_NAMES[n] ?: "field $n"

        fun firmwareVersion(fw: String?): Pair<Int, Int>? {
            val m = Regex("^(\\d+)\\.(\\d+)").find(fw ?: return null) ?: return null
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }

        private fun cmp(a: Pair<Int, Int>, b: Pair<Int, Int>) = compareValuesBy(a, b, { it.first }, { it.second })
        fun firmwareSupported(v: Pair<Int, Int>) = cmp(v, MIN_FIRMWARE) >= 0 && cmp(v, MAX_FIRMWARE) <= 0

        /** Exact manual restore steps. Valid because only use_preset/modem_preset are ever changed and custom configs are refused. */
        fun manualRestoreText(c: PresetChange): String {
            val l = Wire.parse(c.originalRaw)
            val preset = MeshEnums.preset(l.f(2)?.int ?: 0)
            return "Set the radio's LoRa modem preset back to $preset (\"Use modem preset\" on). In the official app: Settings → LoRa → " +
                "Modem preset = $preset. CLI: meshtastic --set lora.use_preset true --set lora.modem_preset $preset. " +
                "No other LoRa setting was changed by MeshSurvey."
        }
    }

    private val listeners = ArrayList<(PresetChange?) -> Unit>()
    fun addListener(l: (PresetChange?) -> Unit) { listeners += l }
    private var refreshAtMono: Long? = null

    /** The most recent change that is not yet confirmed restored, for any radio. */
    val open: PresetChange? get() = store.presetChanges().lastOrNull { it.isOpen }

    fun openFor(node: Long?): PresetChange? = node?.let { n -> store.presetChanges().lastOrNull { it.isOpen && it.nodeNum == n } }

    /** Blocks test sending while the radio configuration is in flux. */
    fun sendBlocker(): String? {
        val o = openFor(radio.info.myNodeNum) ?: return null
        return when (o.status) {
            PresetChangeStatus.APPLYING -> "Waiting for the radio to confirm the ${o.targetPreset} test preset"
            PresetChangeStatus.RESTORING -> "Waiting for the radio to confirm the restored configuration"
            PresetChangeStatus.APPLY_UNCONFIRMED, PresetChangeStatus.RESTORE_UNCONFIRMED -> "Radio configuration unconfirmed — check it before testing"
            else -> null
        }
    }

    fun plan(target: Int): PresetPlan {
        val info = radio.info
        val blockers = ArrayList<String>()
        val warnings = ArrayList<String>()
        val raw = info.loraRaw
        val after = FrequencyCalc.from(info.copy(usePreset = true, modemPreset = target))
        val region = info.regionCode?.let { Regions.table[it] }
        if (radio.state != RadioState.READY) blockers += "Radio is not connected"
        if (raw == null) blockers += "The radio's LoRa configuration has not been received"
        if (info.myNodeNum == null) blockers += "Local node number unknown"
        openFor(info.myNodeNum)?.let { blockers += "A temporary preset (${it.targetPreset}) is still open — restore it first" }
        val targetName = MeshEnums.preset(target)
        val targetParams = Presets.params(target)
        if (targetParams == null) blockers += "$targetName parameters are not known for this firmware; not offered for testing"
        if (info.usePreset == true && info.modemPreset == target) blockers += "The radio is already on $targetName"
        // Refuse configurations where a preset switch is unsupported or its result would be ambiguous.
        val fw = firmwareVersion(info.firmware)
        when {
            fw == null -> blockers += "Firmware version unknown; preset switching is only offered for firmware 2.3–2.7"
            !firmwareSupported(fw) -> blockers += "Firmware ${info.firmware}: preset switching is only verified for 2.3–2.7 (2.8 changes regions and presets)"
        }
        if (info.usePreset != true) blockers += "The radio uses a custom LoRa configuration (use_preset off). Switching to a preset would " +
            "replace custom bandwidth/SF/CR; change it yourself in the official app if intended"
        if ((info.overrideFrequencyMhz ?: 0.0) > 0) blockers += "The radio uses a frequency override (${info.overrideFrequencyMhz} MHz). A different " +
            "bandwidth around a fixed frequency may leave the band; not changed automatically"
        if (info.txEnabled == false) blockers += "Transmit is disabled in the radio's LoRa config"
        if (info.regionCode == null || info.regionCode == 0) blockers += "Region is UNSET"
        else if (region == null) blockers += "Region ${info.region} is not in this app's verified table"
        if (region != null && targetParams != null && (region.endMhz - region.startMhz) < targetParams.bandwidthKHz / 1000.0)
            blockers += "$targetName (${targetParams.bandwidthKHz.toInt()} kHz) does not fit region ${region.name}; the firmware would silently fall back to LongFast"
        if (after.source == meshsurvey.core.radio.FreqSource.UNKNOWN) blockers += "Resulting frequency cannot be determined (${after.note})"
        val slot = info.channelNum ?: 0
        if (slot > 0 && after.numSlots != null && slot > after.numSlots)
            blockers += "Frequency slot $slot does not exist with $targetName (${after.numSlots} slots); the firmware would wrap it to slot ${after.slot}"
        if ((info.frequencyOffsetMhz ?: 0.0) != 0.0) warnings += "A frequency offset of ${info.frequencyOffsetMhz} MHz is configured and is kept unchanged."
        if (backups is MemoryBackupStore) warnings += "No durable backup location is available on this platform; the backup is kept in the database only."

        val tempRaw = raw?.let { Wire.patchVarints(it, mapOf(1 to 1L, 2 to target.toLong())) } ?: ByteArray(0)
        val before = FrequencyCalc.from(info)

        warnings += "Your radio leaves its normal mesh while on $targetName: nodes on ${info.preset ?: "your current preset"} will not hear it, and it will not hear them."
        warnings += "Device B must be switched to exactly the same preset (and frequency slot / channel) or the tests cannot be received."
        warnings += "Changing the preset makes the radio reboot. The app reconnects automatically and confirms the new setting."
        if ((info.primaryChannelName ?: "").isEmpty() && (info.channelNum ?: 0) == 0)
            warnings += "Your primary channel uses the default name, so the frequency slot is derived from the preset name and will change " +
                "(${before.label} → ${after.label}). The default-channel encryption hash also changes with the name."
        if (targetParams != null && targetParams.bandwidthKHz >= 500.0)
            warnings += "$targetName uses ${targetParams.bandwidthKHz.toInt()} kHz bandwidth, which is not permitted in every region. Check your local regulations before transmitting."
        if (region != null && region.dutyCyclePct < 100)
            warnings += "${region.name} has a ${region.dutyCyclePct} % duty-cycle limit, which the firmware enforces (tests may be refused with DUTY_CYCLE_LIMIT)."

        return PresetPlan(target, targetName, info.preset, raw ?: ByteArray(0), tempRaw, before, after,
            targetParams?.let { TestBudget.of(it, true) }, targetParams?.let { TestBudget.of(it, false) }, warnings, blockers)
    }

    /** Sends the change described by [plan]. Call only after the user confirmed the plan. Returns an error or null. */
    fun apply(plan: PresetPlan, autoRestore: Boolean): String? {
        if (!plan.canApply) return plan.blockers.first()
        radio.transmitBlock()?.let { return "$it: temporary preset changes are for active tests" }
        val me = radio.info.myNodeNum ?: return "Local node number unknown"
        // the config must not have changed since the plan was made
        if (!radio.info.loraRaw.contentEqualsNullable(plan.originalRaw)) return "The radio's configuration changed; review the plan again"
        val c = PresetChange(nodeNum = me, createdMs = clock.wallMs(), originalRaw = plan.originalRaw, tempRaw = plan.tempRaw,
            originalPreset = plan.currentName, targetPreset = plan.targetName, status = PresetChangeStatus.APPLYING,
            statusMs = clock.wallMs(), autoRestore = autoRestore)
        // 1) durable file backup, 2) database row, 3) only then the radio. Any failure: nothing is sent.
        try { backups.save(c) } catch (e: Exception) {
            return "Could not write the configuration backup (${e.message}); nothing was changed"
        }
        val id = store.insertPresetChange(c)
        if (!radio.sendAdminToLocalNode(MeshProto.adminSetLora(plan.tempRaw))) {
            store.updatePresetChange(id, PresetChangeStatus.RESTORED, clock.wallMs(), "not sent: radio did not accept the admin message")
            backups.markResolved(c.nodeNum, c.createdMs)
            notifyChanged()
            return "The radio did not accept the configuration message; nothing was changed"
        }
        refreshAtMono = clock.monotonicMs() + REFRESH_AFTER_MS
        notifyChanged()
        return null
    }

    /** Sends the exact original LoRa config back. Returns an error or null. */
    fun restore(): String? {
        val me = radio.info.myNodeNum ?: return "Radio not connected"
        val c = openFor(me) ?: return "Nothing to restore for this radio"
        if (radio.state != RadioState.READY) return "Radio not connected"
        if (!radio.sendAdminToLocalNode(MeshProto.adminSetLora(c.originalRaw))) return "The radio did not accept the restore message"
        store.updatePresetChange(c.id, PresetChangeStatus.RESTORING, clock.wallMs(), null)
        refreshAtMono = clock.monotonicMs() + REFRESH_AFTER_MS
        notifyChanged()
        return null
    }

    /** Call when the radio has (re)sent its configuration (state READY). Confirms or flags open changes. */
    fun onConfig(info: RadioInfo) {
        val c = openFor(info.myNodeNum) ?: return
        val raw = info.loraRaw ?: return
        val now = clock.wallMs()
        val onOriginal = Wire.sameFields(raw, c.originalRaw)
        val onTemp = Wire.sameFields(raw, c.tempRaw)
        val target = MeshEnums.presetNumber(c.targetPreset)
        val l = Wire.parse(raw)
        val presetApplied = (l.f(1)?.bool ?: false) && (l.f(2)?.int ?: 0) == target
        fun set(st: PresetChangeStatus, note: String?) {
            store.updatePresetChange(c.id, st, now, note)
            if (st == PresetChangeStatus.RESTORED) backups.markResolved(c.nodeNum, c.createdMs)
            refreshAtMono = null
            notifyChanged()
        }
        when (c.status) {
            PresetChangeStatus.APPLYING, PresetChangeStatus.APPLY_UNCONFIRMED -> when {
                onTemp -> set(PresetChangeStatus.ACTIVE, null)
                presetApplied -> set(PresetChangeStatus.APPLY_UNCONFIRMED, "the radio applied ${c.targetPreset} but also changed " +
                    Wire.diffFields(raw, c.tempRaw).joinToString { fieldName(it) } + " — check the radio, then restore")
                onOriginal && c.status == PresetChangeStatus.APPLY_UNCONFIRMED -> set(PresetChangeStatus.RESTORED, "radio is on its original configuration")
                else -> {}
            }
            PresetChangeStatus.RESTORING, PresetChangeStatus.RESTORE_UNCONFIRMED, PresetChangeStatus.ACTIVE -> when {
                onOriginal -> set(PresetChangeStatus.RESTORED, if (c.status == PresetChangeStatus.ACTIVE) "original configuration detected" else null)
                !onTemp -> set(if (c.status == PresetChangeStatus.ACTIVE) PresetChangeStatus.ACTIVE else PresetChangeStatus.RESTORE_UNCONFIRMED,
                    "radio config differs from the original in " + Wire.diffFields(raw, c.originalRaw).joinToString { fieldName(it) })
                else -> {}
            }
            PresetChangeStatus.RESTORED -> {}
        }
    }

    /**
     * Startup reconciliation: re-imports backups the database lost (e.g. database deleted or corrupted)
     * and resolves backup files for changes the database already shows as restored.
     */
    fun reconcileBackups(): Int {
        val inDb = store.presetChanges().associateBy { it.nodeNum to it.createdMs }
        var recovered = 0
        for (b in backups.loadAll()) {
            if (b.resolved) continue
            val db = inDb[b.change.nodeNum to b.change.createdMs]
            when {
                // the true state is unknown until the radio's config is read again
                db == null -> { store.insertPresetChange(b.change.copy(status = PresetChangeStatus.APPLY_UNCONFIRMED,
                    statusMs = b.change.createdMs, note = "recovered from backup file")); recovered++ }
                db.status == PresetChangeStatus.RESTORED -> backups.markResolved(b.change.nodeNum, b.change.createdMs)
            }
        }
        if (recovered > 0) notifyChanged()
        return recovered
    }

    fun tick() {
        val r = refreshAtMono
        if (r != null && clock.monotonicMs() >= r && radio.state == RadioState.READY) {
            refreshAtMono = null
            radio.refreshConfig() // in case the firmware applied it without a reboot
        }
        val c = openFor(radio.info.myNodeNum) ?: open ?: return
        val age = clock.wallMs() - c.statusMs
        if (age < CONFIRM_TIMEOUT_MS) return
        when (c.status) {
            PresetChangeStatus.APPLYING -> { store.updatePresetChange(c.id, PresetChangeStatus.APPLY_UNCONFIRMED, clock.wallMs(), "no confirmation within ${CONFIRM_TIMEOUT_MS / 1000} s"); notifyChanged() }
            PresetChangeStatus.RESTORING -> { store.updatePresetChange(c.id, PresetChangeStatus.RESTORE_UNCONFIRMED, clock.wallMs(), "no confirmation within ${CONFIRM_TIMEOUT_MS / 1000} s"); notifyChanged() }
            else -> {}
        }
    }

    private fun notifyChanged() { val o = open; listeners.forEach { it(o) } }

    /** Decoded summary of a LoRa config for display (preset + slot). */
    fun describe(raw: ByteArray): String {
        val l = Wire.parse(raw)
        val use = l.f(1)?.bool ?: false
        val preset = l.f(2)?.int ?: 0
        return (if (use) MeshEnums.preset(preset) else "CUSTOM") + (l.f(11)?.int?.let { " · slot $it" } ?: "")
    }
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray): Boolean = this != null && this.contentEquals(other)
