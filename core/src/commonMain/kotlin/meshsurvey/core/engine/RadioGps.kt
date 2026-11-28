package meshsurvey.core.engine

import meshsurvey.core.model.Fix
import meshsurvey.core.model.FixSource
import meshsurvey.core.proto.PositionInfo
import meshsurvey.core.util.Clock
import meshsurvey.core.util.Geo
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Uses the connected radio's own position (its GPS module, or the fixed position configured in it) as
 * the receiver position. Intended for **stationary** receivers.
 *
 * Why stationary only: the phone API gives the app the radio's position only in the radio's NodeInfo at
 * connect time and in the radio's own position broadcasts. With default 2.7 firmware settings those come
 * every 5–60 minutes, far too rarely for a moving receiver (the logger needs a position ≤ 10 s old).
 *
 * Behaviour:
 *  - Each report is checked for usable precision (channel position precision / HDOP).
 *  - While consecutive reports agree (radio not moving), the last position is re-asserted every second,
 *    like a manually entered fixed position, recorded with source RADIO.
 *  - If a report shows the radio has moved, nothing is asserted until two consecutive reports agree
 *    again. Receptions in between are logged without a position rather than at a wrong one.
 */
class RadioGpsSource(private val clock: Clock) {
    companion object {
        /** Reports further apart than this mean the radio moved. */
        const val MOVE_THRESHOLD_M = 50.0
        /** Positions blurred more than this are not used (default public-channel precision is ~2.9 km). */
        const val MAX_USABLE_ERROR_M = 100.0

        /** Approximate position error of a Meshtastic position truncated to [bits] (≈ 23.9e6 m / 2^bits). */
        fun precisionErrorM(bits: Int?): Double? = bits?.takeIf { it in 1..31 }?.let { 23.9e6 / 2.0.pow(it) }

        /** A GPS position stored in the radio from before this connection is only trusted if this recent. */
        const val MAX_STORED_AGE_S = 2 * 3600L

        /** Rough horizontal error from HDOP (firmware sends HDOP x 100) assuming ~5 m user range error. */
        fun hdopErrorM(hdop: Int?): Double? = hdop?.takeIf { it > 0 }?.let { it / 100.0 * 5.0 }
    }

    private var enabled = false
    private var onFix: ((Fix) -> Unit)? = null
    private var onStatus: ((String) -> Unit)? = null
    private var held: PositionInfo? = null
    private var heldAccuracy: Double? = null
    private var heldReceivedWallMs: Long? = null
    // GPS quality from the radio's own broadcasts (their position may be blurred, but DOP and satellites are not)
    private var lastDopX100: Int? = null
    private var lastSats: Int? = null
    private var lastQualityWallMs: Long? = null
    private var lastReport: PositionInfo? = null
    private var lastReportWallMs: Long? = null
    private var moving = false

    val isEnabled get() = enabled

    fun start(onFix: (Fix) -> Unit, onStatus: (String) -> Unit) {
        enabled = true; this.onFix = onFix; this.onStatus = onStatus
        status()
    }

    fun stop() { enabled = false; onFix = null; onStatus = null }

    /** Forget everything about the previous radio (call on connect/disconnect). */
    fun reset() { held = null; heldAccuracy = null; heldReceivedWallMs = null; lastReport = null; lastReportWallMs = null; moving = false; status() }

    /** Accuracy estimate from the DOP in the radio's last broadcast, if within the last hour (null otherwise). */
    private fun dopAccuracy(): Double? {
        val t = lastQualityWallMs ?: return null
        if (clock.wallMs() - t > 3_600_000) return null
        return hdopErrorM(lastDopX100)
    }

    /** The position currently held, if any (for "use as fixed reference position"). */
    fun heldPosition(): PositionInfo? = held

