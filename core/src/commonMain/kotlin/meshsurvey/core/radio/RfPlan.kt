package meshsurvey.core.radio

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/**
 * LoRa parameters, nominal frequency and airtime, replicating stock firmware logic.
 *
 * Source (meshtastic/firmware, master branch, read 2026-11-25):
 *  - src/mesh/MeshRadio.h          modemPresetToParams()  (bandwidth / SF / CR per preset)
 *  - src/mesh/RadioInterface.cpp   region table, applyModemConfig() (slot + frequency), djb2 hash()
 *  - src/mesh/RadioInterface.h     preambleLength = 16, 16-byte packet header
 *  - src/mesh/Channels.cpp         empty primary channel name -> preset display name ("Custom" if !use_preset)
 *  - src/DisplayFormatters.cpp     preset display names
 * Firmware 2.8 reworks regions/presets; values here are the pre-2.8 tables. See docs/FREQUENCY.md.
 */
data class LoraParams(val bandwidthKHz: Double, val spreadingFactor: Int, val codingRateDenom: Int) {
    val label get() = "BW ${fmtBw(bandwidthKHz)} kHz · SF$spreadingFactor · CR 4/$codingRateDenom"
    private fun fmtBw(v: Double) = if (v == floor(v)) v.toInt().toString() else v.toString()
}

object Presets {
    /** Presets this app has firmware-verified parameters for (0–9). 10–16 exist in the protobufs but not in firmware master. */
    fun params(preset: Int, wideLora: Boolean = false): LoraParams? {
        val base = when (preset) {
            8 -> LoraParams(500.0, 7, 5)    // SHORT_TURBO
            6 -> LoraParams(250.0, 7, 5)    // SHORT_FAST
            5 -> LoraParams(250.0, 8, 5)    // SHORT_SLOW
            4 -> LoraParams(250.0, 9, 5)    // MEDIUM_FAST
            3 -> LoraParams(250.0, 10, 5)   // MEDIUM_SLOW
            9 -> LoraParams(500.0, 11, 8)   // LONG_TURBO
            7 -> LoraParams(125.0, 11, 8)   // LONG_MODERATE
            1 -> LoraParams(125.0, 12, 8)   // LONG_SLOW (deprecated)
            0 -> LoraParams(250.0, 11, 5)   // LONG_FAST
            2 -> LoraParams(250.0, 11, 5)   // VERY_LONG_SLOW: falls to the firmware default branch (LONG_FAST params)
            else -> return null
        }
        return if (wideLora) base.copy(bandwidthKHz = base.bandwidthKHz * 3.25) else base
    }

    /** DisplayFormatters::getModemPresetDisplayName(preset, false, usePreset) — the slot-hash input for a default channel name. */
    fun displayName(preset: Int, usePreset: Boolean): String {
        if (!usePreset) return "Custom"
        return when (preset) {
            8 -> "ShortTurbo"; 5 -> "ShortSlow"; 6 -> "ShortFast"; 3 -> "MediumSlow"; 4 -> "MediumFast"
            1 -> "LongSlow"; 0 -> "LongFast"; 9 -> "LongTurbo"; 7 -> "LongMod"
            else -> "Invalid" // firmware default case
        }
    }

    /** Presets offered for manual test selection (firmware-verified parameters only). */
    val selectable = listOf(8, 6, 5, 4, 3, 9, 0, 7)
}

data class RegionDef(val code: Int, val name: String, val startMhz: Float, val endMhz: Float, val spacingMhz: Float,
                     val dutyCyclePct: Int, val powerLimitDbm: Int, val wideLora: Boolean)

