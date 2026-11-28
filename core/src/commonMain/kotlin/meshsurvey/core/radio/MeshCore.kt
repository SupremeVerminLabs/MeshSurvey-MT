package meshsurvey.core.radio

import meshsurvey.core.util.toHex

/**
 * MeshCore companion-radio protocol (the protocol the MeshCore phone app uses), listen-only subset.
 *
 * Transport framing:
 *  - USB serial / TCP: app → radio frames are `'<' len_lo len_hi payload`, radio → app frames are `'>' len_lo len_hi payload`.
 *  - Bluetooth LE: Nordic UART service; one characteristic write / notification is one frame, no header.
 *
 * Commands this app sends are all local requests answered by the radio itself; none of them makes the radio transmit
 * over LoRa: APP_START, DEVICE_QUERY, GET_CONTACTS, GET_BATT_AND_STORAGE. The app never sends SYNC_NEXT_MESSAGE, so the
 * user's queued messages stay on the radio for their own app.
 */
object MeshCore {
    // commands (app -> radio)
    const val CMD_APP_START = 1
    const val CMD_GET_CONTACTS = 4
    const val CMD_GET_BATT_AND_STORAGE = 20
    const val CMD_DEVICE_QUERY = 22

    // responses (radio -> app)
    const val RESP_OK = 0
    const val RESP_ERR = 1
    const val RESP_CONTACTS_START = 2
    const val RESP_CONTACT = 3
    const val RESP_END_OF_CONTACTS = 4
    const val RESP_SELF_INFO = 5
    const val RESP_BATT_AND_STORAGE = 12
    const val RESP_DEVICE_INFO = 13

    // pushes (radio -> app, unsolicited)
    const val PUSH_ADVERT = 0x80
    const val PUSH_MSG_WAITING = 0x83
    const val PUSH_LOG_RX_DATA = 0x88
    const val PUSH_NEW_ADVERT = 0x8A

    /** Protocol version this app announces (DEVICE_QUERY app_target_ver). */
    const val APP_TARGET_VER = 3

    fun appStart(name: String = "MeshSurvey"): ByteArray =
        byteArrayOf(CMD_APP_START.toByte(), 3, 0, 0, 0, 0, 0, 0) + name.encodeToByteArray()
    fun deviceQuery(): ByteArray = byteArrayOf(CMD_DEVICE_QUERY.toByte(), APP_TARGET_VER.toByte())
    fun getContacts(): ByteArray = byteArrayOf(CMD_GET_CONTACTS.toByte())
    fun getBattery(): ByteArray = byteArrayOf(CMD_GET_BATT_AND_STORAGE.toByte())

    /** Commands this app is allowed to send to a MeshCore radio (all local, none transmits over LoRa). */
    val ALLOWED_COMMANDS = setOf(CMD_APP_START, CMD_GET_CONTACTS, CMD_GET_BATT_AND_STORAGE, CMD_DEVICE_QUERY)

    // ---------------------------------------------------------------- serial framing
    const val TO_RADIO = 0x3C   // '<'
    const val FROM_RADIO = 0x3E // '>'
    const val MAX_FRAME = 300

