package meshsurvey.core.map

import meshsurvey.core.abtest.Outcome
import meshsurvey.core.model.Observation
import meshsurvey.core.model.TestTx
import meshsurvey.core.model.TrackPoint
import meshsurvey.core.model.TxStatus
import kotlin.math.cos
import kotlin.math.floor

class Bounds(val lat0: Double, val lat1: Double, val lon0: Double, val lon1: Double) {
    val centerLat get() = (lat0 + lat1) / 2
    val centerLon get() = (lon0 + lon1) / 2
}

/** Square-ish metric grid: fixed latitude rows, longitude width corrected per row. */
class Grid(val sizeM: Double) {
    private val dLat = sizeM / 111_320.0
    private fun dLon(row: Int): Double {
        val c = (row + 0.5) * dLat
        return sizeM / (111_320.0 * cos(c * kotlin.math.PI / 180).coerceAtLeast(0.01))
    }
    fun key(lat: Double, lon: Double): Long {
        val row = floor(lat / dLat).toInt()
        val col = floor(lon / dLon(row)).toInt()
        return (row.toLong() shl 32) or (col.toLong() and 0xffffffffL)
    }
    fun bounds(key: Long): Bounds {
        val row = (key shr 32).toInt()
        val col = key.toInt()
        val lat0 = row * dLat
        val dl = dLon(row)
        return Bounds(lat0, lat0 + dLat, col * dl, col * dl + dl)
    }
}

/**
 * How a cell is shown. Absence of a cell = no data collected.
 * "Surveyed, nothing heard", "test confirmed not received" and "test not acknowledged (unknown)"
 * are all distinct from "no data" and from each other.
 */
enum class CellClass { MEASURED, TEST_REACHED_B, TEST_NOT_RECEIVED, TEST_NO_ACK, SURVEYED_NOTHING_HEARD }

class CellStats(val key: Long) {
    val snrs = ArrayList<Double>()
    val rssis = ArrayList<Int>()
    var observations = 0
    var direct = 0
    var trackReady = 0
    var testsSent = 0
    var testsReachedB = 0
    var testsNotReceived = 0
    var testsNoAck = 0
    var testsUnknown = 0
    var bestAccuracyM: Double? = null
    var worstAccuracyM: Double? = null

    val bestSnr get() = snrs.maxOrNull()
    val bestRssi get() = rssis.maxOrNull()
    val medianSnr get() = median(snrs)
    val medianRssi get() = median(rssis.map { it.toDouble() })

    val cls: CellClass get() = when {
        observations > 0 -> CellClass.MEASURED
        testsReachedB > 0 -> CellClass.TEST_REACHED_B
        testsNotReceived > 0 -> CellClass.TEST_NOT_RECEIVED
        testsNoAck > 0 -> CellClass.TEST_NO_ACK
        else -> CellClass.SURVEYED_NOTHING_HEARD
    }

    internal fun acc(a: Double?) {
        if (a == null) return
        bestAccuracyM = minOf(bestAccuracyM ?: a, a)
        worstAccuracyM = maxOf(worstAccuracyM ?: a, a)
    }

    companion object {
        fun median(v: List<Double>): Double? {
            if (v.isEmpty()) return null
            val s = v.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }
}

object Coverage {
    /**
     * Aggregates measured observations, radio-ready track points and test transmissions
     * (outcomes from [testOutcome], e.g. from a session comparison) into grid cells.
     */
    fun aggregate(
        grid: Grid,
        observations: List<Observation>,
        track: List<TrackPoint>,
        tests: List<TestTx>,
        testOutcome: (TestTx) -> Outcome? = { null },
    ): Map<Long, CellStats> {
        val cells = HashMap<Long, CellStats>()
        fun cell(lat: Double, lon: Double) = grid.key(lat, lon).let { k -> cells.getOrPut(k) { CellStats(k) } }
        for (o in observations) {
            val lat = o.lat ?: continue; val lon = o.lon ?: continue
            val c = cell(lat, lon)
            c.observations++
            o.snr?.let { c.snrs += it }
            o.rssi?.let { c.rssis += it }
            if (o.hopsAway == 0) c.direct++
            c.acc(o.accuracyM)
        }
        for (t in track) {
            if (!t.radioReady) continue
            cell(t.lat, t.lon).apply { trackReady++; acc(t.accuracyM) }
        }
        for (t in tests) {
            val lat = t.lat ?: continue; val lon = t.lon ?: continue
            val cls = classify(t, testOutcome(t))
            if (cls == TestClass.NOT_TRANSMITTED) continue
            val c = cell(lat, lon)
            c.testsSent++
            when (cls) {
                TestClass.REACHED_B -> c.testsReachedB++
                TestClass.CONFIRMED_NOT_RECEIVED -> c.testsNotReceived++
                TestClass.NO_ACK -> c.testsNoAck++
                else -> c.testsUnknown++
            }
            c.acc(t.accuracyM)
        }
        return cells
    }

    /**
     * REACHED_B: destination ACK, NAK from B (its radio heard it), or B's log shows it.
     * CONFIRMED_NOT_RECEIVED: B was logging and has no record (requires B's imported log).
     * NO_ACK: A's radio gave up without an ACK — delivery unknown, not proven lost.
     */
    enum class TestClass { REACHED_B, CONFIRMED_NOT_RECEIVED, NO_ACK, UNKNOWN, NOT_TRANSMITTED }

    fun classify(t: TestTx, o: Outcome?): TestClass = when (o) {
        Outcome.RECEIVED_AT_B, Outcome.DELIVERED_ACK_ONLY, Outcome.ACK_BUT_NOT_LOGGED, Outcome.REJECTED_BY_B -> TestClass.REACHED_B
        Outcome.NOT_RECEIVED -> TestClass.CONFIRMED_NOT_RECEIVED
        Outcome.NO_ACK -> TestClass.NO_ACK
        Outcome.NOT_TRANSMITTED -> TestClass.NOT_TRANSMITTED
        Outcome.RELAY_ONLY, Outcome.UNKNOWN -> TestClass.UNKNOWN
        null -> when (t.status) {
            TxStatus.DELIVERED, TxStatus.REJECTED -> TestClass.REACHED_B
            TxStatus.NO_ACK -> TestClass.NO_ACK
            TxStatus.NOT_TRANSMITTED -> TestClass.NOT_TRANSMITTED
            else -> TestClass.UNKNOWN
        }
    }
}

/** Colour scales (ARGB). Thresholds are display choices, documented in the user guide. */
object Scales {
    fun snr(v: Double): Long = when {
        v >= 5 -> 0xFF1B9E4BL
        v >= 0 -> 0xFF8BC34AL
        v >= -7 -> 0xFFFFC107L
        v >= -12 -> 0xFFFF7043L
        else -> 0xFFD32F2FL
    }
    fun rssi(v: Int): Long = when {
        v >= -90 -> 0xFF1B9E4BL
        v >= -105 -> 0xFF8BC34AL
        v >= -115 -> 0xFFFFC107L
        v >= -122 -> 0xFFFF7043L
        else -> 0xFFD32F2FL
    }
    const val NOTHING_HEARD = 0xFF757575L
    const val TEST_NOT_RECEIVED = 0xFF6A1B9AL
    const val TEST_NO_ACK = 0xFFE65100L
    const val TEST_OK = 0xFF1565C0L
    const val TEST_UNKNOWN = 0xFFBDBDBDL
}
