package meshsurvey.core

import meshsurvey.core.engine.RadioGpsSource
import meshsurvey.core.model.Fix
import meshsurvey.core.model.FixSource
import meshsurvey.core.proto.PositionInfo
import meshsurvey.core.radio.RadioClient
import meshsurvey.core.radio.RadioListener
import meshsurvey.core.sim.FromRadioEncoder
import meshsurvey.core.util.ManualClock
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

    @Test fun coarsePublicChannelPrecisionIsRefused() {
        val r = Rec(ManualClock())
        r.src.onReport(pos(45.0, -75.0, bits = 13))         // ~2.9 km
        r.clock.advance(1000); r.src.tick()
        assertTrue(r.fixes.isEmpty())
        assertTrue(r.status.contains("too coarse"))
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
}
