package meshsurvey.core.gps

import kotlin.math.sqrt

/** Minimal NMEA 0183 parsing for external USB GPS receivers: GGA (position) and GST (error estimate). */
object Nmea {
    sealed class Sentence {
        data class Gga(val lat: Double?, val lon: Double?, val quality: Int, val satellites: Int?, val altitudeM: Double?,
                       val utcSecondOfDay: Double? = null) : Sentence() {
            /**
             * 1 GPS, 2 DGPS, 3 PPS, 4 RTK, 5 float RTK are measured fixes. 0 no fix, 6 estimated (dead reckoning,
             * often the receiver's last position), 7 manual input, 8 simulation are NOT accepted as positions.
             */
            val isMeasuredFix get() = quality in 1..5 && lat != null && lon != null
        }
        /** Receiver-reported 1-sigma horizontal error (metres) from the lat/lon standard deviations. */
        data class Gst(val horizontalSigmaM: Double) : Sentence()
    }

    fun checksumOk(s: String): Boolean {
        if (!s.startsWith("$")) return false
        val star = s.lastIndexOf('*'); if (star < 0) return false
        var c = 0
        for (i in 1 until star) c = c xor s[i].code
        return s.substring(star + 1).take(2).toIntOrNull(16) == c
    }

    fun coord(v: String, hemi: String): Double? {
        if (v.isEmpty()) return null
        val dot = v.indexOf('.').let { if (it < 0) v.length else it }
        if (dot < 3) return null
        val deg = v.substring(0, dot - 2).toDoubleOrNull() ?: return null
        val min = v.substring(dot - 2).toDoubleOrNull() ?: return null
        if (min >= 60) return null
        val d = deg + min / 60
        return if (hemi == "S" || hemi == "W") -d else d
    }

    fun parse(line: String): Sentence? {
        val t = line.trim()
        if (!checksumOk(t)) return null
        val f = t.substringBefore('*').split(',')
        if (f[0].length < 6) return null
        return when (f[0].drop(3)) {
            "GGA" -> Sentence.Gga(
                lat = coord(f.getOrElse(2) { "" }, f.getOrElse(3) { "" }),
                lon = coord(f.getOrElse(4) { "" }, f.getOrElse(5) { "" }),
                quality = f.getOrNull(6)?.toIntOrNull() ?: 0,
                satellites = f.getOrNull(7)?.toIntOrNull(),
                altitudeM = f.getOrNull(9)?.toDoubleOrNull(),
                utcSecondOfDay = f.getOrNull(1)?.takeIf { it.length >= 6 }?.let { t ->
                    val h = t.substring(0, 2).toIntOrNull(); val m = t.substring(2, 4).toIntOrNull(); val sec = t.substring(4).toDoubleOrNull()
                    if (h == null || m == null || sec == null) null else h * 3600 + m * 60 + sec
                },
            )
            "GST" -> {
                val a = f.getOrNull(6)?.toDoubleOrNull(); val b = f.getOrNull(7)?.toDoubleOrNull()
                if (a != null && b != null) Sentence.Gst(sqrt(a * a + b * b)) else null
            }
            else -> null
        }
    }
}

/** Seconds between the receiver's UTC time-of-day and the host's, wrapped to ±12 h. Large values = stale data or wrong host clock. */
fun gpsClockSkewS(gpsSecondOfDay: Double, hostWallMs: Long): Double {
    val host = (hostWallMs / 1000.0) % 86_400.0
    var d = gpsSecondOfDay - host
    if (d > 43_200) d -= 86_400.0
    if (d < -43_200) d += 86_400.0
    return d
}
