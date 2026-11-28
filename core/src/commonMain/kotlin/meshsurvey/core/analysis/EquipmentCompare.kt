package meshsurvey.core.analysis

import meshsurvey.core.map.Grid
import meshsurvey.core.model.EquipmentProfile
import meshsurvey.core.model.Observation
import meshsurvey.core.model.SurveySession
import meshsurvey.core.quality.ConfidenceRules
import kotlin.math.abs

/** A set of measurements to compare: e.g. one antenna, one radio, one installation, or one period. */
data class MeasurementGroup(
    val label: String,
    val observations: List<Observation>,
    val sessions: List<SurveySession>,
    val profiles: List<EquipmentProfile> = emptyList(),
)

data class GroupStats(
    val label: String,
    val total: Int,
    /** Observations usable for comparison (confidence Valid or Limited, with position and signal values). */
    val usable: Int,
    val excludedLowConfidence: Int,
    val senders: Int,
    val snr: Summary?,
    val rssi: Summary?,
    val presets: Set<String>,
    val regions: Set<String>,
    val firmware: Set<String>,
    val hwModels: Set<Int>,
    val firstMs: Long?,
    val lastMs: Long?,
    val loggedHours: Double,
)

/** One place (map cell) and sender heard by both groups, so equipment is compared under like conditions. */
data class Stratum(val cell: Long, val fromNode: Long, val nA: Int, val nB: Int, val snrDiff: Double?, val rssiDiff: Double?)

data class EquipmentComparison(
    val a: GroupStats,
    val b: GroupStats,
    /** Same-place, same-sender pairs with enough samples on both sides. */
    val strata: List<Stratum>,
    /** Median over strata of (A − B), dB. Positive = A received stronger. */
    val matchedSnrDiffDb: Double?,
    val matchedSnrCi95: Pair<Double, Double>?,
    val matchedRssiDiffDb: Double?,
    val matchedRssiCi95: Pair<Double, Double>?,
    /** Unmatched difference of overall medians: shown for completeness only, it mixes places and senders. */
    val naiveSnrDiffDb: Double?,
    val limitations: List<String>,
) {
    val sufficient get() = strata.size >= EquipmentCompare.MIN_STRATA
}

/**
 * Compares two groups of measurements fairly: only observations of the **same sender in the same map cell** are
 * compared, so differences in where and from whom each group listened do not masquerade as equipment differences.
 * Results are in dB (never percentages). The overall-median difference is reported separately and labelled as such.
 */
object EquipmentCompare {
    const val CELL_M = 100
    const val MIN_PER_STRATUM = 3
    const val MIN_STRATA = 5

    fun usable(o: Observation) = ConfidenceRules.classify(o).usableForAggregates && o.lat != null && o.lon != null && (o.snr != null || o.rssi != null)

    fun stats(g: MeasurementGroup): GroupStats {
        val u = g.observations.filter(::usable)
        val sessionsSpan = g.sessions.sumOf { s -> ((s.endedMs ?: s.startedMs) - s.startedMs).coerceAtLeast(0) }
        return GroupStats(
            label = g.label, total = g.observations.size, usable = u.size, excludedLowConfidence = g.observations.size - u.size,
            senders = u.map { it.fromNode }.toSet().size,
            snr = Stats.summary(u.mapNotNull { it.snr }), rssi = Stats.summary(u.mapNotNull { it.rssi?.toDouble() }),
            presets = (u.mapNotNull { it.preset } + g.sessions.mapNotNull { it.preset }).toSet(),
            regions = (u.mapNotNull { it.region } + g.sessions.mapNotNull { it.region }).toSet(),
            firmware = g.sessions.mapNotNull { it.firmware }.toSet(), hwModels = g.sessions.mapNotNull { it.hwModel }.toSet(),
            firstMs = u.minOfOrNull { it.rxMs }, lastMs = u.maxOfOrNull { it.rxMs }, loggedHours = sessionsSpan / 3_600_000.0,
        )
    }

