package meshsurvey.core.radio

import meshsurvey.core.util.hexToBytes
import meshsurvey.core.util.toHex
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Client for the `meshsurvey-ble` helper process (desktopApp/native/windows-ble), which gives the
 * desktop app Bluetooth LE on Windows. The helper speaks one text line per message over
 * stdin/stdout (protocol documented at the top of meshsurvey-ble.cpp).
 *
 * The helper only moves Meshtastic phone-API bytes. Every rule about what may be transmitted
 * stays in [RadioClient] and the A/B test controller, exactly as for USB, TCP and Android BLE.
 */
class BleHelperProcess(
    command: List<String>,
    private val onLine: (String) -> Unit,
    private val onExit: (Int?) -> Unit,
) {
    private val proc: Process = ProcessBuilder(command).start()
    private val out = proc.outputStream.bufferedWriter()

    init {
        thread(isDaemon = true, name = "ble-helper-stdout") {
            try { proc.inputStream.bufferedReader().forEachLine { onLine(it.trimEnd()) } } catch (_: IOException) {}
            onExit(runCatching { proc.waitFor(); proc.exitValue() }.getOrNull())
        }
        thread(isDaemon = true, name = "ble-helper-stderr") {
            try { proc.errorStream.bufferedReader().forEachLine { onLine("log stderr: $it") } } catch (_: IOException) {}
        }
    }

    val alive: Boolean get() = proc.isAlive

    @Synchronized
    fun send(line: String): Boolean = try { out.write(line); out.write("\n"); out.flush(); true } catch (_: IOException) { false }

    /** Asks the helper to quit, then makes sure it is gone. Blocks up to ~2 s; call off the UI thread. */
    fun close() {
        send("quit")
        runCatching { out.close() }
        if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly()
    }
}

/** [protocol] null = not seen advertising (a paired device); the protocol is then found when connecting. */
data class BleDevice(val address: String, val name: String, val rssi: Int?, val paired: Boolean, val protocol: MeshProtocol? = MeshProtocol.MESHTASTIC)

sealed class BleScanResult {
    data class Found(val devices: List<BleDevice>) : BleScanResult()
    data class Unavailable(val reason: String) : BleScanResult()
}

object BleHelperScan {
    /** Parses `dev|devmc <address> <rssi|-> <paired 0|1> <name…>` (devmc = advertises the MeshCore / Nordic UART service). */
    fun parseDevice(line: String): BleDevice? {
        val p = line.split(" ", limit = 5)
        if (p.size < 4 || (p[0] != "dev" && p[0] != "devmc")) return null
        val mc = p[0] == "devmc"
        val paired = p[3] == "1"
        val proto = when { mc -> MeshProtocol.MESHCORE; paired && p[2] == "-" -> null; else -> MeshProtocol.MESHTASTIC }
        return BleDevice(p[1], p.getOrNull(4)?.trim().orEmpty().ifEmpty { if (mc) "MeshCore" else "Meshtastic" }, p[2].toIntOrNull(), paired, proto)
    }

    fun adapterMessage(state: String): String? = when (state) {
        "ok" -> null
        "off" -> "Bluetooth is turned off. Turn it on in Windows Settings → Bluetooth & devices."
        "none" -> "No Bluetooth adapter found on this PC."
        "nole" -> "This PC's Bluetooth adapter does not support Bluetooth Low Energy."
        else -> "Bluetooth adapter state unknown ($state)."
    }