    fun frameToRadio(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_FRAME) { "frame too large" }
        return byteArrayOf(TO_RADIO.toByte(), payload.size.toByte(), (payload.size shr 8).toByte()) + payload
    }

    // ---------------------------------------------------------------- decoding helpers
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xff
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u32(b: ByteArray, i: Int) = (u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16))
    private fun i32(b: ByteArray, i: Int) = u32(b, i).toInt()
    private fun cstr(b: ByteArray, from: Int, max: Int): String {
        val end = minOf(b.size, from + max)
        if (from >= end) return ""
        var e = from
        while (e < end && b[e] != 0.toByte()) e++
        return b.copyOfRange(from, e).decodeToString().trim()
    }
    /** MeshCore stores positions as degrees × 1e6; 0,0 means "no position". */
    private fun latLon(lat: Int, lon: Int): Pair<Double, Double>? {
        if (lat == 0 && lon == 0) return null
        val la = lat / 1e6; val lo = lon / 1e6
        return if (la in -90.0..90.0 && lo in -180.0..180.0) la to lo else null
    }

    data class SelfInfo(
        val advType: Int, val txPowerDbm: Int, val maxTxPowerDbm: Int, val publicKey: ByteArray,
        val lat: Double?, val lon: Double?, val frequencyMhz: Double?, val bandwidthKhz: Double?,
        val spreadingFactor: Int?, val codingRate: Int?, val name: String,
    )

    /** RESP_SELF_INFO: type, tx power, max tx power, pubkey[32], lat, lon, 4 flag bytes, freq (kHz), bw (Hz), sf, cr, name. */
    fun decodeSelfInfo(f: ByteArray): SelfInfo? {
        if (f.isEmpty() || u8(f, 0) != RESP_SELF_INFO || f.size < 1 + 3 + 32 + 8) return null
        val key = f.copyOfRange(4, 36)
        val pos = latLon(i32(f, 36), i32(f, 40))
        val radio = f.size >= 58
        val freq = if (radio) u32(f, 48) / 1000.0 else null
        val bw = if (radio) u32(f, 52) / 1000.0 else null
        return SelfInfo(u8(f, 1), f[2].toInt(), f[3].toInt(), key, pos?.first, pos?.second,
            freq?.takeIf { it in 100.0..3000.0 }, bw?.takeIf { it > 0 }, if (radio) u8(f, 56) else null, if (radio) u8(f, 57) else null,
            if (f.size > 58) cstr(f, 58, 64) else "")
    }

    data class DeviceInfo(val firmwareCode: Int, val buildDate: String?, val model: String?, val version: String?)

    /** RESP_DEVICE_INFO: fw version code, max contacts/2, max channels, ble pin (4), build date (12), model (40), version (20). */
    fun decodeDeviceInfo(f: ByteArray): DeviceInfo? {
        if (f.size < 2 || u8(f, 0) != RESP_DEVICE_INFO) return null
        if (f.size < 8) return DeviceInfo(u8(f, 1), null, null, null)
        return DeviceInfo(u8(f, 1), cstr(f, 8, 12).ifEmpty { null }, cstr(f, 20, 40).ifEmpty { null }, cstr(f, 60, 20).ifEmpty { null })
    }

    /** Node types used in adverts and contacts. */
    fun nodeType(t: Int) = when (t) { 1 -> "companion"; 2 -> "repeater"; 3 -> "room server"; 4 -> "sensor"; else -> "node type $t" }

    data class Contact(val publicKey: ByteArray, val type: Int, val name: String, val lastAdvertS: Long, val lat: Double?, val lon: Double?) {
        val keyHex get() = publicKey.toHex()
    }

    /** RESP_CONTACT / PUSH_NEW_ADVERT: pubkey[32], type, flags, out_path_len, out_path[64], name[32], last_advert, lat, lon, lastmod. */
    fun decodeContact(f: ByteArray): Contact? {
        if (f.isEmpty() || (u8(f, 0) != RESP_CONTACT && u8(f, 0) != PUSH_NEW_ADVERT) || f.size < 1 + 32 + 3 + 64 + 32 + 4) return null
        val key = f.copyOfRange(1, 33)
        val name = cstr(f, 100, 32)
        val last = u32(f, 132)
        val pos = if (f.size >= 144) latLon(i32(f, 136), i32(f, 140)) else null
        return Contact(key, u8(f, 33), name, last, pos?.first, pos?.second)
    }

    /** RESP_BATT_AND_STORAGE: millivolts (u16), then storage figures. */
    fun decodeBatteryMv(f: ByteArray): Int? = if (f.size >= 3 && u8(f, 0) == RESP_BATT_AND_STORAGE) u16(f, 1).takeIf { it in 1..10_000 } else null

    // ---------------------------------------------------------------- over-the-air packets (PUSH_LOG_RX_DATA)

    enum class Route { TRANSPORT_FLOOD, FLOOD, DIRECT, TRANSPORT_DIRECT }

    enum class PayloadType(val code: Int) {
        REQ(0), RESPONSE(1), TXT_MSG(2), ACK(3), ADVERT(4), GRP_TXT(5), GRP_DATA(6), ANON_REQ(7), PATH(8), TRACE(9), MULTIPART(10),
        CONTROL(11), RAW_CUSTOM(15), UNKNOWN(-1);
        companion object { fun of(c: Int) = entries.firstOrNull { it.code == c } ?: UNKNOWN }
    }

    data class Advert(val publicKey: ByteArray, val type: Int, val lat: Double?, val lon: Double?, val name: String?) {
        val keyHex get() = publicKey.toHex()
    }

    class RxPacket(
        val snr: Double,
        val rssi: Int,
        val route: Route,
        val payloadType: PayloadType,
        /** Repeater hashes the packet passed through (flood routing appends one per repeat). */
        val path: List<ByteArray>,
        val payload: ByteArray,
        val advert: Advert?,
        /** Source hash of addressed payloads (first byte of the sender's public key), if the payload type carries one. */
        val sourceHash: Int?,
        val rawLength: Int,
    ) {
        val isFlood get() = route == Route.FLOOD || route == Route.TRANSPORT_FLOOD
        val hops get() = path.size

        /**
         * The node this radio heard the transmission from, as a public-key prefix (hex): the last repeater in a flood path,
         * or the sender itself for a zero-hop flood packet. Unknown for direct-routed packets (each repeater removes itself).
         */
        val heardPrefix: String? get() = when {
            !isFlood -> null
            path.isNotEmpty() -> path.last().toHex()
            advert != null -> advert.keyHex
            sourceHash != null -> sourceHash.toString(16).padStart(2, '0')
            else -> null
        }

        /** Content fingerprint, the same for every copy of one packet whichever path it took (used to match two receivers). */
        val fingerprint: Long get() {
            var h = 0x811c9dc5L
            fun mix(b: Int) { h = ((h xor (b.toLong() and 0xff)) * 0x01000193L) and 0xffffffffL }
            mix(payloadType.code)
            payload.forEach { mix(it.toInt()) }
            return h
        }
    }

    /**
     * Parses a PUSH_LOG_RX_DATA frame: [0x88][snr×4 (int8)][rssi (int8)][raw packet].
     * Raw packet: header (route bits 0-1, payload type bits 2-5), transport codes (4 bytes, transport routes only), path length,
     * path, payload. Path length: low 6 bits = hash count, high 2 bits = hash size − 1 (1-byte hashes on older firmware).
     */
    fun decodeRx(f: ByteArray): RxPacket? {
        if (f.size < 5 || u8(f, 0) != PUSH_LOG_RX_DATA) return null
        val snr = f[1].toInt() / 4.0
        val rssi = f[2].toInt()
        val raw = f.copyOfRange(3, f.size)
        var i = 0
        val header = u8(raw, i++)
        val route = Route.entries[header and 3]
        val type = PayloadType.of((header shr 2) and 0x0f)
        if (route == Route.TRANSPORT_FLOOD || route == Route.TRANSPORT_DIRECT) i += 4
        if (i >= raw.size) return null
        val pl = u8(raw, i++)
        val count = pl and 0x3f
        val hashSize = (pl shr 6) + 1
        if (i + count * hashSize > raw.size) return null
        val path = (0 until count).map { n -> raw.copyOfRange(i + n * hashSize, i + (n + 1) * hashSize) }
        i += count * hashSize
        val payload = raw.copyOfRange(i, raw.size)
        val advert = if (type == PayloadType.ADVERT) decodeAdvert(payload) else null
        val src = when (type) {
            PayloadType.REQ, PayloadType.RESPONSE, PayloadType.TXT_MSG, PayloadType.PATH -> if (payload.size >= 2) u8(payload, 1) else null
            PayloadType.ANON_REQ -> if (payload.size >= 2) u8(payload, 1) else null   // first byte of the sender's public key
            else -> null
        }
        return RxPacket(snr, rssi, route, type, path, payload, advert, src, raw.size)
    }

    /** Advert payload: pubkey[32], timestamp (4), signature[64], flags (type in low 4 bits; 0x10 lat/lon, 0x20/0x40 features, 0x80 name). */
    fun decodeAdvert(p: ByteArray): Advert? {
        if (p.size < 101) return null
        val key = p.copyOfRange(0, 32)
        var i = 100
        val flags = u8(p, i++)
        var pos: Pair<Double, Double>? = null
        if (flags and 0x10 != 0) { if (i + 8 > p.size) return Advert(key, flags and 0x0f, null, null, null); pos = latLon(i32(p, i), i32(p, i + 4)); i += 8 }
        if (flags and 0x20 != 0) i += 2
        if (flags and 0x40 != 0) i += 2
        val name = if (flags and 0x80 != 0 && i < p.size) p.copyOfRange(i, p.size).decodeToString().trim { it <= ' ' || it == '\u0000' }.ifEmpty { null } else null
        return Advert(key, flags and 0x0f, pos?.first, pos?.second, name)
    }
}

