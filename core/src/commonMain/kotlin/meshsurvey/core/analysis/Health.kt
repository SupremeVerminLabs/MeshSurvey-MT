package meshsurvey.core.analysis

import meshsurvey.core.model.ClockEvent
import meshsurvey.core.model.FixSource
import meshsurvey.core.model.LoggingWindow
import meshsurvey.core.model.Observation
import meshsurvey.core.model.SessionStatus
import meshsurvey.core.model.SurveySession
import meshsurvey.core.quality.ConfidenceRules
import meshsurvey.core.util.Geo
import kotlin.math.abs

enum class HealthScope { SOLO, HISTORICAL, PAIRED }
enum class Severity { INFO, NOTICE, WARNING }
enum class FindingConfidence { LOW, MEDIUM, HIGH }

/**
 * One diagnostic finding. Findings point at *possible* problems and say why; they never diagnose hardware.
 * Every field is shown when the user expands the finding.
 */
data class Finding(
    val ruleId: String,
    val scope: HealthScope,
    val severity: Severity,
    val title: String,
    val statement: String,
    val evidence: List<String>,
    val baseline: String,
    val sampleCount: Int,
    val confidence: FindingConfidence,
    val alternatives: List<String> = emptyList(),
    val inspection: List<String> = emptyList(),
    val caveats: List<String> = emptyList(),
    /** True for "not enough evidence" results: shown instead of a score, never as a problem. */
    val insufficient: Boolean = false,
)

/**
 * Deterministic, documented rules (docs/HEALTH_RULES.md). No scores, no network services, no transmissions.
 * Thresholds are constants here so they can be read and challenged.
 */
object Health {
    const val MIN_SOLO_OBS = 30
    const val MIN_VARIABILITY_SAMPLES = 20
    const val HIGH_VARIABILITY_IQR_DB = 10.0
    const val MEANINGFUL_DIFF_DB = 3.0
    const val STABLE_DIFF_DB = 1.5
    const val MIN_PAIRED_COMPARABLE = 50
    const val FEWER_SHARE = 0.30
    const val SKEW_NOTICE_MS = 30_000L

    private val connectionInspection = listOf(
        "Check the antenna connector is tight and the right type (SMA vs RP-SMA) with no bent centre pin.",
        "Inspect the feedline and adapters for kinks, crushed sections, water ingress or corrosion.",
        "Check the antenna is mounted firmly, vertical and clear of nearby metal.",
    )

