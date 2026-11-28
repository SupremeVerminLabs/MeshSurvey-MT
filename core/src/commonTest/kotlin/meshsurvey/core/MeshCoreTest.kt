package meshsurvey.core

import meshsurvey.core.engine.SurveyEngine
import meshsurvey.core.map.Heard
import meshsurvey.core.model.Fix
import meshsurvey.core.model.FixSource
import meshsurvey.core.model.KnownNode
import meshsurvey.core.proto.MeshEnums
import meshsurvey.core.radio.LinkKind
import meshsurvey.core.radio.LinkState
import meshsurvey.core.radio.MeshCore
import meshsurvey.core.radio.MeshCoreFramer
import meshsurvey.core.radio.MeshCoreIds
import meshsurvey.core.radio.MeshProtocol
import meshsurvey.core.radio.RadioClient
import meshsurvey.core.radio.RadioLink
import meshsurvey.core.radio.RadioState
import meshsurvey.core.store.ObsFilter
import meshsurvey.core.store.SqlStore
import meshsurvey.core.testsupport.testDriver
import meshsurvey.core.util.ManualClock
import meshsurvey.core.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Frames built here follow the MeshCore companion protocol layout; they are not captured from a real radio. */
class MeshCoreTest {
    companion object {
        fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun key(first: Int) = ByteArray(32) { i -> if (i == 0) first.toByte() else (i * 7).toByte() }
        fun fixed(s: String, n: Int) = s.encodeToByteArray().copyOf(n)

        fun selfInfo(k: ByteArray, name: String = "Base MC") = byteArrayOf(5, 1, 22, 22) + k + le32(0) + le32(0) +
            byteArrayOf(0, 0, 0, 0) + le32(910_525) + le32(62_500) + byteArrayOf(7, 5) + name.encodeToByteArray()
        fun deviceInfo() = byteArrayOf(13, 8, 50, 8) + le32(123456) + fixed("19 Oct 2026", 12) + fixed("Heltec V3", 40) + fixed("v1.9.0", 20)
        fun contact(k: ByteArray, type: Int, name: String, lat: Double?, lon: Double?) =
            byteArrayOf(3) + k + byteArrayOf(type.toByte(), 0, 0xff.toByte()) + ByteArray(64) + fixed(name, 32) + le32(1_764_000_000) +
                le32(((lat ?: 0.0) * 1e6).toLong()) + le32(((lon ?: 0.0) * 1e6).toLong()) + le32(0)
        fun advertPayload(k: ByteArray, type: Int, name: String, lat: Double, lon: Double) =
            k + le32(1_764_000_000) + ByteArray(64) + byteArrayOf((type or 0x10 or 0x80).toByte()) +
                le32((lat * 1e6).toLong()) + le32((lon * 1e6).toLong()) + name.encodeToByteArray()
        /** PUSH_LOG_RX_DATA with a flood packet through [path] (1-byte hashes). */
        fun rx(payloadType: Int, path: List<Int>, payload: ByteArray, snr: Double = -4.5, rssi: Int = -101, route: Int = 1) =
            byteArrayOf(0x88.toByte(), (snr * 4).toInt().toByte(), rssi.toByte(), ((payloadType shl 2) or route).toByte(), path.size.toByte()) +
                ByteArray(path.size) { path[it].toByte() } + payload
    }

    @Test fun decodesSelfInfoDeviceInfoAndContacts() {
        val si = MeshCore.decodeSelfInfo(selfInfo(key(0xa3)))!!
        assertEquals("Base MC", si.name); assertEquals(910.525, si.frequencyMhz); assertEquals(62.5, si.bandwidthKhz)
        assertEquals(7, si.spreadingFactor); assertEquals(22, si.txPowerDbm)
        val d = MeshCore.decodeDeviceInfo(deviceInfo())!!
        assertEquals("v1.9.0", d.version); assertEquals("Heltec V3", d.model)
        val c = MeshCore.decodeContact(contact(key(0x5c), 2, "Hilltop RPT", 45.4215, -75.6972))!!
        assertEquals(2, c.type); assertEquals("Hilltop RPT", c.name); assertEquals(45.4215, c.lat!!, 1e-6)
        assertNull(MeshCore.decodeContact(contact(key(0x5d), 1, "No GPS", null, null))!!.lat)
    }

