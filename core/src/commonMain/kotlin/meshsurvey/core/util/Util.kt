package meshsurvey.core.util

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Wall-clock (for timestamps) and monotonic (for intervals) time, injectable for tests. */
interface Clock {
    fun wallMs(): Long
    fun monotonicMs(): Long
}

class ManualClock(var wall: Long = 1_764_000_000_000L, var mono: Long = 1_000_000L) : Clock {
    override fun wallMs() = wall
    override fun monotonicMs() = mono
    fun advance(ms: Long) { wall += ms; mono += ms }
}

object Geo {
    const val EARTH_M = 6_371_000.0
    fun distanceM(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = kotlin.math.PI / 180.0
        val dLa = (la2 - la1) * r
        val dLo = (lo2 - lo1) * r
        val a = sin(dLa / 2) * sin(dLa / 2) + cos(la1 * r) * cos(la2 * r) * sin(dLo / 2) * sin(dLo / 2)
        return 2 * EARTH_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
    fun validLatLon(lat: Double?, lon: Double?) =
        lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0) &&
            !lat.isNaN() && !lon.isNaN()
}

fun newId(random: Random = Random.Default, bytes: Int = 8): String =
    (0 until bytes).joinToString("") { random.nextInt(256).toString(16).padStart(2, '0') }

fun ByteArray.toHex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0) return null
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return null }
}

/** SHA-256 (FIPS 180-4), used for import de-duplication. */
object Sha256 {
    private val K = listOf(
        0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u, 0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u, 0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau, 0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u, 0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u, 0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u, 0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u, 0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u,
    ).map { it.toInt() }.toIntArray()

    fun hex(data: ByteArray): String = digest(data).toHex()

    fun digest(data: ByteArray): ByteArray {
        val h = listOf(0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au, 0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u).map { it.toInt() }.toIntArray()
        val bitLen = data.size.toLong() * 8
        val padLen = ((data.size + 9 + 63) / 64) * 64
        val m = ByteArray(padLen)
        data.copyInto(m)
        m[data.size] = 0x80.toByte()
        for (i in 0 until 8) m[padLen - 1 - i] = (bitLen ushr (8 * i)).toByte()
        val w = IntArray(64)
        for (chunk in 0 until padLen step 64) {
            for (i in 0 until 16) {
                w[i] = ((m[chunk + 4 * i].toInt() and 0xff) shl 24) or ((m[chunk + 4 * i + 1].toInt() and 0xff) shl 16) or
                    ((m[chunk + 4 * i + 2].toInt() and 0xff) shl 8) or (m[chunk + 4 * i + 3].toInt() and 0xff)
            }
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        val out = ByteArray(32)
        for (i in 0 until 8) for (j in 0 until 4) out[i * 4 + j] = (h[i] ushr (24 - 8 * j)).toByte()
        return out
    }
}
