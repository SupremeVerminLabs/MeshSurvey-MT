package meshsurvey.core

import meshsurvey.core.engine.Station
import meshsurvey.core.model.EquipmentProfile
import meshsurvey.core.model.Fix
import meshsurvey.core.model.FixSource
import meshsurvey.core.proto.BROADCAST
import meshsurvey.core.proto.Port
import meshsurvey.core.proto.Wire
import meshsurvey.core.proto.f
import meshsurvey.core.radio.RadioState
import meshsurvey.core.sim.FromRadioEncoder
import meshsurvey.core.store.ObsFilter
import meshsurvey.core.store.SqlStore
import meshsurvey.core.util.ManualClock
import meshsurvey.core.testsupport.testDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StationTest {
    private fun ready(link: Harness.TestLink, node: Long, preset: Int) {
        val nonce = Wire.parse(link.sent.first()).f(3)!!.u32
        link.l!!.onFromRadio(FromRadioEncoder.myInfo(node))
        link.l!!.onFromRadio(FromRadioEncoder.loraConfig(preset, 1, 20, 3, true))
        link.l!!.onFromRadio(FromRadioEncoder.configComplete(nonce))
    }

    @Test fun twoRadiosLogIndependentlyOnDifferentPresetsWithOneStationPosition() {
        val clock = ManualClock()
        val st = Station(SqlStore(testDriver()), clock)
        val a = st.primary; val b = st.addReceiver()
        val la = Harness.TestLink(); val lb = Harness.TestLink()
        a.connect(la); b.connect(lb)
        ready(la, 0x111, preset = 0)      // LONG_FAST
        ready(lb, 0x222, preset = 6)      // SHORT_FAST
        assertEquals(RadioState.READY, a.radio.state); assertEquals(RadioState.READY, b.radio.state)
        st.onFix(Fix(45.0, -75.0, 4.0, null, clock.wallMs(), clock.monotonicMs(), FixSource.GPS))
        la.l!!.onFromRadio(FromRadioEncoder.packet(7, BROADCAST, 1, Port.TELEMETRY, byteArrayOf(), snr = 3f, rssi = -100, rxTime = clock.wallMs() / 1000))
        lb.l!!.onFromRadio(FromRadioEncoder.packet(8, BROADCAST, 2, Port.TELEMETRY, byteArrayOf(), snr = 9f, rssi = -90, rxTime = clock.wallMs() / 1000))
        val sessions = st.store.sessions()
        assertEquals(setOf(0x111L, 0x222L), sessions.map { it.receiverNode }.toSet())
        assertEquals(2, sessions.map { it.preset }.toSet().size)
        val oa = st.store.observations(ObsFilter(receiverNode = 0x111)).single()
        val ob = st.store.observations(ObsFilter(receiverNode = 0x222)).single()
        assertEquals(45.0, oa.lat); assertEquals(45.0, ob.lat)            // the station position applies to both
        assertTrue(oa.preset != ob.preset)
        clock.advance(31_000); st.tick()
        assertEquals(setOf(0x111L, 0x222L), st.store.windows(null).mapNotNull { it.receiverNode }.toSet())
        st.removeReceiver(b)
        assertEquals(1, st.receivers.value.size)
        assertTrue(st.store.sessions().first { it.receiverNode == 0x222L }.endedMs != null)
    }

    @Test fun eachRadioRemembersItsOwnEquipmentProfile() {
        val clock = ManualClock()
        val st = Station(SqlStore(testDriver()), clock)
        val a = st.primary; val la = Harness.TestLink(); a.connect(la); ready(la, 0x111, 0)
        a.saveProfile(EquipmentProfile("x", "Fibreglass 5.8 dBi"))
        a.setActiveProfile("x")
        val b = st.addReceiver(); val lb = Harness.TestLink(); b.connect(lb); ready(lb, 0x222, 0)
        assertNull(b.activeProfileId.value)                                // not inherited from the other radio
        assertEquals(1, b.profiles.value.size)                             // but the profile list is shared
        assertEquals("x", st.store.sessions().first { it.receiverNode == 0x111L && it.endedMs == null }.profileId)
    }

    @Test fun onlyThePrimaryEngineRecoversInterruptedSessions() {
        val clock = ManualClock()
        val store = SqlStore(testDriver())
        val st = Station(store, clock)
        val la = Harness.TestLink(); st.primary.connect(la); ready(la, 0x111, 0)
        st.addReceiver()                                                    // must not mark the primary's open session interrupted
        assertEquals(meshsurvey.core.model.SessionStatus.ACTIVE, store.sessions().single().status)
    }
}