    @Test fun floodPacketIsHeardFromTheLastRepeaterAndZeroHopFromTheSender() {
        val adv = advertPayload(key(0x77), 2, "Ridge RPT", 45.5, -75.5)
        val relayed = MeshCore.decodeRx(rx(4, listOf(0x12, 0xa3), adv))!!
        assertEquals("a3", relayed.heardPrefix); assertEquals(2, relayed.hops); assertEquals(-4.5, relayed.snr); assertEquals(-101, relayed.rssi)
        assertEquals("Ridge RPT", relayed.advert!!.name); assertEquals(45.5, relayed.advert!!.lat!!, 1e-6)
        val direct = MeshCore.decodeRx(rx(4, emptyList(), adv))!!
        assertEquals(key(0x77).toHex(), direct.heardPrefix)                 // zero hops: heard the repeater itself, full key known
        assertEquals(relayed.fingerprint, direct.fingerprint)               // same packet whichever path it took
        val routed = MeshCore.decodeRx(rx(2, listOf(0x44), byteArrayOf(0x10, 0x20, 1, 2, 3), route = 2))!!
        assertNull(routed.heardPrefix)                                      // direct routing: previous hop unknown
        val txt = MeshCore.decodeRx(rx(2, emptyList(), byteArrayOf(0x10, 0x20, 1, 2, 3)))!!
        assertEquals("20", txt.heardPrefix)                                 // zero-hop text: sender's hash
        assertNull(MeshCore.decodeRx(byteArrayOf(0x88.toByte(), 0, 0, 0x05, 9, 1)))  // path longer than the packet
    }

    @Test fun twoByteHashPathsFromNewerFirmware() {
        val p = MeshCore.decodeRx(byteArrayOf(0x88.toByte(), 8, -90, (5 shl 2 or 1).toByte(), (0x40 or 2).toByte(), 1, 2, 0xa3.toByte(), 0x0f, 9, 9))!!
        assertEquals("a30f", p.heardPrefix); assertEquals("mc:a3", Heard.group("mc:a30f"))
    }

    @Test fun framerFindsFramesAndIgnoresConsoleText() {
        val got = ArrayList<ByteArray>()
        val f = MeshCoreFramer({ got += it })
        val frame = byteArrayOf(0x3E, 3, 0, 0x0c, 1, 2)
        f.feed("boot> ok\r\n".encodeToByteArray() + frame.copyOfRange(0, 2))
        f.feed(frame.copyOfRange(2, frame.size))
        assertEquals(1, got.size); assertEquals(listOf<Byte>(0x0c, 1, 2), got[0].toList())
        assertEquals(listOf<Byte>(0x3C, 2, 0, 22, 3), MeshCore.frameToRadio(MeshCore.deviceQuery()).toList())
    }

    @Test fun idsNeverCollideWithMeshtasticNodeNumbers() {
        val id = MeshCoreIds.fromHash(0xa3)
        assertTrue(MeshCoreIds.isMeshCore(id)); assertTrue(id > 0xffffffffL)
        assertEquals("mc:a3", MeshEnums.nodeId(id)); assertEquals("!0000a3b4", MeshEnums.nodeId(0xa3b4))
        assertEquals("mc:a3070e15", MeshCoreIds.label(MeshCoreIds.fromPrefix(key(0xa3).copyOfRange(0, 4))))
    }

    /** Stream-like link whose protocol is unknown until probed. */
    class ProbeLink(private val known: MeshProtocol? = null) : RadioLink {
        override val kind = LinkKind.SERIAL
        override val description = "probe"
        var l: RadioLink.Listener? = null
        val sent = ArrayList<ByteArray>(); val mc = ArrayList<ByteArray>()
        var set: MeshProtocol? = null
        override val detectedProtocol: MeshProtocol? get() = known
        override fun setProtocol(p: MeshProtocol) { set = p }
        override fun start(listener: RadioLink.Listener) { l = listener; listener.onLinkState(LinkState.CONNECTED) }
        override fun send(toRadio: ByteArray): Boolean { sent += toRadio; return true }
        override fun sendMeshCore(frame: ByteArray): Boolean { mc += frame; return true }
        override fun close() {}
    }

    @Test fun meshtasticRadioIsFoundAfterTheProbeGoesUnanswered() {
        val clock = ManualClock(); val rc = RadioClient(clock); val link = ProbeLink()
        rc.connect(link)
        assertEquals(1, link.mc.size); assertEquals(0, link.sent.size)       // only the harmless MeshCore query first
        clock.advance(2_600); rc.tick()
        assertEquals(MeshProtocol.MESHTASTIC, link.set); assertEquals(1, link.sent.size)
        assertEquals(MeshProtocol.MESHTASTIC, rc.info.protocol)
    }