    // ---------------------------------------------------------------- solo
    fun solo(sessions: List<SurveySession>, observations: List<Observation>, clockEvents: List<ClockEvent> = emptyList(),
             windows: List<LoggingWindow> = emptyList()): List<Finding> {
        val out = ArrayList<Finding>()
        val usable = observations.filter(EquipmentCompare::usable)

        // Configuration consistency
        val configs = sessions.map { Triple(it.preset, it.region, it.frequencyMhz) }.toSet()
        if (configs.size > 1) out += Finding("SOLO-CONFIG", HealthScope.SOLO, Severity.NOTICE,
            "Measurements were made under ${configs.size} radio configurations",
            "These sessions used different preset/region/frequency settings. Signal levels from different settings are not directly comparable.",
            configs.map { "preset ${it.first ?: "?"}, region ${it.second ?: "?"}, frequency ${it.third?.let { f -> "$f MHz" } ?: "?"}" },
            "the sessions themselves", sessions.size, FindingConfidence.HIGH,
            alternatives = listOf("A deliberate temporary preset change for testing."),
            inspection = listOf("Filter by preset before comparing coverage or equipment."))

        // Recording interruptions
        val interrupted = sessions.count { it.status == SessionStatus.INTERRUPTED }
        val gaps = clockEvents.filter { it.kind == meshsurvey.core.model.ClockEventKind.TICK_GAP }
        val jumps = clockEvents.filter { it.kind == meshsurvey.core.model.ClockEventKind.CLOCK_JUMP }
        if (interrupted > 0 || gaps.isNotEmpty() || jumps.isNotEmpty()) out += Finding("SOLO-INTERRUPT", HealthScope.SOLO, Severity.NOTICE,
            "Recording was interrupted",
            "Periods without logging look like periods without reception on a map; they are not.",
            listOfNotNull(
                interrupted.takeIf { it > 0 }?.let { "$it session(s) ended unexpectedly (app closed, crash or radio lost)" },
                gaps.takeIf { it.isNotEmpty() }?.let { "${it.size} pause(s) in the app's 1 s timer, ${it.sumOf { g -> g.magnitudeMs } / 1000} s in total (app suspended or PC asleep)" },
                jumps.takeIf { it.isNotEmpty() }?.let { "${it.size} system clock change(s)" },
            ), "the app's own timing records", sessions.size + gaps.size + jumps.size, FindingConfidence.HIGH,
            alternatives = listOf("Deliberate pauses (stopping the app, disconnecting the radio)."),
            inspection = listOf("On Android, allow the app to run in the background (battery settings).", "Keep the PC awake and the radio cable secure."))

        if (usable.size < MIN_SOLO_OBS) {
            out += Finding("SOLO-DATA", HealthScope.SOLO, Severity.INFO, "Not enough measurements for a signal assessment",
                "Only ${usable.size} usable receptions (need $MIN_SOLO_OBS). No conclusions are drawn about reception quality.",
                listOf("${observations.size} receptions in total, ${observations.size - usable.size} excluded as low-confidence or missing position/signal values"),
                "none", usable.size, FindingConfidence.HIGH, insufficient = true,
                inspection = listOf("Log for longer, or with a GPS position source, before assessing."))
            return out
        }

        // Measurement quality
        val lowShare = (observations.size - usable.size).toDouble() / observations.size
        if (lowShare > 0.30) {
            val reasons = observations.flatMap { ConfidenceRules.classify(it).reasons }.groupingBy { it.substringBefore(" (").substringBefore(",") }.eachCount()
                .entries.sortedByDescending { it.value }.take(4).map { "${it.value}× ${it.key}" }
            out += Finding("SOLO-QUALITY", HealthScope.SOLO, Severity.NOTICE, "Many receptions have limited measurement quality",
                "${(lowShare * 100).toInt()}% of ${observations.size} receptions were excluded from statistics.", reasons,
                "the confidence rules (Confidence.kt)", observations.size, FindingConfidence.HIGH,
                inspection = listOf("Use a position source with reported accuracy (phone or USB GPS) and keep it on while logging."))
        }

        // Variability, only meaningful for a stationary receiver
        val stationary = isStationary(usable)
        if (stationary) {
            val bySender = usable.filter { it.snr != null }.groupBy { it.fromNode }.filterValues { it.size >= MIN_VARIABILITY_SAMPLES }
            val noisy = bySender.mapNotNull { (n, l) -> Stats.summary(l.map { it.snr!! })?.takeIf { it.iqr > HIGH_VARIABILITY_IQR_DB }?.let { n to it } }
            if (noisy.isNotEmpty()) out += Finding("SOLO-VARIABILITY", HealthScope.SOLO, Severity.NOTICE,
                "Unusually variable reception from ${noisy.size} of ${bySender.size} regularly heard senders",
                "For a receiver that did not move, SNR from these senders varied by more than ${HIGH_VARIABILITY_IQR_DB.toInt()} dB between the middle half of readings.",
                noisy.map { (n, s) -> "node ${n.toString(16)}: ${s.n} readings, SNR median ${f1(s.median)} dB, IQR ${f1(s.iqr)} dB" },
                "each sender's own readings at this receiver", noisy.sumOf { it.second.n },
                if (noisy.size >= 3) FindingConfidence.MEDIUM else FindingConfidence.LOW,
                alternatives = listOf("The sender moved or changed its own antenna/power.", "Changing environment (traffic, weather, foliage, people nearby).",
                    "Local interference or a busy channel.", "Signals arriving by different relay paths."),
                inspection = listOf("Check for an intermittent connector or feedline (wiggle test while watching Monitor).", "Check the antenna mount does not move in wind."),
                caveats = listOf("Variability alone does not indicate a fault; compare with another receiver at the same place if possible."))
        } else out += Finding("SOLO-MOVING", HealthScope.SOLO, Severity.INFO, "Variability not assessed for a moving receiver",
            "The receiver moved during these measurements, so signal changes mostly reflect location.", emptyList(),
            "none", usable.size, FindingConfidence.HIGH, insufficient = true)

        if (out.none { it.severity != Severity.INFO }) out += Finding("SOLO-OK", HealthScope.SOLO, Severity.INFO, "No inconsistencies found",
            "Checked: configuration consistency, recording interruptions, measurement quality" + (if (stationary) ", per-sender variability." else "."),
            listOf("${usable.size} usable receptions from ${usable.map { it.fromNode }.toSet().size} senders"), "the checks listed", usable.size,
            FindingConfidence.MEDIUM, caveats = listOf("Finding nothing is not proof of good equipment: without a reference, absolute performance cannot be judged."))
        return out
    }

    /** All positions within 50 m of their median, or the position came from a stationary source (radio/manual). */
    fun isStationary(obs: List<Observation>): Boolean {
        val pos = obs.filter { it.lat != null && it.lon != null }
        if (pos.isEmpty()) return false
        if (pos.all { it.fixSource == FixSource.RADIO.name || it.fixSource == FixSource.MANUAL.name }) return true
        val la = Stats.median(pos.map { it.lat!! })!!; val lo = Stats.median(pos.map { it.lon!! })!!
        return pos.count { Geo.distanceM(la, lo, it.lat!!, it.lon!!) <= 50 } >= pos.size * 0.95
    }