object Regions {
    // RadioInterface.cpp RDEF table (pre-2.8). Codes from config.proto RegionCode.
    val table: Map<Int, RegionDef> = listOf(
        RegionDef(1, "US", 902.0f, 928.0f, 0f, 100, 30, false),
        RegionDef(2, "EU_433", 433.0f, 434.0f, 0f, 10, 10, false),
        RegionDef(3, "EU_868", 869.4f, 869.65f, 0f, 10, 27, false),
        RegionDef(4, "CN", 470.0f, 510.0f, 0f, 100, 19, false),
        RegionDef(5, "JP", 920.5f, 923.5f, 0f, 100, 13, false),
        RegionDef(6, "ANZ", 915.0f, 928.0f, 0f, 100, 30, false),
        RegionDef(22, "ANZ_433", 433.05f, 434.79f, 0f, 100, 14, false),
        RegionDef(9, "RU", 868.7f, 869.2f, 0f, 100, 20, false),
        RegionDef(7, "KR", 920.0f, 923.0f, 0f, 100, 23, false),
        RegionDef(8, "TW", 920.0f, 925.0f, 0f, 100, 27, false),
        RegionDef(10, "IN", 865.0f, 867.0f, 0f, 100, 30, false),
        RegionDef(11, "NZ_865", 864.0f, 868.0f, 0f, 100, 36, false),
        RegionDef(12, "TH", 920.0f, 925.0f, 0f, 10, 27, false),
        RegionDef(14, "UA_433", 433.0f, 434.7f, 0f, 10, 10, false),
        RegionDef(15, "UA_868", 868.0f, 868.6f, 0f, 1, 14, false),
        RegionDef(16, "MY_433", 433.0f, 435.0f, 0f, 100, 20, false),
        RegionDef(17, "MY_919", 919.0f, 924.0f, 0f, 100, 27, false),
        RegionDef(18, "SG_923", 917.0f, 925.0f, 0f, 100, 20, false),
        RegionDef(19, "PH_433", 433.0f, 434.7f, 0f, 100, 10, false),
        RegionDef(20, "PH_868", 868.0f, 869.4f, 0f, 100, 14, false),
        RegionDef(21, "PH_915", 915.0f, 918.0f, 0f, 100, 24, false),
        RegionDef(23, "KZ_433", 433.075f, 434.775f, 0f, 100, 10, false),
        RegionDef(24, "KZ_863", 863.0f, 868.0f, 0f, 100, 30, false),
        RegionDef(25, "NP_865", 865.0f, 868.0f, 0f, 100, 30, false),
        RegionDef(26, "BR_902", 902.0f, 907.5f, 0f, 100, 30, false),
        RegionDef(13, "LORA_24", 2400.0f, 2483.5f, 0f, 100, 10, true),
    ).associateBy { it.code }
}

enum class FreqSource {
    /** The radio's config contains an explicit override_frequency (plus any frequency_offset). */
    CONFIGURED_OVERRIDE,
    /** Computed here from region + preset + slot + channel name using the firmware formula. Not reported by the radio. */
    CALCULATED,
    UNKNOWN,
}

data class FrequencyInfo(
    val mhz: Double?,
    val source: FreqSource,
    /** 1-based frequency slot as shown in the official apps; null if not applicable. */
    val slot: Int? = null,
    val numSlots: Int? = null,
    val params: LoraParams? = null,
    val note: String = "",
) {
    val label: String get() = when (source) {
        FreqSource.UNKNOWN -> "unknown ($note)"
        FreqSource.CONFIGURED_OVERRIDE -> "${fmt(mhz!!)} MHz (configured override)"
        FreqSource.CALCULATED -> "${fmt(mhz!!)} MHz (calculated, slot $slot of $numSlots)" + if (note.isNotEmpty()) " — $note" else ""
    }
    companion object {
        fun fmt(v: Double): String {
            val r = kotlin.math.round(v * 10_000) / 10_000
            return r.toString()
        }
    }
}

object FrequencyCalc {
    /** djb2, as in RadioInterface.cpp hash(): h = h * 33 + c, 32-bit unsigned. */
    fun djb2(s: String): Long {
        var h = 5381L
        for (b in s.encodeToByteArray()) h = (h * 33 + (b.toInt() and 0xff)) and 0xffffffffL
        return h
    }