    @Test fun meshCoreRadioIsListenedToAndEveryReceptionTaggedAndAttributed() {
        val clock = ManualClock()
        val store = SqlStore(testDriver())
        val eng = SurveyEngine(store, clock)
        val link = ProbeLink()
        eng.connect(link)
        val l = link.l!!
        l.onMeshCoreFrame(deviceInfo())                                       // probe answered: it is MeshCore
        assertEquals(MeshProtocol.MESHCORE, link.set)
        assertEquals(MeshCore.CMD_APP_START, link.mc.last()[0].toInt())
        l.onMeshCoreFrame(selfInfo(key(0xa1)))
        assertEquals(MeshCore.CMD_GET_CONTACTS, link.mc.last()[0].toInt())
        l.onMeshCoreFrame(byteArrayOf(2, 2, 0, 0, 0))
        l.onMeshCoreFrame(contact(key(0xa3), 2, "Hilltop RPT", 45.42, -75.69))
        l.onMeshCoreFrame(contact(key(0x5c), 1, "Friend phone", null, null))
        l.onMeshCoreFrame(byteArrayOf(4, 0, 0, 0, 0))
        assertEquals(RadioState.READY, eng.radio.state)
        assertTrue(eng.radio.info.preset!!.startsWith("MC 910.525/BW62.5/SF7"))
        assertTrue(eng.radio.info.firmware!!.contains("v1.9.0"))
        assertNull(eng.radio.sendData(5, 256, byteArrayOf(1), true))         // Meshtastic sending refused on a MeshCore radio
        assertTrue(link.sent.isEmpty())                                      // no Meshtastic bytes ever went to it
        assertTrue(link.mc.all { (it[0].toInt() and 0xff) in MeshCore.ALLOWED_COMMANDS })

        eng.onFix(Fix(45.40, -75.70, 5.0, null, clock.wallMs(), clock.monotonicMs(), FixSource.GPS))
        l.onMeshCoreFrame(rx(5, listOf(0x31, 0xa3), byteArrayOf(0x11, 1, 2, 3, 4)))          // group text relayed by Hilltop
        l.onMeshCoreFrame(rx(4, emptyList(), advertPayload(key(0x77), 2, "Ridge RPT", 45.5, -75.5), snr = 6.0, rssi = -88))
        l.onMeshCoreFrame(rx(4, emptyList(), advertPayload(key(0xa1), 1, "Base MC", 45.4, -75.7)))  // our own advert: skipped
        val obs = store.observations(ObsFilter())
        assertEquals(2, obs.size)
        assertTrue(obs.all { it.protocol == "meshcore" && it.lat == 45.40 })
        assertEquals("mc:a3", obs[0].heardId); assertEquals(2, obs[0].hopsAway); assertEquals(0xa3, obs[0].relayByte)
        assertEquals(6.0, obs[1].snr); assertEquals(0, obs[1].hopsAway)
        assertEquals("meshcore", store.sessions().single().protocol)

        // known positions: the contact with a position and the advert just heard
        val known = store.knownNodes("meshcore")
        assertEquals(setOf("Hilltop RPT", "Friend phone", "Ridge RPT", "Base MC"), known.mapNotNull { it.name }.toSet())
        assertEquals(45.5, known.first { it.name == "Ridge RPT" }.lat)
        assertEquals("Hilltop RPT (mc:a3)", Heard.label("mc:a3", known))
        // per-node filter
        assertEquals(1, store.observations(ObsFilter(heardGroups = setOf("mc:a3"))).size)
        assertEquals(1, store.observations(ObsFilter(heardGroups = setOf("mc:77"))).size)
        assertEquals(0, store.observations(ObsFilter(protocol = "meshtastic")).size)
        assertEquals(mapOf("mc:a3" to 1, "mc:77" to 1), store.heardGroupCounts("meshcore"))

        // battery
        l.onMeshCoreFrame(byteArrayOf(12, 0x68, 0x10, 0, 0, 0, 0, 0, 0, 0, 0))
        assertEquals(4.2, eng.radioMetrics.value!!.voltage!!, 1e-9)
    }

    @Test fun bluetoothMeshCoreRadioSkipsTheProbe() {
        val clock = ManualClock(); val rc = RadioClient(clock); val link = ProbeLink(MeshProtocol.MESHCORE)
        rc.connect(link)
        assertEquals(MeshCore.CMD_APP_START, link.mc.first()[0].toInt())
        clock.advance(8_100); rc.tick(); clock.advance(8_100); rc.tick(); clock.advance(8_100); rc.tick()
        assertEquals(RadioState.FAILED, rc.state)                            // gives up after three unanswered tries
    }

    @Test fun ambiguousPrefixesAreShownAsPossible() {
        val k = listOf(KnownNode("meshcore", "a3" + "0".repeat(62), "Hill", "repeater", 1.0, 1.0, 0, "contacts"),
            KnownNode("meshcore", "a3" + "1".repeat(62), "Barn", "repeater", 1.0, 1.0, 0, "advert"),
            KnownNode("meshtastic", "!1234abcd", "Router", "ROUTER", 1.0, 1.0, 0, "nodedb"))
        assertEquals("possibly Hill or Barn (mc:a3)", Heard.label("mc:a3", k))
        assertEquals("Router (mt:~cd)", Heard.label(Heard.group("mt:!1234abcd"), k))
        assertEquals("mc:ff", Heard.label("mc:ff", k))
    }
}