    /** Runs a short-lived helper: checks the adapter, scans [seconds], lists Meshtastic radios. Blocking. */
    fun scan(command: List<String>, seconds: Int = 5): BleScanResult {
        val lines = LinkedBlockingQueue<String>()
        val helper = try {
            BleHelperProcess(command, { lines.put(it) }, { lines.put("__exit $it") })
        } catch (e: IOException) {
            return BleScanResult.Unavailable("Bluetooth helper could not start: ${e.message}")
        }
        try {
            fun next(timeoutMs: Long): String? = lines.poll(timeoutMs, TimeUnit.MILLISECONDS)
            fun await(prefix: String, timeoutMs: Long): String? {
                val end = System.currentTimeMillis() + timeoutMs
                while (true) {
                    val left = end - System.currentTimeMillis()
                    if (left <= 0) return null
                    val l = next(left) ?: return null
                    if (l.startsWith("__exit")) return l
                    if (l.startsWith(prefix)) return l
                }
            }
            helper.send("adapter")
            val a = await("adapter", 10_000) ?: return BleScanResult.Unavailable("Bluetooth helper did not respond.")
            if (a.startsWith("__exit")) return BleScanResult.Unavailable("Bluetooth helper stopped unexpectedly.")
            adapterMessage(a.removePrefix("adapter").trim())?.let { return BleScanResult.Unavailable(it) }
            helper.send("scan $seconds")
            val found = LinkedHashMap<String, BleDevice>()
            val end = System.currentTimeMillis() + (seconds + 15) * 1000L
            while (System.currentTimeMillis() < end) {
                val l = next(end - System.currentTimeMillis()) ?: break
                when {
                    l == "scan-done" || l.startsWith("__exit") -> break
                    l.startsWith("dev ") || l.startsWith("devmc ") -> parseDevice(l)?.let { found[it.address] = it }
                    l.startsWith("error scan") -> return BleScanResult.Unavailable("Bluetooth scan failed: ${l.removePrefix("error scan").trim()}")
                }
            }
            return BleScanResult.Found(found.values.toList())
        } finally {
            helper.close()
        }
    }
}

/**
 * A [RadioLink] over the helper process. Pairs first when a PIN is given (Meshtastic radios
 * require an encrypted, paired link), then connects. Callbacks are delivered through [post] so the
 * engine sees them on its single thread, like every other link.
 */
class HelperBleLink(
    private val command: List<String>,
    private val address: String,
    private val name: String,
    private val pin: String?,
    private val post: (Runnable) -> Unit,
) : RadioLink {
    override val kind = LinkKind.BLE
    override val description = "Bluetooth $name"
    private var helper: BleHelperProcess? = null
    private var listener: RadioLink.Listener? = null
    @Volatile private var connected = false
    @Volatile private var closed = false
    /** Reported by the helper on connect (which Bluetooth service the radio offers). */
    @Volatile private var proto = MeshProtocol.MESHTASTIC
    override val detectedProtocol: MeshProtocol get() = proto
    /** When pairing/connecting was requested; the link fails if the helper has not answered within [CONNECT_TIMEOUT_MS]. */
    private var connectStartedMs: Long? = null

    companion object { const val CONNECT_TIMEOUT_MS = 100_000L }

    override fun tick() {
        val t = connectStartedMs ?: return
        if (connected || closed || System.currentTimeMillis() - t < CONNECT_TIMEOUT_MS) return
        connectStartedMs = null
        listener?.onLinkState(LinkState.FAILED, "No answer from the radio over Bluetooth. Make sure no phone app is connected to it, " +
            "and enter its PIN (shown on its screen; 123456 on radios without one).")
        helper?.let { h -> helper = null; thread(isDaemon = true) { h.close() } }
    }

    override fun start(listener: RadioLink.Listener) {
        this.listener = listener
        listener.onLinkState(LinkState.CONNECTING, "$description: starting Bluetooth")
        helper = try {
            BleHelperProcess(command, { l -> post(Runnable { handle(l) }) }, { code -> post(Runnable { exited(code) }) })
        } catch (e: IOException) {
            listener.onLinkState(LinkState.FAILED, "Bluetooth helper could not start: ${e.message}")
            null
        }
    }

    private fun handle(l: String) {
        if (closed) return
        val ls = listener ?: return
        val h = helper ?: return
        when {
            l.startsWith("ready") -> {
                connectStartedMs = System.currentTimeMillis()
                if (!pin.isNullOrBlank()) { ls.onLinkState(LinkState.CONNECTING, "$description: pairing"); h.send("pair $address ${pin.trim()}") }
                else { ls.onLinkState(LinkState.CONNECTING, "$description: connecting"); h.send("connect $address") }
            }
            l == "paired" -> { ls.onLinkState(LinkState.CONNECTING, "$description: connecting"); h.send("connect $address") }
            l.startsWith("error pair") -> ls.onLinkState(LinkState.FAILED,
                "Pairing failed: ${l.removePrefix("error pair").trim()}. Check the PIN: radios with a screen show it; " +
                    "screenless radios such as the RAK4631 use 123456 unless changed.")
            l.startsWith("connected") -> {
                proto = if (l.split(" ").getOrNull(2) == "meshcore") MeshProtocol.MESHCORE else MeshProtocol.MESHTASTIC
                connected = true; connectStartedMs = null; ls.onLinkState(LinkState.CONNECTED, description)
            }
            l.startsWith("log connect:") -> ls.onLinkState(LinkState.CONNECTING, "$description: " + l.removePrefix("log connect:").trim())
            l.startsWith("error connect") -> ls.onLinkState(LinkState.FAILED, "Bluetooth: ${l.removePrefix("error connect").trim()}")
            l.startsWith("rx ") -> l.substring(3).hexToBytes()?.let { if (proto == MeshProtocol.MESHCORE) ls.onMeshCoreFrame(it) else ls.onFromRadio(it) }
                ?: ls.onDebugText("Bluetooth helper sent malformed data")
            l == "lost" -> { connected = false; ls.onLinkState(LinkState.DISCONNECTED, "Bluetooth link lost") }
            l.startsWith("error ") || l.startsWith("log ") -> ls.onDebugText("Bluetooth: " + l.substringAfter(' '))
        }
    }

    private fun exited(code: Int?) {
        if (closed) return
        val was = connected
        connected = false
        listener?.onLinkState(if (was) LinkState.DISCONNECTED else LinkState.FAILED, "Bluetooth helper stopped (exit code ${code ?: "?"})")
    }

    override fun send(toRadio: ByteArray): Boolean {
        val h = helper ?: return false
        if (!connected || closed || proto != MeshProtocol.MESHTASTIC) return false
        return h.send("tx " + toRadio.toHex())
    }

    override fun sendMeshCore(frame: ByteArray): Boolean {
        val h = helper ?: return false
        if (!connected || closed || proto != MeshProtocol.MESHCORE) return false
        return h.send("tx " + frame.toHex())
    }

    override fun close() {
        if (closed) return
        closed = true
        connected = false
        val h = helper
        helper = null
        if (h != null) thread(isDaemon = true, name = "ble-helper-close") { h.send("disconnect"); h.close() }
        listener?.onLinkState(LinkState.DISCONNECTED, "closed")
    }
}

