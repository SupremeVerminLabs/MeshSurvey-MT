package meshsurvey.core

import meshsurvey.core.proto.BROADCAST
import meshsurvey.core.proto.FromRadioMsg
import meshsurvey.core.proto.MeshEnums
import meshsurvey.core.proto.MeshProto
import meshsurvey.core.proto.Port
import meshsurvey.core.proto.ProtoWriter
import meshsurvey.core.proto.Wire
import meshsurvey.core.proto.f
import meshsurvey.core.radio.LinkKind
import meshsurvey.core.radio.LinkState
import meshsurvey.core.radio.RadioClient
import meshsurvey.core.radio.RadioLink
import meshsurvey.core.radio.RadioListener
import meshsurvey.core.radio.RadioState
import meshsurvey.core.radio.ReceivedPacket
import meshsurvey.core.radio.StreamFramer
import meshsurvey.core.sim.FromRadioEncoder
import meshsurvey.core.util.ManualClock
import meshsurvey.core.util.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {

    @Test fun varintAndNegativeInt32() {
        val b = ProtoWriter().uint32(1, 300).int32(2, -92).fixed32(3, 0xdeadbeefL).float(4, 6.25f).toByteArray()
        val f = Wire.parse(b)
        assertEquals(300L, f.f(1)!!.u32)
        assertEquals(-92, f.f(2)!!.int)          // int32 negative is a 10-byte varint
        assertEquals(0xdeadbeefL, f.f(3)!!.u32)
        assertEquals(6.25f, f.f(4)!!.float)
    }

    @Test fun knownBytesWantConfig() {
        // ToRadio{want_config_id=69420}: tag 0x18, varint 69420 = ac 9e 04
        val b = MeshProto.wantConfig(69420)
        assertEquals(listOf(0x18, 0xac, 0x9e, 0x04), b.map { it.toInt() and 0xff })
        assertEquals(listOf(0x3a, 0x00), MeshProto.heartbeat().map { it.toInt() and 0xff })
    }

    @Test fun truncatedInputThrows() {
        assertFailsWith<Exception> { Wire.parse(byteArrayOf(0x0a, 0x05, 0x01)) }
    }

    @Test fun decodesPacketMetadata() {
        val bytes = FromRadioEncoder.packet(from = 0x11223344, to = BROADCAST, id = 77, port = Port.TELEMETRY, payload = byteArrayOf(1, 2),
            snr = -7.5f, rssi = -118, rxTime = 1_764_000_000, hopLimit = 1, hopStart = 3, relay = 0x99, channel = 0)
        val m = MeshProto.decodeFromRadio(bytes) as FromRadioMsg.Packet
        val p = m.packet
        assertEquals(0x11223344L, p.from); assertEquals(BROADCAST, p.to); assertEquals(77L, p.id)
        assertEquals(-118, p.rssi); assertEquals(-7.5f, p.snr)
        assertEquals(2, p.hopsAway); assertEquals(0x99, p.relayByte)
        assertEquals(Port.TELEMETRY, p.portnum); assertTrue(p.decoded)
    }

    @Test fun snrZeroVsUnknown() {
        // proto3 drops 0.0: with RSSI present, absent SNR means 0 dB; with neither, unknown.
        val withRssi = (MeshProto.decodeFromRadio(FromRadioEncoder.packet(1, 2, 3, Port.TEXT, null, snr = 0f, rssi = -100)) as FromRadioMsg.Packet).packet
        assertEquals(0f, withRssi.snr)
        val none = (MeshProto.decodeFromRadio(FromRadioEncoder.packet(1, 2, 3, Port.TEXT, null)) as FromRadioMsg.Packet).packet
        assertNull(none.snr); assertNull(none.rssi); assertNull(none.hopsAway)
    }

    @Test fun encryptedPacketHasNoPort() {
        val p = (MeshProto.decodeFromRadio(FromRadioEncoder.packet(5, BROADCAST, 9, null, null, snr = 3f, rssi = -90,
            encrypted = ByteArray(30) { 1 })) as FromRadioMsg.Packet).packet
        assertNull(p.portnum); assertEquals(30, p.encryptedLen)
    }

    @Test fun decodesLoraConfig() {
        val m = MeshProto.decodeFromRadio(FromRadioEncoder.loraConfig(preset = 4, region = 3, channelNum = 0, hopLimit = 3, txEnabled = true)) as FromRadioMsg.LoraConfig
        assertEquals("MEDIUM_FAST", m.cfg.presetName); assertEquals("EU_868", m.cfg.regionName); assertNull(m.cfg.overrideFrequencyMhz)
    }

    @Test fun sendPacketRoundTrip() {
        val b = MeshProto.sendPacket(to = 0x51a000b2, port = Port.PRIVATE, payload = byteArrayOf(9, 8, 7), id = 1234, wantAck = true)
        val pkt = Wire.parse(Wire.parse(b).f(1)!!.bytes!!)
        assertEquals(0x51a000b2L, pkt.f(2)!!.u32)
        assertEquals(1234L, pkt.f(6)!!.u32)
        assertTrue(pkt.f(10)!!.bool)
        val data = Wire.parse(pkt.f(4)!!.bytes!!)
        assertEquals(256, data.f(1)!!.int)
    }

    @Test fun nodeIdParsing() {
        assertEquals(0x51a000b2L, MeshEnums.parseNodeId("!51a000b2"))
        assertNull(MeshEnums.parseNodeId("!ffffffff"))
        assertEquals("!000000ff", MeshEnums.nodeId(255))
    }

    @Test fun streamFramerHandlesGarbageAndSplits() {
        val frames = ArrayList<ByteArray>(); val text = ArrayList<String>()
        val fr = StreamFramer({ frames += it }, { text += it })
        val a = StreamFramer.encode(byteArrayOf(1, 2, 3))
        val b = StreamFramer.encode(ByteArray(300) { 7 })
        val stream = "INFO boot\n".encodeToByteArray() + a + "dbg\n".encodeToByteArray() + b
        // feed one byte at a time to exercise partial headers
        stream.forEach { fr.feed(byteArrayOf(it)) }
        assertEquals(2, frames.size)
        assertEquals(listOf<Byte>(1, 2, 3), frames[0].toList())
        assertEquals(300, frames[1].size)
        assertEquals(listOf("INFO boot", "dbg"), text)
    }

    @Test fun streamFramerResyncsOnBadLength() {
        val frames = ArrayList<ByteArray>()
        val fr = StreamFramer({ frames += it })
        fr.feed(byteArrayOf(0x94.toByte(), 0xc3.toByte(), 0x7f, 0x7f) + StreamFramer.encode(byteArrayOf(5)))
        assertEquals(1, frames.size)
    }

    @Test fun sha256Vectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".encodeToByteArray()))
        assertEquals("248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()))
    }

    // ---------------- RadioClient handshake ----------------

    class FakeLink : RadioLink {
        override val kind = LinkKind.SERIAL
        override val description = "fake"
        var listener: RadioLink.Listener? = null
        val sent = ArrayList<ByteArray>()
        override fun start(listener: RadioLink.Listener) { this.listener = listener; listener.onLinkState(LinkState.CONNECTED) }
        override fun send(toRadio: ByteArray): Boolean { sent += toRadio; return true }
        override fun close() {}
    }

    @Test fun radioThatRebootsOnPortOpenIsAskedAgainAfterBootOutput() {
        val clock = ManualClock()
        val rc = RadioClient(clock)
        val link = FakeLink()
        rc.connect(link)
        assertEquals(1, link.sent.size)
        val l = link.listener!!
        clock.advance(500); l.onDebugText("ESP-ROM:esp32s3-20210327")       // board resets as the port opens
        clock.advance(1500); l.onDebugText("INFO | ?? Booted, wake cause 0")
        clock.advance(2000); rc.tick()
        assertEquals(1, link.sent.size)                                     // still printing within 3 s: wait
        clock.advance(1500); rc.tick()
        assertEquals(2, link.sent.size)                                     // quiet for 3 s: second want_config
        val n2 = Wire.parse(link.sent.last()).f(3)!!.u32
        l.onFromRadio(FromRadioEncoder.myInfo(5)); l.onFromRadio(FromRadioEncoder.configComplete(n2))
        assertEquals(RadioState.READY, rc.state)
    }

    @Test fun stalledHandshakeIsRetriedThenFails() {
        val clock = ManualClock()
        val rc = RadioClient(clock)
        val link = FakeLink()
        rc.connect(link)
        assertEquals(1, link.sent.size)
        clock.advance(21_000); rc.tick()
        assertEquals(2, link.sent.size)               // second want_config
        assertEquals(RadioState.DOWNLOADING_CONFIG, rc.state)
        clock.advance(21_000); rc.tick()
        assertEquals(3, link.sent.size)
        clock.advance(21_000); rc.tick()
        assertEquals(RadioState.FAILED, rc.state)      // gave up after 3 requests
        // progress resets and a late, matching completion is still accepted on a fresh request
        val rc2 = RadioClient(clock); val l2 = FakeLink(); rc2.connect(l2)
        val n2 = Wire.parse(l2.sent.last()).f(3)!!.u32
        l2.listener!!.onFromRadio(FromRadioEncoder.myInfo(1))
        l2.listener!!.onFromRadio(FromRadioEncoder.configComplete(n2))
        assertEquals(RadioState.READY, rc2.state)
    }

    @Test fun handshakeReachesReadyOnlyWithMatchingNonce() {
        val clock = ManualClock()
        val rc = RadioClient(clock)
        val states = ArrayList<RadioState>()
        val packets = ArrayList<ReceivedPacket>()
        rc.addListener(object : RadioListener {
            override fun onRadioState(state: RadioState, detail: String) { states += state }
            override fun onPacket(rx: ReceivedPacket) { packets += rx }
        })
        val link = FakeLink()
        rc.connect(link)
        val nonce = Wire.parse(link.sent.first()).f(3)!!.u32
        val l = link.listener!!
        l.onFromRadio(FromRadioEncoder.myInfo(0xabcdef01))
        l.onFromRadio(FromRadioEncoder.loraConfig(0, 1, 20, 3, true))
        l.onFromRadio(FromRadioEncoder.nodeInfo(42, "Node", "N", 2, 45.0, -75.0))
        l.onFromRadio(FromRadioEncoder.packet(42, BROADCAST, 1, Port.TEXT, null, snr = 1f, rssi = -99))
        assertTrue(packets.single().duringConfigDownload)
        l.onFromRadio(FromRadioEncoder.configComplete(nonce + 1))
        assertEquals(RadioState.DOWNLOADING_CONFIG, rc.state)
        l.onFromRadio(FromRadioEncoder.configComplete(nonce))
        assertEquals(RadioState.READY, rc.state)
        assertEquals(0xabcdef01L, rc.info.myNodeNum)
        assertEquals("LONG_FAST", rc.info.preset)
        assertNotNull(rc.nodes[42L])
        // heartbeat after 5 minutes on stream links
        clock.advance(301_000); rc.tick()
        assertTrue(link.sent.any { Wire.parse(it).f(7) != null })
    }

    @Test fun sendDataRefusesBroadcastAndNonTestPorts() {
        val rc = RadioClient(ManualClock())
        val link = FakeLink(); rc.connect(link)
        val nonce = Wire.parse(link.sent.first()).f(3)!!.u32
        link.listener!!.onFromRadio(FromRadioEncoder.configComplete(nonce))
        assertFailsWith<IllegalArgumentException> { rc.sendData(BROADCAST, Port.PRIVATE, byteArrayOf(1), true) }
        assertFailsWith<IllegalArgumentException> { rc.sendData(5, Port.TEXT, byteArrayOf(1), true) }
        assertNotNull(rc.sendData(5, Port.PRIVATE, byteArrayOf(1), true))
    }
}
