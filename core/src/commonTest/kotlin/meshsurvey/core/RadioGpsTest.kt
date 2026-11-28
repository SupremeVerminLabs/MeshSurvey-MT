package meshsurvey.core

import meshsurvey.core.engine.RadioGpsSource
import meshsurvey.core.model.Fix
import meshsurvey.core.model.FixSource
import meshsurvey.core.proto.PositionInfo
import meshsurvey.core.radio.RadioClient
import meshsurvey.core.radio.RadioListener
import meshsurvey.core.sim.FromRadioEncoder
import meshsurvey.core.util.ManualClock
import meshsurvey.core.proto.f
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioGpsTest {
    private fun pos(lat: Double, lon: Double, bits: Int? = 32, hdop: Int? = 120, src: Int = 2, timeS: Long? = null, speed: Int? = 0) =
        PositionInfo(lat, lon, 50, timeS, src, hdop, speed, 9, bits)

    private class Rec(val clock: ManualClock) { val fixes = ArrayList<Fix>(); var status = ""; val src = RadioGpsSource(clock)
        init { src.start({ fixes += it }, { status = it }) } }

    @Test fun stationaryRadioPositionIsHeldEverySecond() {
        val r = Rec(ManualClock())
        r.src.onReport(pos(45.0, -75.0))
        repeat(3) { r.clock.advance(1000); r.src.tick() }
        assertEquals(3, r.fixes.size)
        assertTrue(r.fixes.all { it.source == FixSource.RADIO && it.lat == 45.0 })
        assertEquals(6.0, r.fixes.last().accuracyM)         // HDOP 1.2 x 5 m
        assertTrue(r.status.contains("stationary"))
    }

    @Test fun blurredBroadcastIsNeverUsedAndNeverReplacesAGoodPosition() {
        val r = Rec(ManualClock())
        r.src.onReport(pos(45.0, -75.0, bits = 13))         // ~2.9 km: the radio's own broadcast on a default channel
        r.clock.advance(1000); r.src.tick()
        assertTrue(r.fixes.isEmpty())
        assertTrue(r.status.contains("blurred"))
        r.src.onReport(pos(45.001, -75.0, bits = null, hdop = null, timeS = r.clock.wallMs() / 1000 - 30), fromConfig = true)   // full-precision NodeInfo
        r.src.tick(); assertEquals(1, r.fixes.size)
        r.src.onReport(pos(45.02, -75.0, bits = 13))        // later blurred broadcast, far off due to truncation
        r.clock.advance(1000); r.src.tick()
        assertEquals(2, r.fixes.size); assertEquals(45.001, r.fixes.last().lat)
        assertTrue(r.status.contains("assumed stationary"))
    }

    @Test fun accuracyIsEstimatedFromTheBroadcastDopWhileThePositionComesFromNodeInfo() {
        val c = ManualClock(); val r = Rec(c)
        r.src.onReport(PositionInfo(45.0, -75.0, 50, c.wallMs() / 1000 - 10, 2, null, 0, null, null), fromConfig = true)   // NodeInfo: no quality fields
        r.src.tick(); assertNull(r.fixes.last().accuracyM); assertTrue(r.status.contains("not known yet"))
        r.src.onReport(PositionInfo(45.02, -75.0, 50, null, 2, null, 0, 6, 13, pdop = 240))                              // blurred broadcast, PDOP 2.4, 6 sats
        r.src.tick()
        assertEquals(45.0, r.fixes.last().lat)                      // position still the precise one
        assertEquals(12.0, r.fixes.last().accuracyM)                // 2.4 x 5 m
        r.src.onReport(PositionInfo(45.0, -75.0, 50, c.wallMs() / 1000, 2, null, 0, null, null), fromConfig = true)
        assertTrue(r.status.contains("6 satellites"))
        c.advance(3_700_000); r.src.tick()
        assertEquals(12.0, r.fixes.last().accuracyM)                // a stationary radio's estimate is kept for a day…
        c.advance(23 * 3_600_000L); r.src.tick()
        assertNull(r.fixes.last().accuracyM)                        // …and dropped after that
    }

    @Test fun heldFixesCarryTheRealPositionTime() {
        val c = ManualClock()
        val r = Rec(c)
        val tS = c.wallMs() / 1000 - 300
        r.src.onReport(pos(45.0, -75.0, timeS = tS), fromConfig = true)
        c.advance(1000); r.src.tick()
        assertEquals(tS * 1000, r.fixes.last().positionTimeMs)
        assertTrue(r.status.contains("5 min ago"))
    }

    @Test fun quietOwnInfoRequestUsesConfigOnlyNonceAndKeepsState() {
        val rc = RadioClient(ManualClock())
        val link = ProtocolTest.FakeLink(); rc.connect(link)
        val n = meshsurvey.core.proto.Wire.parse(link.sent.last()).f(3)!!.u32
        val l = link.listener!!
        l.onFromRadio(FromRadioEncoder.myInfo(42)); l.onFromRadio(FromRadioEncoder.configComplete(n))
        assertEquals(meshsurvey.core.radio.RadioState.READY, rc.state)
        assertTrue(rc.requestOwnInfo())
        assertEquals(69420L, meshsurvey.core.proto.Wire.parse(link.sent.last()).f(3)!!.u32)
        l.onFromRadio(FromRadioEncoder.nodeInfo(42, "Me", "M", 0, 45.0, -75.0))
        l.onFromRadio(FromRadioEncoder.configComplete(69420))
        assertEquals(meshsurvey.core.radio.RadioState.READY, rc.state)   // never shown as "Syncing"
    }

    @Test fun movingRadioStopsAssertingUntilStationaryAgain() {
        val r = Rec(ManualClock())
        r.src.onReport(pos(45.0, -75.0)); r.src.tick(); assertEquals(1, r.fixes.size)
        r.src.onReport(pos(45.01, -75.0))                  // ~1.1 km away: moved
        repeat(5) { r.clock.advance(1000); r.src.tick() }
        assertEquals(1, r.fixes.size)                       // nothing asserted while moving
        assertTrue(r.status.contains("moving"))
        r.src.onReport(pos(45.01, -75.0001))               // agrees with previous report (~8 m)
        r.clock.advance(1000); r.src.tick()
        assertEquals(2, r.fixes.size); assertEquals(45.01, r.fixes.last().lat)
    }

    @Test fun groundSpeedMeansMoving() {
        val r = Rec(ManualClock())
        r.src.onReport(pos(45.0, -75.0, speed = 15))
        r.src.tick()
        assertTrue(r.fixes.isEmpty())
    }

    @Test fun staleStoredPositionAtConnectIsNotUsedButFixedPositionIs() {
        val c = ManualClock()
        val r = Rec(c)
        val nowS = c.wallMs() / 1000
        r.src.onReport(pos(45.0, -75.0, timeS = nowS - 3 * 24 * 3600), fromConfig = true)
        r.src.tick(); assertTrue(r.fixes.isEmpty()); assertTrue(r.status.contains("stored position"))
        r.src.onReport(pos(45.0, -75.0, timeS = nowS - 60), fromConfig = true)
        r.src.tick(); assertEquals(1, r.fixes.size)
        val r2 = Rec(c)
        r2.src.onReport(pos(44.0, -76.0, src = 1, timeS = null, hdop = null), fromConfig = true)   // configured fixed position
        r2.src.tick(); assertEquals(1, r2.fixes.size); assertNull(r2.fixes[0].accuracyM)
        assertTrue(r2.status.contains("fixed position"))
    }

    @Test fun precisionMath() {
        assertEquals(2917, RadioGpsSource.precisionErrorM(13)!!.toInt())
        assertNull(RadioGpsSource.precisionErrorM(32))
        assertNull(RadioGpsSource.precisionErrorM(null))
    }

    @Test fun radioClientReportsOwnPositionFromConfigOnly() {
        val rc = RadioClient(ManualClock())
        val got = ArrayList<Pair<PositionInfo, Boolean>>()
        rc.addListener(object : RadioListener { override fun onOwnPosition(pos: PositionInfo, fromConfig: Boolean) { got += pos to fromConfig } })
        val link = ProtocolTest.FakeLink(); rc.connect(link)
        val l = link.listener!!
        l.onFromRadio(FromRadioEncoder.myInfo(42))
        l.onFromRadio(FromRadioEncoder.nodeInfo(7, "Other", "O", 0, 1.0, 2.0))   // someone else: ignored
        l.onFromRadio(FromRadioEncoder.nodeInfo(42, "Me", "M", 0, 45.0, -75.0))
        assertEquals(1, got.size)
        assertEquals(45.0, got[0].first.lat, 1e-6); assertTrue(got[0].second)
    }

    @Test fun accuracyFromABroadcastIsRememberedForADayAndRestoredAfterRestart() {
        val clock = ManualClock()
        val r = Rec(clock)
        var saved: String? = null
        r.src.onQuality = { saved = it }
        r.src.onReport(pos(45.0, -75.0, bits = null, hdop = null, timeS = clock.wallMs() / 1000), fromConfig = true)   // NodeInfo: no DOP
        clock.advance(1000); r.src.tick()
        assertNull(r.fixes.last().accuracyM)
        assertNull(r.src.shortAccuracy)
        assertTrue(r.status.contains("not known yet"))
        r.src.onReport(pos(45.0, -75.0, bits = 13, hdop = 160))                       // blurred broadcast, but its DOP counts
        assertEquals("≈±8 m", r.src.shortAccuracy)
        clock.advance(3 * 3_600_000L); r.src.tick()
        assertEquals(8.0, r.fixes.last().accuracyM)                                    // still used 3 h later (was 1 h)
        // next run: restored from the saved value, dropped once older than a day
        val again = Rec(clock)
        again.src.restoreQuality(saved)
        again.src.onReport(pos(45.0, -75.0, bits = null, hdop = null, timeS = clock.wallMs() / 1000), fromConfig = true)
        clock.advance(1000); again.src.tick()
        assertEquals(8.0, again.fixes.last().accuracyM)
        clock.advance(22 * 3_600_000L); again.src.tick()
        assertNull(again.fixes.last().accuracyM)
        assertNull(RadioGpsSource.decodeQuality("garbage"))
    }

    @Test fun anotherConnectedRadioHearingTheBroadcastSuppliesTheDop() {
        val clock = ManualClock()
        val st = meshsurvey.core.engine.Station(meshsurvey.core.store.SqlStore(meshsurvey.core.testsupport.testDriver()), clock)
        val a = st.primary; val b = st.addReceiver()
        val la = Harness.TestLink(); val lb = Harness.TestLink()
        a.connect(la); b.connect(lb)
        for ((l, n) in listOf(la to 0x111L, lb to 0x222L)) {
            val nonce = meshsurvey.core.proto.Wire.parse(l.sent.first()).f(3)!!.u32
            l.l!!.onFromRadio(FromRadioEncoder.myInfo(n)); l.l!!.onFromRadio(FromRadioEncoder.configComplete(nonce))
        }
        // radio B hears radio A's position broadcast over the air (PDOP 2.0, 7 satellites)
        val payload = meshsurvey.core.proto.ProtoWriter().sfixed32(1, 450000000).sfixed32(2, -750000000).varint(11, 200L).varint(19, 7L).toByteArray()
        lb.l!!.onFromRadio(FromRadioEncoder.packet(0x111, meshsurvey.core.proto.BROADCAST, 9, meshsurvey.core.proto.Port.POSITION, payload, snr = 5f, rssi = -90, rxTime = clock.wallMs() / 1000))
        assertEquals(7, a.radioGps.satellites)
        assertEquals("≈±10 m", a.radioGps.shortAccuracy)
        assertNull(b.radioGps.satellites)
        assertTrue(st.store.getMeta("gps_quality:${0x111}") != null)
    }
}
