package meshsurvey.core.analysis

import meshsurvey.core.model.LoggingWindow
import meshsurvey.core.model.Observation
import meshsurvey.core.model.RxStatus
import meshsurvey.core.model.SurveySession
import meshsurvey.core.model.TrackPoint
import meshsurvey.core.util.Geo
import kotlin.math.abs

/** Everything one receiver recorded over the period being compared (own data or imported). */
data class ReceiverData(
    val label: String,
    val receiverNode: Long?,
    val sessions: List<SurveySession>,
    val observations: List<Observation>,
    val track: List<TrackPoint>,
    val windows: List<LoggingWindow>,
)

enum class PairClass(val label: String) {
    BOTH("Observed by both receivers"),
    REFERENCE_ONLY("Reference receiver only"),
    MOBILE_ONLY("Mobile receiver only"),
    OTHER_UNAVAILABLE("Other receiver was not logging"),
    INCOMPATIBLE_CONFIG("Receivers on different radio settings"),
    AMBIGUOUS("Ambiguous match"),
}

data class Position(val lat: Double, val lon: Double, val accuracyM: Double?, val fromTrack: Boolean)

/** One packet (sender + packet id) and how the two receivers' logs relate for it. */
data class PairedPacket(
    val fromNode: Long,
    val packetId: Long,
    val cls: PairClass,
    val mobile: Observation?,
    val reference: Observation?,
    /** Where the mobile receiver was at that moment; null when no valid position was recorded (never invented). */
    val mobilePosition: Position?,
    val note: String,
)

data class PairStats(
    val mobileTotal: Int,
    val referenceTotal: Int,
    val both: Int,
    val referenceOnly: Int,
    val mobileOnly: Int,
    val excludedUnavailable: Int,
    val excludedIncompatible: Int,
    val ambiguous: Int,
    val excludedOwnPackets: Int,
    val excludedLateQueued: Int,
    /** Median of (mobile clock − reference clock) over matched packets, ms. */
    val clockSkewMs: Long?,
    /** Matched packets: mobile minus reference, in dB. */
    val snrDiff: Summary?,
    val rssiDiff: Summary?,
    /** Median distance between the receivers over matched packets where both positions are known (m). */
    val medianSeparationM: Double?,
    val separationSamples: Int,
) {
    /** Packets both receivers could fairly have heard: both logging, same settings, unambiguous. */
    val comparable get() = both + referenceOnly + mobileOnly

    /** Of the reference's comparable packets, the share the mobile also logged. Denominator: both + reference-only. */
    val mobileShareOfReference: Double? get() = (both + referenceOnly).takeIf { it > 0 }?.let { both.toDouble() / it }
    val referenceShareOfMobile: Double? get() = (both + mobileOnly).takeIf { it > 0 }?.let { both.toDouble() / it }

    /** True when the receivers were within 100 m for most matched packets, so differences can reflect equipment. */
    val coLocated: Boolean? get() = medianSeparationM?.let { it <= 100.0 }.takeIf { separationSamples >= 10 }

    fun shareText(share: Double?, of: String, denominator: Int) = share?.let {
        "${(it * 100).toInt()}% of $denominator packets $of (an overlap of logs, not a packet delivery rate)"
    } ?: "no comparable packets"
}

data class PairResult(val mobile: ReceiverData, val reference: ReceiverData, val packets: List<PairedPacket>, val stats: PairStats)

/**
 * Compares two receivers' logs packet by packet. A packet is identified by sender + packet id, which Meshtastic keeps
 * the same across relays; each receiver logs only the first copy it hears (firmware drops duplicates), so a match
 * means both heard the packet by some path. Unmatched packets are *not* treated as lost: the other receiver may have
 * been filtered by its firmware, out of sync, or simply out of range at its own location.
 */
object PairCompare {
    /** Same packet id further apart than this is not trusted as the same packet. Allows for clock offsets between devices. */
    const val MATCH_WINDOW_MS = 120_000L
    /** A track point is only used as "where the mobile was" if this close in time. */
    const val POSITION_WINDOW_MS = 10_000L