/**
 * Stream framing for MeshCore (radio → app frames `'>' len_lo len_hi payload`). Bytes outside frames are ignored.
 * [accept] decides whether a candidate frame is plausible (used while probing, so stray '>' characters in a
 * Meshtastic debug console are not taken for MeshCore frames).
 */
class MeshCoreFramer(private val onFrame: (ByteArray) -> Unit, var accept: (ByteArray) -> Boolean = { true }) {
    private var buf = ByteArray(0)

    fun feed(data: ByteArray, len: Int = data.size) {
        buf += data.copyOfRange(0, len)
        while (true) {
            val start = buf.indexOfFirst { (it.toInt() and 0xff) == MeshCore.FROM_RADIO }
            if (start < 0) { buf = ByteArray(0); return }
            if (start > 0) buf = buf.copyOfRange(start, buf.size)
            if (buf.size < 3) return
            val n = (buf[1].toInt() and 0xff) or ((buf[2].toInt() and 0xff) shl 8)
            if (n == 0 || n > MeshCore.MAX_FRAME) { buf = buf.copyOfRange(1, buf.size); continue }
            if (buf.size < 3 + n) {
                if (buf.size > 3 + MeshCore.MAX_FRAME) buf = buf.copyOfRange(1, buf.size) else return
                continue
            }
            val frame = buf.copyOfRange(3, 3 + n)
            if (!accept(frame)) { buf = buf.copyOfRange(1, buf.size); continue }
            buf = buf.copyOfRange(3 + n, buf.size)
            onFrame(frame)
        }
    }
}