/** Parses the helper's Windows location lines (`pos <lat> <lon> <acc> <alt> <epoch_ms> <source>`). */
object OsLocation {
    /** Windows PositionSource: 0 cellular, 1 satellite, 2 Wi-Fi, 3 IP address, 4 unknown, 5 default, 6 obfuscated. */
    fun sourceLabel(src: Int) = when (src) { 0 -> "cellular"; 1 -> "satellite"; 2 -> "Wi-Fi"; 3 -> "IP address"; 6 -> "obfuscated"; else -> "unknown" }

    sealed class Result {
        data class Ok(val fix: meshsurvey.core.model.Fix, val sourceLabel: String) : Result()
        data class Rejected(val reason: String) : Result()
    }

    /**
     * IP-address and deliberately obfuscated positions are rejected (kilometres off). The fix keeps Windows' own
     * timestamp; one older than [maxAgeMs] is rejected so a cached position is never logged as current.
     */
    fun parse(line: String, nowWallMs: Long, nowMonoMs: Long, maxAgeMs: Long = 10_000): Result? {
        val p = line.split(" ")
        if (p.size < 7 || p[0] != "pos") return null
        val lat = p[1].toDoubleOrNull() ?: return null
        val lon = p[2].toDoubleOrNull() ?: return null
        val acc = p[3].toDoubleOrNull()
        val alt = p[4].toDoubleOrNull()
        val ts = p[5].toLongOrNull() ?: return null
        val src = p[6].toIntOrNull() ?: 4
        if (src == 3 || src == 6) return Result.Rejected("Windows only has an ${sourceLabel(src)} position (too coarse); not used")
        val age = nowWallMs - ts
        if (age > maxAgeMs) return Result.Rejected("Windows position is ${age / 1000} s old; waiting for a fresh one")
        val ageClamped = age.coerceAtLeast(0)
        return Result.Ok(meshsurvey.core.model.Fix(lat, lon, acc, if (src == 1) alt else null,
            nowWallMs - ageClamped, nowMonoMs - ageClamped, meshsurvey.core.model.FixSource.OS_LOCATION), sourceLabel(src))
    }
}
