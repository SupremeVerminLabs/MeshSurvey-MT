package meshsurvey.core.analysis

import kotlin.math.floor
import kotlin.random.Random

/** Descriptive statistics on dB values. All work on the raw values; nothing is averaged in linear or percentage terms. */
data class Summary(
    val n: Int,
    val min: Double,
    val p10: Double,
    val p25: Double,
    val median: Double,
    val p75: Double,
    val p90: Double,
    val max: Double,
) {
    /** Interquartile range: the spread of the middle half of the values (dB). */
    val iqr get() = p75 - p25
}

object Stats {
    /** Linear-interpolated quantile (R type 7) of an ascending list. */
    fun quantile(sorted: List<Double>, q: Double): Double {
        require(sorted.isNotEmpty())
        if (sorted.size == 1) return sorted[0]
        val h = (sorted.size - 1) * q
        val lo = floor(h).toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo])
    }

    fun summary(values: Collection<Double>): Summary? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return Summary(s.size, s.first(), quantile(s, .10), quantile(s, .25), quantile(s, .5), quantile(s, .75), quantile(s, .90), s.last())
    }

    fun median(values: Collection<Double>): Double? = if (values.isEmpty()) null else quantile(values.sorted(), .5)

    /**
     * 95% interval for the median of [values] by bootstrap resampling with a fixed seed, so the same data always gives
     * the same answer. Null with fewer than 5 values (an interval would be meaningless).
     */
    fun medianCi95(values: List<Double>, iterations: Int = 2000, seed: Int = 20261127): Pair<Double, Double>? {
        if (values.size < 5) return null
        val rnd = Random(seed)
        val meds = DoubleArray(iterations) { _ ->
            val sample = List(values.size) { values[rnd.nextInt(values.size)] }.sorted()
            quantile(sample, .5)
        }.sorted()
        return quantile(meds, .025) to quantile(meds, .975)
    }
}