    // ---------------------------------------------------------------- historical
    fun historical(current: MeasurementGroup, baseline: MeasurementGroup): List<Finding> {
        val c = EquipmentCompare.compare(current, baseline)
        val base = "earlier measurements \"${baseline.label}\" at the same places from the same senders"
        if (!c.sufficient) return listOf(Finding("HIST-NODATA", HealthScope.HISTORICAL, Severity.INFO, "No comparable earlier measurements",
            "Current and earlier surveys share only ${c.strata.size} place-and-sender combinations (need ${EquipmentCompare.MIN_STRATA}). No trend is reported.",
            listOf("current: ${c.a.usable} usable receptions", "earlier: ${c.b.usable} usable receptions"), base, c.a.usable + c.b.usable,
            FindingConfidence.HIGH, caveats = c.limitations, insufficient = true))
        val out = ArrayList<Finding>()
        val d = c.matchedSnrDiffDb ?: c.matchedRssiDiffDb ?: 0.0
        val ci = c.matchedSnrCi95 ?: c.matchedRssiCi95
        val ciExcludesZero = ci != null && (ci.first > 0 || ci.second < 0)
        val metric = if (c.matchedSnrDiffDb != null) "SNR" else "RSSI"
        val ev = listOf("median $metric difference over ${c.strata.size} matched place/sender pairs: ${f1(d)} dB" + (ci?.let { " (95% interval ${f1(it.first)} to ${f1(it.second)} dB)" } ?: ""),
            "current: ${c.a.usable} receptions, earlier: ${c.b.usable}", "overall-median difference (unmatched, for reference only): ${c.naiveSnrDiffDb?.let(::f1) ?: "?"} dB")
        val conf = if (c.strata.size >= 15 && c.limitations.size <= 1) FindingConfidence.MEDIUM else FindingConfidence.LOW
        when {
            d <= -MEANINGFUL_DIFF_DB && ciExcludesZero -> out += Finding("HIST-DECLINE", HealthScope.HISTORICAL, Severity.WARNING,
                "Reception is weaker than before", "Compared with $base, the same senders now arrive ${f1(-d)} dB weaker.", ev, base, c.strata.size, conf,
                alternatives = listOf("Seasonal change (foliage, rain, snow).", "The senders changed antenna, power or location.", "New obstructions or interference near the receiver."),
                inspection = connectionInspection, caveats = c.limitations)
            d >= MEANINGFUL_DIFF_DB && ciExcludesZero -> out += Finding("HIST-IMPROVE", HealthScope.HISTORICAL, Severity.INFO,
                "Reception is stronger than before", "Compared with $base, the same senders now arrive ${f1(d)} dB stronger.", ev, base, c.strata.size, conf,
                alternatives = listOf("The senders improved their own setups.", "Seasonal change."), caveats = c.limitations)
            abs(d) < STABLE_DIFF_DB -> out += Finding("HIST-STABLE", HealthScope.HISTORICAL, Severity.INFO, "Reception unchanged",
                "Matched measurements are within ${f1(STABLE_DIFF_DB)} dB of $base.", ev, base, c.strata.size, conf, caveats = c.limitations)
            else -> out += Finding("HIST-UNCLEAR", HealthScope.HISTORICAL, Severity.INFO, "Possible change, not established",
                "The matched difference (${f1(d)} dB) is too small or too uncertain to call a change.", ev, base, c.strata.size, FindingConfidence.LOW,
                caveats = c.limitations, insufficient = true)
        }
        return out
    }