    /** Custom bandwidth codes the firmware maps to exact LoRa bandwidths. */
    private fun customBw(bw: Int): Double = when (bw) {
        31 -> 31.25; 62 -> 62.5; 200 -> 203.125; 400 -> 406.25; 800 -> 812.5; 1600 -> 1625.0; else -> bw.toDouble()
    }

    fun from(info: RadioInfo): FrequencyInfo = compute(
        regionCode = info.regionCode, usePreset = info.usePreset, modemPreset = info.modemPreset,
        customBandwidth = info.bandwidth, customSf = info.spreadFactor, customCr = info.codingRate,
        channelNum = info.channelNum, overrideMhz = info.overrideFrequencyMhz, offsetMhz = info.frequencyOffsetMhz,
        primaryChannelName = info.primaryChannelName,
    )

    fun compute(
        regionCode: Int?, usePreset: Boolean?, modemPreset: Int?,
        customBandwidth: Int? = null, customSf: Int? = null, customCr: Int? = null,
        channelNum: Int?, overrideMhz: Double?, offsetMhz: Double?, primaryChannelName: String?,
    ): FrequencyInfo {
        val offset = offsetMhz ?: 0.0
        if (overrideMhz != null && overrideMhz > 0) return FrequencyInfo(overrideMhz + offset, FreqSource.CONFIGURED_OVERRIDE,
            note = if (offset != 0.0) "includes ${offset} MHz offset" else "")
        if (regionCode == null || usePreset == null || modemPreset == null) return FrequencyInfo(null, FreqSource.UNKNOWN, note = "radio config not received")
        val region = Regions.table[regionCode] ?: return FrequencyInfo(null, FreqSource.UNKNOWN,
            note = "region code $regionCode not in this app's (pre-2.8) table")
        var params = if (usePreset) Presets.params(modemPreset, region.wideLora)
            else if (customBandwidth != null && customSf != null && customCr != null) LoraParams(customBw(customBandwidth), customSf, customCr) else null
        params ?: return FrequencyInfo(null, FreqSource.UNKNOWN, note = "preset ${modemPreset} parameters not known for this firmware version")
        val notes = ArrayList<String>()
        val span = region.endMhz - region.startMhz
        var bwK = params.bandwidthKHz.toFloat()
        if (span < bwK / 1000f) {
            // applyModemConfig falls back to LONG_FAST when the region is narrower than the bandwidth
            params = Presets.params(0, region.wideLora)!!
            bwK = params.bandwidthKHz.toFloat()
            notes += "region too narrow for preset; firmware falls back to LongFast parameters (unverified on hardware)"
        }
        // float arithmetic, as in the firmware
        val numChannels = floor((span / (region.spacingMhz + (bwK / 1000f))).toDouble()).toInt()
        if (numChannels <= 0) return FrequencyInfo(null, FreqSource.UNKNOWN, note = "no channel fits the region")
        val slotIndex: Long = if (channelNum != null && channelNum > 0) {
            ((channelNum - 1).toLong() and 0xffffffffL) % numChannels
        } else {
            val name = when {
                primaryChannelName == null -> return FrequencyInfo(null, FreqSource.UNKNOWN, params = params,
                    note = "primary channel name not received and no explicit frequency slot")
                primaryChannelName.isEmpty() -> Presets.displayName(modemPreset, usePreset)
                else -> primaryChannelName
            }
            djb2(name) % numChannels
        }
        val freq = region.startMhz + (bwK / 2000f) + (slotIndex.toFloat() * (bwK / 1000f))
        return FrequencyInfo(freq.toDouble() + offset, FreqSource.CALCULATED, (slotIndex + 1).toInt(), numChannels, params,
            notes.joinToString("; ") + if (offset != 0.0) (if (notes.isEmpty()) "" else "; ") + "includes ${offset} MHz offset" else "")
    }
}

/** Semtech LoRa time-on-air (explicit header, CRC on), as used for airtime budgeting. */
object Airtime {
    const val PREAMBLE_SYMBOLS = 16
    const val MESH_HEADER_BYTES = 16
    /** Data{portnum=256, payload=18 B} ≈ 23 B, plus up to 2 B bitfield. */
    const val TEST_DATA_BYTES = 25
    /** PKI-encrypted DMs add an 8-byte MAC and 4-byte extra nonce. */
    const val PKI_OVERHEAD = 12