class RxOnlyTest {
    private fun ready(link: Harness.TestLink, node: Long) {
        val nonce = Wire.parse(link.sent.first()).f(3)!!.u32
        link.l!!.onFromRadio(FromRadioEncoder.myInfo(node))
        link.l!!.onFromRadio(FromRadioEncoder.loraConfig(0, 1, 20, 3, true))
        link.l!!.onFromRadio(FromRadioEncoder.configComplete(nonce))
    }

    @Test fun rxOnlyBlocksEveryAppTransmissionOnEveryRadioAndIsRemembered() {
        val clock = ManualClock()
        val store = SqlStore(testDriver())
        val st = Station(store, clock)
        val la = Harness.TestLink(); st.primary.connect(la); ready(la, 0x111)
        val b = st.addReceiver(); val lb = Harness.TestLink(); b.connect(lb); ready(lb, 0x222)
        st.primary.ab.setPeer(0x999, confirmed = true)
        b.ab.setPeer(0x999, confirmed = true)
        st.setRxOnly(true)
        val sentBefore = la.sent.size + lb.sent.size
        assertTrue(st.primary.ab.sendSingle()!!.contains("RX-only"))
        assertTrue(b.ab.startAuto(60, 600)!!.contains("RX-only"))
        // even a direct call at the lowest level is refused
        assertNull(st.primary.radio.sendData(0x999, meshsurvey.core.proto.Port.PRIVATE, byteArrayOf(1), wantAck = true))
        assertEquals(sentBefore, la.sent.size + lb.sent.size)
        assertEquals(0, st.primary.radio.appTransmissions)
        assertTrue(Station(store, clock).shared.rxOnly.value)                    // survives a restart
    }

    @Test fun turningRxOnlyOnStopsARunningSessionAndCountsWhatWasSent() {
        val clock = ManualClock()
        val st = Station(SqlStore(testDriver()), clock)
        val la = Harness.TestLink(); st.primary.connect(la); ready(la, 0x111)
        st.primary.ab.setPeer(0x999, confirmed = true)
        assertNull(st.primary.ab.sendSingle())
        clock.advance(1000); st.tick()
        assertEquals(1, st.primary.appTx.value.count); assertTrue(st.primary.appTx.value.estAirtimeMs > 0)
        clock.advance(200_000)                                                   // respect the minimum spacing after the single test
        assertNull(st.primary.ab.startAuto(60, 600))
        assertTrue(st.primary.ab.state.autoRunning)
        st.setRxOnly(true)
        assertTrue(!st.primary.ab.state.autoRunning)
    }
}

class SimulationIsolationTest {
    @Test fun simulatedRadioNeverWritesToTheSurveyDatabase() {
        val clock = ManualClock()
        val real = SqlStore(testDriver()); val sim = SqlStore(testDriver())
        val st = Station(real, clock, simStoreFactory = { sim })
        val world = meshsurvey.core.sim.SimWorld(seed = 3)
        val loc = meshsurvey.core.sim.SimulatedLocation(world, clock)
        val e = st.addSimulatedReceiver()
        assertTrue(st.isSimulated(e)); assertTrue(!st.isSimulated(st.primary))
        e.connect(meshsurvey.core.sim.SimulatedLink(world, clock, { loc.current().let { it.lat to it.lon } }))
        repeat(120) { clock.advance(1000); st.onFix(loc.current()); st.tick() }
        assertTrue(sim.countObservations() > 0)
        assertEquals(0, real.countObservations())
        assertTrue(real.sessions().isEmpty())
        assertTrue(meshsurvey.core.export.Bundle.export(real, "dev", "t", clock.wallMs()).contains("\"contains_simulated\":false"))
    }
}