    fun compare(mobile: ReceiverData, reference: ReceiverData): PairResult {
        val own = setOfNotNull(mobile.receiverNode, reference.receiverNode)
        var ownCount = 0; var late = 0
        fun usable(o: Observation): Boolean {
            if (o.fromNode in own) { ownCount++; return false }
            if (o.status == RxStatus.QUEUED_LATE) { late++; return false }   // reception time unknown
            return true
        }
        val m = mobile.observations.filter(::usable).groupBy { it.fromNode to it.packetId }
        val r = reference.observations.filter(::usable).groupBy { it.fromNode to it.packetId }

        val packets = ArrayList<PairedPacket>()
        for (key in (m.keys + r.keys)) {
            val ml = m[key].orEmpty(); val rl = r[key].orEmpty()
            val mo = ml.singleOrNull(); val ro = rl.singleOrNull()
            packets += when {
                ml.size > 1 || rl.size > 1 -> PairedPacket(key.first, key.second, PairClass.AMBIGUOUS, ml.firstOrNull(), rl.firstOrNull(),
                    ml.firstOrNull()?.let(::ownPos), "the same packet id appears more than once in one log (id reuse or duplicate import)")
                mo != null && ro != null -> {
                    if (abs(mo.rxMs - ro.rxMs) > MATCH_WINDOW_MS)
                        PairedPacket(key.first, key.second, PairClass.AMBIGUOUS, mo, ro, ownPos(mo),
                            "same packet id but logged ${abs(mo.rxMs - ro.rxMs) / 1000} s apart; probably a reused id")
                    else if (!sameConfig(mo.preset, mo.region, ro.preset, ro.region))
                        PairedPacket(key.first, key.second, PairClass.INCOMPATIBLE_CONFIG, mo, ro, ownPos(mo), "receivers on ${mo.preset}/${mo.region} vs ${ro.preset}/${ro.region}")
                    else PairedPacket(key.first, key.second, PairClass.BOTH, mo, ro, ownPos(mo) ?: trackPos(mobile, mo.rxMs), "")
                }
                ro != null -> classifyOneSided(ro, other = mobile, oneSided = PairClass.REFERENCE_ONLY, mobileSide = false, mobile = mobile)
                else -> classifyOneSided(mo!!, other = reference, oneSided = PairClass.MOBILE_ONLY, mobileSide = true, mobile = mobile)
            }
        }
        packets.sortBy { it.reference?.rxMs ?: it.mobile?.rxMs ?: 0 }

        val matched = packets.filter { it.cls == PairClass.BOTH }
        val skew = Stats.median(matched.map { (it.mobile!!.rxMs - it.reference!!.rxMs).toDouble() })?.toLong()
        val seps = matched.mapNotNull { p ->
            val mp = p.mobilePosition ?: return@mapNotNull null
            val ro = p.reference!!
            if (ro.lat == null || ro.lon == null) null else Geo.distanceM(mp.lat, mp.lon, ro.lat, ro.lon)
        }
        val stats = PairStats(
            mobileTotal = m.values.sumOf { it.size }, referenceTotal = r.values.sumOf { it.size },
            both = matched.size,
            referenceOnly = packets.count { it.cls == PairClass.REFERENCE_ONLY },
            mobileOnly = packets.count { it.cls == PairClass.MOBILE_ONLY },
            excludedUnavailable = packets.count { it.cls == PairClass.OTHER_UNAVAILABLE },
            excludedIncompatible = packets.count { it.cls == PairClass.INCOMPATIBLE_CONFIG },
            ambiguous = packets.count { it.cls == PairClass.AMBIGUOUS },
            excludedOwnPackets = ownCount, excludedLateQueued = late, clockSkewMs = skew,
            snrDiff = Stats.summary(matched.mapNotNull { p -> p.mobile!!.snr?.let { a -> p.reference!!.snr?.let { b -> a - b } } }),
            rssiDiff = Stats.summary(matched.mapNotNull { p -> p.mobile!!.rssi?.let { a -> p.reference!!.rssi?.let { b -> (a - b).toDouble() } } }),
            medianSeparationM = Stats.median(seps), separationSamples = seps.size,
        )
        return PairResult(mobile, reference, packets, stats)
    }

    private fun classifyOneSided(o: Observation, other: ReceiverData, oneSided: PairClass, mobileSide: Boolean, mobile: ReceiverData): PairedPacket {
        val pos = if (mobileSide) ownPos(o) else trackPos(mobile, o.rxMs)
        if (!logging(other, o.rxMs))
            return PairedPacket(o.fromNode, o.packetId, PairClass.OTHER_UNAVAILABLE, if (mobileSide) o else null, if (mobileSide) null else o, pos,
                "${other.label} was not logging at that time")
        val otherCfg = configAt(other, o.rxMs)
        if (otherCfg != null && !sameConfig(o.preset, o.region, otherCfg.first, otherCfg.second))
            return PairedPacket(o.fromNode, o.packetId, PairClass.INCOMPATIBLE_CONFIG, if (mobileSide) o else null, if (mobileSide) null else o, pos,
                "${other.label} was on ${otherCfg.first}/${otherCfg.second}")
        return PairedPacket(o.fromNode, o.packetId, oneSided, if (mobileSide) o else null, if (mobileSide) null else o, pos,
            if (pos == null && !mobileSide) "no valid mobile position within ${POSITION_WINDOW_MS / 1000} s, so not placed on the map" else "")
    }

    private fun sameConfig(p1: String?, r1: String?, p2: String?, r2: String?): Boolean =
        (p1 == null || p2 == null || p1 == p2) && (r1 == null || r2 == null || r1 == r2)

    private fun ownPos(o: Observation): Position? =
        if (o.lat != null && o.lon != null) Position(o.lat, o.lon, o.accuracyM, fromTrack = false) else null

    /** Nearest recorded track point within ±10 s; null otherwise. Positions are never interpolated or invented. */
    private fun trackPos(d: ReceiverData, t: Long): Position? =
        d.track.minByOrNull { abs(it.ms - t) }?.takeIf { abs(it.ms - t) <= POSITION_WINDOW_MS }?.let { Position(it.lat, it.lon, it.accuracyM, true) }

    /** Was this receiver logging at time t (its own clock)? Uses logging windows, falling back to session spans. */
    fun logging(d: ReceiverData, t: Long): Boolean =
        if (d.windows.isNotEmpty()) d.windows.any { t >= it.startMs - 5_000 && t <= it.endMs + 5_000 }
        else d.sessions.any { t >= it.startedMs && t <= (it.endedMs ?: Long.MAX_VALUE) }

    /** The other receiver's preset/region at time t, from its session (null = unknown). */
    private fun configAt(d: ReceiverData, t: Long): Pair<String?, String?>? =
        d.sessions.firstOrNull { t >= it.startedMs && t <= (it.endedMs ?: Long.MAX_VALUE) }?.let { it.preset to it.region }
}