    // ---------------------------------------------------------------- paired
    fun paired(r: PairResult): List<Finding> {
        val s = r.stats
        val out = ArrayList<Finding>()
        val m = r.mobile.label; val ref = r.reference.label
        val counts = listOf("both: ${s.both}", "$ref only: ${s.referenceOnly}", "$m only: ${s.mobileOnly}",
            "excluded, other receiver not logging: ${s.excludedUnavailable}", "excluded, different settings: ${s.excludedIncompatible}",
            "ambiguous: ${s.ambiguous}", "excluded, late from radio queue: ${s.excludedLateQueued}")
        s.clockSkewMs?.takeIf { abs(it) > SKEW_NOTICE_MS }?.let { out += Finding("PAIR-SKEW", HealthScope.PAIRED, Severity.NOTICE,
            "Device clocks differ by ${abs(it) / 1000} s", "Matched packets show the two devices' clocks disagree. Matching by packet id still works, " +
                "but time-based steps (availability, replay) are less precise.", listOf("median clock difference over ${s.both} matched packets"),
            "matched packets", s.both, FindingConfidence.HIGH, inspection = listOf("Enable automatic time on both devices.")) }
        if (s.comparable < MIN_PAIRED_COMPARABLE) {
            out += Finding("PAIR-INSUFFICIENT", HealthScope.PAIRED, Severity.INFO, "Not enough comparable packets",
                "Only ${s.comparable} packets were comparable (need $MIN_PAIRED_COMPARABLE). No receiver assessment is made.", counts,
                "packets both receivers could have heard", s.comparable, FindingConfidence.HIGH, insufficient = true)
            return out
        }
        val shareM = s.mobileShareOfReference!!; val shareR = s.referenceShareOfMobile!!
        val evidence = counts + listOf(s.shareText(s.mobileShareOfReference, "logged by $ref were also logged by $m", s.both + s.referenceOnly),
            s.shareText(s.referenceShareOfMobile, "logged by $m were also logged by $ref", s.both + s.mobileOnly)) +
            listOfNotNull(s.snrDiff?.let { "matched packets: $m − $ref SNR median ${f1(it.median)} dB (IQR ${f1(it.iqr)} dB, n=${it.n})" },
                s.medianSeparationM?.let { "median distance between receivers: ${it.toInt()} m (${s.separationSamples} packets with both positions)" })
        val base = "the other receiver, over the same packets"

        if (s.coLocated != true) {
            out += Finding("PAIR-LOCATION", HealthScope.PAIRED, Severity.INFO, "Receivers were in different places",
                "Differences between $m and $ref mostly reflect where each was, not their equipment. See the comparison map for where each heard what.",
                evidence, base, s.comparable, FindingConfidence.HIGH,
                caveats = listOf(if (s.coLocated == null) "Positions were missing for most matched packets, so distance could not be measured." else
                    "For an equipment comparison, place both receivers side by side (within ~10 m) for an hour."))
            return out
        }
        // Co-located: differences can point at equipment.
        val snrMed = s.snrDiff?.median
        fun weaker(name: String, share: Double, otherShare: Double, snrFor: Double?) {
            val fewer = (otherShare - share) >= FEWER_SHARE * otherShare
            if (!fewer) return
            if (snrFor != null && snrFor <= -MEANINGFUL_DIFF_DB) out += Finding("PAIR-WEAKER", HealthScope.PAIRED, Severity.WARNING,
                "$name heard fewer packets and weaker signals",
                "Side by side, $name logged ${(share * 100).toInt()}% of the other receiver's packets (the other logged ${(otherShare * 100).toInt()}% of $name's), " +
                    "and matched packets arrived ${f1(-snrFor)} dB weaker. Consider inspecting $name's antenna connection, feedline and mounting.",
                evidence, base, s.comparable, if (s.comparable >= 200) FindingConfidence.MEDIUM else FindingConfidence.LOW,
                alternatives = listOf("Different antenna gain or type (expected if the antennas differ).", "Different radio front-end or firmware.",
                    "One receiver was transmitting or busy part of the time.", "Body, vehicle or wall shadowing between two 'side by side' positions."),
                inspection = connectionInspection,
                caveats = listOf("RSSI/SNR cannot by themselves establish a defective antenna, damaged receiver or poor SWR.", "Same-model radios differ by a few dB."))
            else out += Finding("PAIR-CONTRADICTORY", HealthScope.PAIRED, Severity.NOTICE, "$name heard fewer packets, but not weaker signals",
                "$name logged clearly fewer of the shared packets, yet matched packets were not weaker (median ${snrFor?.let(::f1) ?: "?"} dB). " +
                    "The evidence does not point at the antenna.", evidence, base, s.comparable, FindingConfidence.LOW,
                alternatives = listOf("Logging gaps or app pauses on $name.", "Firmware duplicate filtering or a busy radio queue.",
                    "Different channel list or encryption keys (encrypted packets are still logged).", "Bluetooth link drops between the radio and the app."),
                inspection = listOf("Check the interruption findings and the receiver-availability timeline.", "Check both radios have the same channels configured."))
        }
        weaker(m, shareM, shareR, snrMed)
        weaker(ref, shareR, shareM, snrMed?.let { -it })
        if (out.none { it.ruleId == "PAIR-WEAKER" || it.ruleId == "PAIR-CONTRADICTORY" }) out += Finding("PAIR-SIMILAR", HealthScope.PAIRED, Severity.INFO,
            "Receivers performed similarly side by side",
            "Neither receiver logged ${(FEWER_SHARE * 100).toInt()}% fewer of the shared packets than the other." +
                (snrMed?.let { " Matched SNR differed by ${f1(it)} dB (median)." } ?: ""), evidence, base, s.comparable, FindingConfidence.MEDIUM,
            caveats = listOf("Similar results show neither receiver is clearly worse here; they do not prove either is performing optimally."))
        return out
    }

    private fun f1(d: Double): String = (kotlin.math.round(d * 10) / 10).toString()
}
