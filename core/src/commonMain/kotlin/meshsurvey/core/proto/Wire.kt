package meshsurvey.core.proto

/**
 * Minimal protobuf wire-format codec.
 *
 * The core deliberately avoids generated protobuf classes so that it has no
 * native/platform dependencies and can be unit-tested anywhere. Field numbers
 * used elsewhere are taken from meshtastic/protobufs (mesh.proto, config.proto,
 * portnums.proto, telemetry.proto) and are listed in docs/API_CAPABILITIES.md.
 */
/** One decoded field. [start]/[end] are the byte offsets of the whole field (tag included) in the parsed buffer. */
class PField(val num: Int, val wire: Int, val raw: Long, val bytes: ByteArray?, val start: Int = 0, val end: Int = 0) {
    val int: Int get() = raw.toInt()
    val u32: Long get() = raw and 0xffffffffL
    val float: Float get() = Float.fromBits(raw.toInt())
    val bool: Boolean get() = raw != 0L
    val str: String? get() = bytes?.decodeToString()
}

class ProtoException(msg: String) : Exception(msg)

fun List<PField>.f(n: Int): PField? = firstOrNull { it.num == n }
fun List<PField>.all(n: Int): List<PField> = filter { it.num == n }

object Wire {
    const val VARINT = 0
    const val FIXED64 = 1
    const val LEN = 2
    const val FIXED32 = 5

    fun parse(buf: ByteArray): List<PField> {
        val out = ArrayList<PField>()
        var p = 0
        fun varint(): Long {
            var r = 0L
            var s = 0
            while (true) {
                if (p >= buf.size) throw ProtoException("truncated varint")
                val b = buf[p++].toInt() and 0xff
                r = r or ((b and 0x7f).toLong() shl s)
                if (b and 0x80 == 0) return r
                s += 7
                if (s > 63) throw ProtoException("varint too long")
            }
        }
        fun fixed(n: Int): Long {
            if (p + n > buf.size) throw ProtoException("truncated fixed$n")
            var r = 0L
            for (i in 0 until n) r = r or ((buf[p + i].toLong() and 0xff) shl (8 * i))
            p += n
            return r
        }
        while (p < buf.size) {
            val start = p
            val tag = varint()
            val num = (tag ushr 3).toInt()
            if (num <= 0) throw ProtoException("bad field number")
            when (val wire = (tag and 7).toInt()) {
                VARINT -> { val v = varint(); out += PField(num, wire, v, null, start, p) }
                FIXED64 -> { val v = fixed(8); out += PField(num, wire, v, null, start, p) }
                LEN -> {
                    val len = varint()
                    if (len < 0 || p + len > buf.size) throw ProtoException("bad length")
                    val b = buf.copyOfRange(p, p + len.toInt())
                    p += len.toInt()
                    out += PField(num, wire, len, b, start, p)
                }
                FIXED32 -> { val v = fixed(4); out += PField(num, wire, v, null, start, p) }
                else -> throw ProtoException("unsupported wire type $wire")
            }
        }
        return out
    }

    /**
     * Returns [buf] with the given varint fields replaced (or appended if absent), every other
     * field preserved byte-for-byte in its original order. Used to change only use_preset and
     * modem_preset in a LoRaConfig without touching fields this app doesn't model.
     * A value of 0 removes the field (proto3 default).
     */
    fun patchVarints(buf: ByteArray, values: Map<Int, Long>): ByteArray {
        val fields = parse(buf)
        val out = ArrayList<Byte>(buf.size + 8)
        val done = HashSet<Int>()
        for (f in fields) {
            val v = values[f.num]
            if (v == null) { for (i in f.start until f.end) out += buf[i]; continue }
            if (f.wire != VARINT) throw ProtoException("field ${f.num} is not a varint")
            if (f.num in done) continue // drop repeated occurrences of a patched scalar
            done += f.num
            if (v != 0L) out.addAll(ProtoWriter().varint(f.num, v).toByteArray().toList())
        }
        for ((num, v) in values.entries.sortedBy { it.key }) {
            if (num !in done && v != 0L) out.addAll(ProtoWriter().varint(num, v).toByteArray().toList())
        }
        return out.toByteArray()
    }

    /** Field numbers whose values differ between the two buffers. */
    fun diffFields(a: ByteArray, b: ByteArray): List<Int> {
        fun m(buf: ByteArray) = parse(buf).groupBy { it.num }.mapValues { (_, v) -> v.map { it.raw to it.bytes?.toList() }.sortedBy { it.first } }
        val ma = m(a); val mb = m(b)
        return (ma.keys + mb.keys).filter { ma[it] != mb[it] }.sorted()
    }

    /** True if both buffers contain the same set of fields and values, regardless of order. */
    fun sameFields(a: ByteArray, b: ByteArray): Boolean {
        fun key(buf: ByteArray) = parse(buf).map { f -> Triple(f.num, f.raw, f.bytes?.toList()) }.sortedWith(compareBy({ it.first }, { it.second }))
        return key(a) == key(b)
    }
}

/** Builder for outgoing messages. Proto3 semantics: default values are omitted. */
class ProtoWriter {
    private val buf = ArrayList<Byte>(64)

    private fun rawVarint(v: Long) {
        var x = v
        while (true) {
            if (x and 0x7fL.inv() == 0L) { buf += x.toByte(); return }
            buf += ((x and 0x7f) or 0x80).toByte()
            x = x ushr 7
        }
    }
    private fun tag(num: Int, wire: Int) = rawVarint(((num shl 3) or wire).toLong())
    private fun rawFixed(v: Long, n: Int) { for (i in 0 until n) buf += ((v ushr (8 * i)) and 0xff).toByte() }

    /** uint32/uint64/enum/bool. Negative int32 values are sign-extended (10 bytes), as protobuf requires. */
    fun varint(num: Int, v: Long): ProtoWriter { if (v != 0L) { tag(num, Wire.VARINT); rawVarint(v) }; return this }
    fun int32(num: Int, v: Int) = varint(num, v.toLong())
    fun uint32(num: Int, v: Long) = varint(num, v and 0xffffffffL)
    fun bool(num: Int, v: Boolean) = varint(num, if (v) 1 else 0)
    fun fixed32(num: Int, v: Long): ProtoWriter { if (v != 0L) { tag(num, Wire.FIXED32); rawFixed(v, 4) }; return this }
    fun sfixed32(num: Int, v: Int) = fixed32(num, v.toLong() and 0xffffffffL)
    fun float(num: Int, v: Float): ProtoWriter { if (v != 0f) { tag(num, Wire.FIXED32); rawFixed(v.toRawBits().toLong(), 4) }; return this }
    fun bytes(num: Int, v: ByteArray?): ProtoWriter {
        if (v == null || v.isEmpty()) return this
        tag(num, Wire.LEN); rawVarint(v.size.toLong()); v.forEach { buf += it }; return this
    }
    fun string(num: Int, v: String?) = bytes(num, v?.encodeToByteArray())
    fun message(num: Int, w: ProtoWriter): ProtoWriter = messageAlways(num, w.toByteArray())
    /** Embedded message written even when empty (presence matters for oneof fields such as heartbeat). */
    fun messageAlways(num: Int, b: ByteArray): ProtoWriter {
        tag(num, Wire.LEN); rawVarint(b.size.toLong()); b.forEach { buf += it }; return this
    }
    fun toByteArray(): ByteArray = buf.toByteArray()
}