    fun compare(a: MeasurementGroup, b: MeasurementGroup): EquipmentComparison {
        val sa = stats(a); val sb = stats(b)
        val grid = Grid(CELL_M.toDouble())
        fun strata(g: MeasurementGroup) = g.observations.filter(::usable).groupBy { grid.key(it.lat!!, it.lon!!) to it.fromNode }
        val ga = strata(a); val gb = strata(b)
        val st = ga.keys.intersect(gb.keys).mapNotNull { k ->
            val la = ga[k]!!; val lb = gb[k]!!
            if (la.size < MIN_PER_STRATUM || lb.size < MIN_PER_STRATUM) return@mapNotNull null
            val snr = Stats.median(la.mapNotNull { it.snr })?.let { x -> Stats.median(lb.mapNotNull { it.snr })?.let { y -> x - y } }
            val rssi = Stats.median(la.mapNotNull { it.rssi?.toDouble() })?.let { x -> Stats.median(lb.mapNotNull { it.rssi?.toDouble() })?.let { y -> x - y } }
            Stratum(k.first, k.second, la.size, lb.size, snr, rssi)
        }
        val snrD = st.mapNotNull { it.snrDiff }; val rssiD = st.mapNotNull { it.rssiDiff }

        val lim = ArrayList<String>()
        if (st.size < MIN_STRATA) lim += "Only ${st.size} place-and-sender combinations were heard by both groups with at least $MIN_PER_STRATUM " +
            "receptions each (need $MIN_STRATA). The groups listened in different places or to different senders, so no fair comparison is possible."
        if (sa.presets.size > 1 || sb.presets.size > 1 || (sa.presets.isNotEmpty() && sb.presets.isNotEmpty() && sa.presets != sb.presets))
            lim += "Different modem presets (${sa.presets.joinToString().ifEmpty { "?" }} vs ${sb.presets.joinToString().ifEmpty { "?" }}): SNR and range differ by preset."
        if (sa.regions.isNotEmpty() && sb.regions.isNotEmpty() && sa.regions != sb.regions) lim += "Different regions (${sa.regions} vs ${sb.regions})."
        if (sa.firmware.isNotEmpty() && sb.firmware.isNotEmpty() && sa.firmware != sb.firmware) lim += "Different firmware (${sa.firmware.joinToString()} vs ${sb.firmware.joinToString()})."
        if (sa.hwModels.isNotEmpty() && sb.hwModels.isNotEmpty() && sa.hwModels != sb.hwModels) lim += "Different radio models were used; that may be the point of the comparison, but chipset and front-end differences then mix with antenna differences."
        val gapDays = if (sa.lastMs != null && sb.firstMs != null && sb.lastMs != null && sa.firstMs != null)
            minOf(abs(sa.firstMs - sb.lastMs), abs(sb.firstMs - sa.lastMs)) / 86_400_000 else null
        if (gapDays != null && gapDays > 30) lim += "Measurements are $gapDays days apart: foliage, weather and the senders' own setups may have changed."
        if (a.profiles.size > 1 || b.profiles.size > 1) lim += "A group mixes several equipment profiles."
        if (sa.usable < 30 || sb.usable < 30) lim += "Small samples (${sa.usable} and ${sb.usable} usable receptions)."
        if (sa.excludedLowConfidence > sa.total / 3 || sb.excludedLowConfidence > sb.total / 3)
            lim += "Many receptions were excluded as low-confidence (${sa.excludedLowConfidence} and ${sb.excludedLowConfidence})."
        lim += "RSSI/SNR show what reached the receiver; they cannot by themselves prove a defective antenna, impedance mismatch or high SWR."

        return EquipmentComparison(sa, sb, st,
            Stats.median(snrD), Stats.medianCi95(snrD), Stats.median(rssiD), Stats.medianCi95(rssiD),
            sa.snr?.median?.let { x -> sb.snr?.median?.let { y -> x - y } }, lim)
    }

    /** Plain-language difference, always in dB. */
    fun describe(diffDb: Double?, a: String, b: String): String = when {
        diffDb == null -> "no comparable values"
        abs(diffDb) < 0.5 -> "$a and $b within 0.5 dB of each other"
        diffDb > 0 -> "$a ${fmt1(diffDb)} dB stronger than $b"
        else -> "$a ${fmt1(-diffDb)} dB weaker than $b"
    }

    internal fun fmt1(d: Double) = ((d * 10).toLong() / 10.0).toString()
}
