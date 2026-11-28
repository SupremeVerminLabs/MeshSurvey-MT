package meshsurvey.core

import meshsurvey.core.proto.BROADCAST
import meshsurvey.core.proto.Port
import meshsurvey.core.proto.Wire
import meshsurvey.core.proto.f
import meshsurvey.core.radio.RadioClient
import meshsurvey.core.radio.RadioListener
import meshsurvey.core.radio.RadioState
import meshsurvey.core.radio.ReceivedPacket
import meshsurvey.core.radio.StreamFramer
import meshsurvey.core.radio.TcpLink
import meshsurvey.core.sim.FromRadioEncoder
import meshsurvey.core.util.ManualClock
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End-to-end over a real socket: a fake node speaking the Meshtastic stream protocol. */
class TcpLinkTest {
    @Test fun handshakeAndPacketOverTcp() {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            server.accept().use { s ->
                val out = s.getOutputStream()
                out.write("boot log line\n".encodeToByteArray()) // debug text before frames
                val framer = StreamFramer({ frame ->
                    Wire.parse(frame).f(3)?.let { want ->
                        listOf(FromRadioEncoder.myInfo(0x1234), FromRadioEncoder.loraConfig(4, 3, 0, 3, true),
                            FromRadioEncoder.configComplete(want.u32),
                            FromRadioEncoder.packet(0x99, BROADCAST, 5, Port.TELEMETRY, null, snr = 2.5f, rssi = -104))
                            .forEach { out.write(StreamFramer.encode(it)) }
                        out.flush()
                    }
                })
                val buf = ByteArray(512)
                while (true) { val n = s.getInputStream().read(buf); if (n < 0) break; framer.feed(buf, n) }
            }
        }
        // "main thread" = a queue drained by the test thread
        val mainQ = LinkedBlockingQueue<Runnable>()
        val rc = RadioClient(ManualClock())
        val got = CountDownLatch(1)
        val debug = ArrayList<String>()
        var pkt: ReceivedPacket? = null
        rc.addListener(object : RadioListener {
            override fun onPacket(rx: ReceivedPacket) { pkt = rx; got.countDown() }
            override fun onDebugText(text: String) { debug += text }
        })
        rc.connect(TcpLink("127.0.0.1", server.localPort, meshsurvey.core.radio.MeshProtocol.MESHTASTIC) { mainQ.put(it) })
        val deadline = System.currentTimeMillis() + 5000
        while (got.count > 0 && System.currentTimeMillis() < deadline) mainQ.poll(100, TimeUnit.MILLISECONDS)?.run()
        assertEquals(RadioState.READY, rc.state)
        assertEquals("MEDIUM_FAST", rc.info.preset)
        assertEquals(-104, pkt!!.packet.rssi)
        assertTrue(debug.contains("boot log line"))
        rc.disconnect(); server.close()
    }
}
