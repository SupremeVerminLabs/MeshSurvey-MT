package meshsurvey.core.quality

import meshsurvey.core.model.FixSource
import meshsurvey.core.model.Observation
import meshsurvey.core.model.RxStatus
import meshsurvey.core.model.Source

/** Ordered from best to worst. */
enum class ConfidenceClass(val label: String) {
    VALID("Valid"),
    LIMITED("Limited confidence"),
    INSUFFICIENT("Insufficient information"),
    POSSIBLY_MISCONFIGURED("Possibly misconfigured"),
    INVALID_FOR_AGGREGATION("Invalid for aggregation"),
}

data class Confidence(val cls: ConfidenceClass, val reasons: List<String>) {
    val usableForAggregates get() = cls == ConfidenceClass.VALID || cls == ConfidenceClass.LIMITED
}

/**
 * Explains how far an observation can be trusted as a *located signal measurement*. Derived on demand from
 * the stored record; the record itself is never changed or deleted, and unusual signal values are flagged,
 * not discarded (they may be real).
 */
object ConfidenceRules {
    const val GOOD_ACCURACY_M = 30.0
    const val MAX_ACCURACY_M = 100.0
    const val MAX_STATIONARY_POSITION_AGE_MS = 30 * 60_000L

    fun classify(o: Observation): Confidence {
        val reasons = ArrayList<Pair<ConfidenceClass, String>>()
        fun flag(c: ConfidenceClass, why: String) { reasons += c to why }

        when (o.source) {
            Source.SIMULATED -> flag(ConfidenceClass.INVALID_FOR_AGGREGATION, "simulated data")
            else -> {}
        }
        val fixSource = o.fixSource?.let { runCatching { FixSource.valueOf(it) }.getOrNull() }
        if (fixSource == FixSource.MOCK) flag(ConfidenceClass.INVALID_FOR_AGGREGATION, "position came from a mock-location app")
        if (fixSource == FixSource.SIMULATED) flag(ConfidenceClass.INVALID_FOR_AGGREGATION, "simulated position")

        // position
        if (o.lat == null || o.lon == null) flag(ConfidenceClass.INSUFFICIENT, "no position at reception time")
        else {
            val acc = o.accuracyM
            when {
                acc != null && acc > MAX_ACCURACY_M -> flag(ConfidenceClass.INVALID_FOR_AGGREGATION, "position accuracy ${acc.toInt()} m (worse than ${MAX_ACCURACY_M.toInt()} m)")
                acc != null && acc > GOOD_ACCURACY_M -> flag(ConfidenceClass.LIMITED, "position accuracy ${acc.toInt()} m")
                acc == null && fixSource != FixSource.MANUAL && fixSource != FixSource.RADIO -> flag(ConfidenceClass.LIMITED, "position accuracy not reported")
            }
            if (fixSource == FixSource.MANUAL || fixSource == FixSource.RADIO) {
                val age = o.positionAgeMs
                if (fixSource == FixSource.RADIO && age != null && age > MAX_STATIONARY_POSITION_AGE_MS)
                    flag(ConfidenceClass.LIMITED, "radio position assumed stationary, last determined ${age / 60_000} min before reception")
            }
        }

        // signal values
        if (o.status == RxStatus.QUEUED_LATE) flag(ConfidenceClass.INSUFFICIENT, "delivered late from the radio's queue; reception time and place unknown")
        if (o.rssi == null && o.snr == null) flag(ConfidenceClass.INSUFFICIENT, "no RSSI or SNR reported")
        o.rssi?.let { if (it > 0 || it < -150) flag(ConfidenceClass.POSSIBLY_MISCONFIGURED, "implausible RSSI $it dBm") }
        o.snr?.let { if (it > 30 || it < -40) flag(ConfidenceClass.POSSIBLY_MISCONFIGURED, "implausible SNR $it dB") }

        // configuration
        if (o.preset == null) flag(ConfidenceClass.LIMITED, "radio preset unknown; cannot be grouped with compatible measurements")

        val worst = reasons.maxByOrNull { it.first.ordinal }?.first ?: ConfidenceClass.VALID
        return Confidence(worst, reasons.map { it.second })
    }
}