    fun ms(payloadBytes: Int, p: LoraParams, preamble: Int = PREAMBLE_SYMBOLS): Double {
        val tSym = 2.0.pow(p.spreadingFactor) / (p.bandwidthKHz * 1000.0) * 1000.0 // ms
        val de = if (tSym > 16.0) 1 else 0
        val num = 8.0 * payloadBytes - 4.0 * p.spreadingFactor + 28 + 16
        val den = 4.0 * (p.spreadingFactor - 2 * de)
        val payloadSymbols = 8 + max(ceil(num / den) * p.codingRateDenom, 0.0)
        return (preamble + 4.25 + payloadSymbols) * tSym
    }

    /** Worst-case on-air size of one test packet (header + data + PKI overhead). */
    val testPacketBytes get() = MESH_HEADER_BYTES + TEST_DATA_BYTES + PKI_OVERHEAD
}

/**
 * Conservative limits for controlled tests. Counts only this radio's own transmissions;
 * relays may add more airtime (flooded copies), which cannot be bounded from the client.
 */
data class TestBudget(
    val params: LoraParams,
    val transmissionsPerTest: Int,
    val airtimePerTransmissionMs: Double,
    val worstCaseMsPerTest: Double,
    val minIntervalS: Int,
    val maxTestsPerSession: Int,
) {
    companion object {
        const val ABSOLUTE_MIN_INTERVAL_S = 30
        /** Worst-case share of airtime test traffic from this radio may use. */
        const val MAX_TEST_DUTY = 0.02
        /** Worst-case total own airtime per session. */
        const val MAX_SESSION_AIRTIME_MS = 60_000.0

        /**
         * With want_ack the firmware makes up to 3 transmissions (NextHopRouter: NUM_RELIABLE_RETX = 3,
         * initial send + 2 retries; the last retry falls back to flooding). Without ACK: exactly 1.
         */
        fun of(params: LoraParams, wantAck: Boolean): TestBudget {
            val tx = if (wantAck) 3 else 1
            val air = Airtime.ms(Airtime.testPacketBytes, params)
            val worst = air * tx
            val minInterval = max(ABSOLUTE_MIN_INTERVAL_S, ceil(worst / 1000.0 / MAX_TEST_DUTY).toInt())
            val maxTests = floor(MAX_SESSION_AIRTIME_MS / worst).toInt().coerceAtLeast(1)
            return TestBudget(params, tx, air, worst, minInterval, maxTests)
        }
    }
}

/**
 * Airtime the radio reports about itself (DeviceMetrics, from its own telemetry/node info):
 * airUtilTx = share of time this radio spent transmitting (all its traffic, firmware's averaging window);
 * channelUtil = share of time the channel was busy (all nodes it can hear). These are REPORTED values,
 * unlike [TestBudget], which is an ESTIMATE of this app's own test packets only.
 */
data class RadioMetrics(
    val channelUtilPct: Double?,
    val airUtilTxPct: Double?,
    val wallMs: Long,
    /** DeviceMetrics.battery_level: 0–100 %, 101 = running on external power (firmware convention). */
    val batteryLevel: Int? = null,
    val voltage: Double? = null,
) {
    /** "87 %, 4.02 V", "external power, 4.20 V", or null when the radio reports neither. */
    val batteryText: String? get() {
        val lvl = batteryLevel?.let { if (it > 100) "external power" else "$it %" }
        val v = voltage?.takeIf { it > 0 }?.let { "${(kotlin.math.round(it * 100) / 100)} V" }
        return listOfNotNull(lvl, v).joinToString(", ").ifEmpty { null }
    }
    fun freshAt(nowMs: Long, maxAgeMs: Long = 20 * 60_000L) = nowMs - wallMs <= maxAgeMs
}
