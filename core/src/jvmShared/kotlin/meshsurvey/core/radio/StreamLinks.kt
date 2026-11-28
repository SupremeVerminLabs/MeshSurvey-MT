package meshsurvey.core.radio

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Base for byte-stream links (USB serial, TCP). Reads on a background thread, writes on a
 * single writer thread, and delivers every callback through [post] (the UI/main thread).
 */
abstract class StreamLinkBase(private val post: (Runnable) -> Unit) : RadioLink {
    @Volatile private var closed = false
    private var listener: RadioLink.Listener? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "radio-writer").apply { isDaemon = true } }
    private var out: OutputStream? = null
    /** null while the protocol is being probed: both framings are watched. */
    @Volatile private var framing: MeshProtocol? = null
    /** Protocol known in advance (e.g. Meshtastic's TCP port 4403); null = probe on connect. */
    protected open val initialProtocol: MeshProtocol? = null
    override val detectedProtocol: MeshProtocol? get() = initialProtocol
    override fun setProtocol(p: MeshProtocol) { framing = p }

    /** Open the transport (called on a background thread). */
    protected abstract fun openStreams(): Pair<InputStream, OutputStream>
    protected abstract fun closeTransport()
    /** Called once after opening, before the handshake (e.g. wake a sleeping serial device). */
    protected open fun afterOpen(out: OutputStream) {}

    override fun start(listener: RadioLink.Listener) {
        this.listener = listener
        framing = initialProtocol
        post.invoke(Runnable { listener.onLinkState(LinkState.CONNECTING, description) })
        Thread({
            try {
                val (input, output) = openStreams()
                out = output
                afterOpen(output)
                post.invoke(Runnable { if (!closed) listener.onLinkState(LinkState.CONNECTED, description) })
                val framer = StreamFramer(
                    onFrame = { frame -> post.invoke(Runnable { if (!closed) listener.onFromRadio(frame) }) },
                    onText = { t -> post.invoke(Runnable { if (!closed) listener.onDebugText(t) }) },
                )
                val mc = MeshCoreFramer({ frame -> post.invoke(Runnable { if (!closed) listener.onMeshCoreFrame(frame) }) })
                // While probing, only a well-formed DEVICE_INFO answer counts as MeshCore (a Meshtastic console may print '>').
                mc.accept = { f -> framing == MeshProtocol.MESHCORE || (f.size >= 4 && (f[0].toInt() and 0xff) == MeshCore.RESP_DEVICE_INFO) }
                val buf = ByteArray(1024)
                while (!closed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    when (framing) {
                        MeshProtocol.MESHTASTIC -> framer.feed(buf, n)
                        MeshProtocol.MESHCORE -> mc.feed(buf, n)
                        null -> {
                            var found = false
                            val prev = mc.accept
                            mc.accept = { f -> prev(f).also { if (it) found = true } }
                            mc.feed(buf, n)
                            mc.accept = prev
                            if (found) framing = MeshProtocol.MESHCORE else framer.feed(buf, n)
                        }
                    }
                }
                if (!closed) post.invoke(Runnable { listener.onLinkState(LinkState.DISCONNECTED, "connection closed by device") })
            } catch (e: Exception) {
                if (!closed) post.invoke(Runnable { listener.onLinkState(LinkState.FAILED, e.message ?: e.toString()) })
            } finally {
                try { closeTransport() } catch (_: Exception) {}
            }
        }, "radio-reader").apply { isDaemon = true }.start()
    }

    override fun send(toRadio: ByteArray): Boolean {
        val o = out ?: return false
        if (closed || framing == MeshProtocol.MESHCORE) return false
        val frame = StreamFramer.encode(toRadio)
        writer.execute { try { o.write(frame); o.flush() } catch (_: Exception) {} }
        return true
    }

    override fun sendMeshCore(frame: ByteArray): Boolean {
        val o = out ?: return false
        if (closed || framing == MeshProtocol.MESHTASTIC) return false
        val bytes = MeshCore.frameToRadio(frame)
        writer.execute { try { o.write(bytes); o.flush() } catch (_: Exception) {} }
        return true
    }

    override fun close() {
        closed = true
        writer.execute { try { closeTransport() } catch (_: Exception) {} }
        writer.shutdown()
    }
}

/** Meshtastic TCP API (Wi-Fi/Ethernet nodes, default port 4403). */
class TcpLink(
    private val host: String,
    private val port: Int = 4403,
    /** null = probe on connect. */
    private val protocol: MeshProtocol? = if (port == 4403) MeshProtocol.MESHTASTIC else null,
    post: (Runnable) -> Unit,
) : StreamLinkBase(post) {
    override val kind = LinkKind.TCP
    override val description = "TCP $host:$port"
    /** 4403 is Meshtastic's API port; any other port is probed (MeshCore companion radios over Wi-Fi use their own port). */
    override val initialProtocol: MeshProtocol? get() = protocol
    private var socket: Socket? = null

    override fun openStreams(): Pair<InputStream, OutputStream> {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 8_000)
        s.tcpNoDelay = true
        socket = s
        return s.getInputStream() to s.getOutputStream()
    }

    override fun closeTransport() { socket?.close() }
}
