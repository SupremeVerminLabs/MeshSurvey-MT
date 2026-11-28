package meshsurvey.core.radio

/**
 * A physical connection to a Meshtastic radio. Implementations live in the
 * platform modules (BLE, USB serial, TCP) plus [meshsurvey.core.sim.SimulatedLink].
 * A link only moves whole protobuf messages: stream links use [StreamFramer],
 * BLE delivers one FromRadio per characteristic read.
 */
enum class LinkKind(val label: String, val isStream: Boolean) {
    SERIAL("USB serial", true),
    TCP("TCP / Wi-Fi", true),
    BLE("Bluetooth LE", false),
    SIMULATED("Simulated radio", false),
}

enum class LinkState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

/** Which firmware family a radio runs. Everything a radio logs is tagged with it. */
enum class MeshProtocol(val label: String, val tag: String) {
    MESHTASTIC("Meshtastic", "meshtastic"),
    MESHCORE("MeshCore", "meshcore");
    companion object { fun ofTag(t: String?) = entries.firstOrNull { it.tag == t } ?: MESHTASTIC }
}

interface RadioLink {
    val kind: LinkKind
    val description: String
    fun start(listener: Listener)
    /** Send one encoded ToRadio message. Returns false if it could not be queued. */
    fun send(toRadio: ByteArray): Boolean
    fun close()
    /** Called about once per second from RadioClient.tick(); used by the simulator. */
    fun tick() {}

    /**
     * The protocol, when the transport itself reveals it (which Bluetooth service the radio offers). null = unknown until
     * probed (USB serial, TCP): the client then sends a harmless MeshCore identity request first, which a Meshtastic radio ignores.
     */
    val detectedProtocol: MeshProtocol? get() = MeshProtocol.MESHTASTIC
    /** Fixes the stream framing once the protocol is known (stream links only). */
    fun setProtocol(p: MeshProtocol) {}
    /** Send one MeshCore companion frame (payload only; the link adds serial framing). False if unsupported. */
    fun sendMeshCore(frame: ByteArray): Boolean = false

    interface Listener {
        fun onLinkState(state: LinkState, detail: String = "")
        fun onFromRadio(bytes: ByteArray)
        /** Non-protobuf text from the radio (serial debug console); optional. */
        fun onDebugText(text: String) {}
        /** One MeshCore companion frame from the radio. */
        fun onMeshCoreFrame(bytes: ByteArray) {}
    }
}

/**
 * Meshtastic stream framing used by USB serial and TCP (port 4403):
 *   0x94 0xC3 <len hi> <len lo> <protobuf bytes>
 * Bytes outside frames are the device debug console and are passed through as text.
 */
class StreamFramer(private val onFrame: (ByteArray) -> Unit, private val onText: (String) -> Unit = {}) {
    companion object {
        const val START1 = 0x94
        const val START2 = 0xC3
        const val MAX_LEN = 512
        fun encode(payload: ByteArray): ByteArray {
            require(payload.size <= MAX_LEN) { "frame too large" }
            return byteArrayOf(START1.toByte(), START2.toByte(), (payload.size shr 8).toByte(), payload.size.toByte()) + payload
        }
    }

    private var buf = ByteArray(0)
    private val text = StringBuilder()

    fun feed(data: ByteArray, len: Int = data.size) {
        buf += data.copyOfRange(0, len)
        while (true) {
            val start = indexOfStart()
            if (start < 0) {
                // keep a trailing START1 that may be the first half of a header
                val keep = if (buf.isNotEmpty() && (buf.last().toInt() and 0xff) == START1) 1 else 0
                emitText(buf.copyOfRange(0, buf.size - keep))
                buf = buf.copyOfRange(buf.size - keep, buf.size)
                return
            }
            if (start > 0) { emitText(buf.copyOfRange(0, start)); buf = buf.copyOfRange(start, buf.size) }
            if (buf.size < 4) return
            val len = ((buf[2].toInt() and 0xff) shl 8) or (buf[3].toInt() and 0xff)
            if (len > MAX_LEN) { buf = buf.copyOfRange(1, buf.size); continue } // corrupt header: resync
            if (buf.size < 4 + len) return
            val frame = buf.copyOfRange(4, 4 + len)
            buf = buf.copyOfRange(4 + len, buf.size)
            onFrame(frame)
        }
    }

    private fun indexOfStart(): Int {
        for (i in 0 until buf.size - 1) {
            if ((buf[i].toInt() and 0xff) == START1 && (buf[i + 1].toInt() and 0xff) == START2) return i
        }
        return -1
    }

    private fun emitText(b: ByteArray) {
        if (b.isEmpty()) return
        for (byte in b) {
            val c = (byte.toInt() and 0xff).toChar()
            if (c == '\n') { if (text.isNotEmpty()) onText(text.toString()); text.clear() }
            else if (c != '\r' && text.length < 1000) text.append(c)
        }
    }
}