/**
 * Identities of MeshCore nodes inside the app's 64-bit node-number space, kept far above Meshtastic's 32-bit node numbers so
 * the two can never collide. Most MeshCore packets only carry a 1-byte public-key prefix, so an id records how many bytes it knows.
 */
object MeshCoreIds {
    const val BASE = 1L shl 44
    val UNKNOWN = BASE

    fun isMeshCore(id: Long) = id >= BASE && id < (BASE shl 1)

    /** Id from the first bytes (1 to 4) of a public key or hash. */
    fun fromPrefix(bytes: ByteArray): Long {
        val n = bytes.size.coerceAtMost(4)
        if (n == 0) return UNKNOWN
        var v = 0L
        for (k in 0 until n) v = (v shl 8) or (bytes[k].toLong() and 0xff)
        return BASE or (n.toLong() shl 36) or v
    }

    fun fromHash(b: Int) = fromPrefix(byteArrayOf(b.toByte()))

    /** Display form, e.g. "mc:a3" or "mc:a3f19c07"; "mc:?" when the sender is not identifiable. */
    fun label(id: Long): String {
        val n = ((id shr 36) and 0xf).toInt()
        if (n == 0) return "mc:?"
        return "mc:" + (id and 0xffffffffL).toString(16).padStart(n * 2, '0')
    }
}