    /** @param fromConfig true for the position in the radio's NodeInfo at connect (may be stored from earlier). */
    fun onReport(p: PositionInfo, fromConfig: Boolean = false) {
        val now = clock.wallMs()
        if (!fromConfig && (p.hdop != null || p.pdop != null)) { lastDopX100 = p.hdop ?: p.pdop; lastSats = p.satsInView; lastQualityWallMs = now }
        if (fromConfig && p.locationSource != 1) {
            val ageS = p.timeS?.let { now / 1000 - it }
            if (ageS == null || ageS > MAX_STORED_AGE_S) {
                // Stored GPS position of unknown or old age: the radio may have been moved since. Wait for a fresh report.
                say("The radio's stored position is " + (ageS?.let { "${it / 3600} h old" } ?: "of unknown age") +
                    "; waiting for a fresh report from its GPS.")
                return
            }
        }
        val err = listOfNotNull(precisionErrorM(p.precisionBits), hdopErrorM(p.hdop)).maxOrNull()
        if (err != null && err > MAX_USABLE_ERROR_M) {
            // Typically the radio's own over-the-air broadcast, blurred to the channel's position precision.
            // The full-precision position comes from the radio's NodeInfo (read at connect and every minute), so
            // a blurred broadcast is simply ignored; it never replaces a good position.
            if (held == null) say("So far only a blurred position (~${err.roundToInt()} m) has been seen; waiting for the radio's " +
                "full-precision position (read from the radio about once a minute).")
            return
        }
        val prev = lastReport
        lastReport = p; lastReportWallMs = now
        val moved = prev != null && Geo.distanceM(prev.lat, prev.lon, p.lat, p.lon) > max(MOVE_THRESHOLD_M, 2 * (err ?: 0.0))
        val speedMoving = (p.groundSpeedMps ?: 0) >= 2
        if (moved || speedMoving) {
            moving = true; held = null
        } else if (prev == null || !moving) {
            held = p; heldAccuracy = err; heldReceivedWallMs = now
        } else {
            // two consecutive agreeing reports after a change: treated as stationary again
            moving = false; held = p; heldAccuracy = err; heldReceivedWallMs = now
        }
        status()
    }

    /** Called every second by the engine. */
    fun tick() {
        if (!enabled) return
        val h = held ?: return
        // positionTimeMs = when the radio determined this position, so each observation records its real age.
        val posTime = h.timeS?.let { it * 1000 } ?: heldReceivedWallMs
        onFix?.invoke(Fix(h.lat, h.lon, heldAccuracy ?: dopAccuracy(), h.altitudeM?.toDouble(), clock.wallMs(), clock.monotonicMs(), FixSource.RADIO, posTime))
    }

    private fun status() {
        val h = held
        val age = lastReportWallMs?.let { ((clock.wallMs() - it) / 60_000).toInt() }
        when {
            !enabled -> {}
            moving -> say("The radio's position changed between reports, so it is not treated as stationary. Receptions are " +
                "logged without position until two reports agree again. For a moving receiver use phone, USB or Windows location GPS.")
            h == null -> say("Waiting for the radio's position (read from the radio about once a minute). " +
                "The radio needs a GPS fix or a fixed position set in it.")
            else -> {
                val kind = if (h.locationSource == 1) "radio's configured fixed position" else "radio GPS"
                val dop = dopAccuracy()
                val acc = heldAccuracy?.let { " ±${it.roundToInt()} m" }
                    ?: dop?.let { " ±${it.roundToInt()} m (estimated from the DOP in its last position broadcast" + (lastSats?.let { s -> ", $s satellites" } ?: "") + ")" }
                    ?: " (accuracy not reported yet; estimated once the radio broadcasts its position)"
                val posAge = h.timeS?.let { ((clock.wallMs() / 1000 - it) / 60).coerceAtLeast(0) }
                say("Using $kind$acc, assumed stationary" +
                    (posAge?.let { "; position determined $it min ago" } ?: (age?.let { "; last report $it min ago" } ?: "")))
            }
        }
    }

    private fun say(s: String) { if (enabled) onStatus?.invoke(s) }
}
