package meshsurvey.core

import meshsurvey.core.abtest.Outcome
import meshsurvey.core.abtest.RoutingOutcome
import meshsurvey.core.abtest.SessionCompare
import meshsurvey.core.abtest.TestPayload
import meshsurvey.core.engine.SurveyEngine
import meshsurvey.core.export.Bundle
import meshsurvey.core.map.CellClass
import meshsurvey.core.map.Coverage
import meshsurvey.core.map.Grid
import meshsurvey.core.model.DeliveryMode
import meshsurvey.core.model.Source
import meshsurvey.core.model.TestMode
import meshsurvey.core.model.TestRole
import meshsurvey.core.model.TxStatus
import meshsurvey.core.proto.MeshProto
import meshsurvey.core.proto.Port
import meshsurvey.core.proto.Wire
import meshsurvey.core.proto.f
import meshsurvey.core.sim.FromRadioEncoder
import meshsurvey.core.sim.SimWorld
import meshsurvey.core.sim.SimulatedLink
import meshsurvey.core.sim.SimulatedLocation
import meshsurvey.core.store.SqlStore
import meshsurvey.core.testsupport.testDriver
import meshsurvey.core.util.ManualClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AbTestAndMapTest {
    private val peer = 0x0b0b0b0bL
    private val me = 0x0a0a0a0aL

    private fun sentPackets(h: Harness) = h.link.sent.mapNotNull { Wire.parse(it).f(1)?.bytes }.map { MeshProto.decodePacket(it) }
    private fun routing(h: Harness, from: Long, reqId: Long, err: Int) =
        h.rx(FromRadioEncoder.packet(from, me, 900 + reqId % 50, Port.ROUTING, FromRadioEncoder.routing(err), requestId = reqId,
            snr = if (from == me) null else 2f, rssi = if (from == me) null else -100))

    @Test fun payloadRoundTrip() {
        val p = TestPayload("0011223344556677", 513, 1_764_000_123)
        val b = p.encode()
        assertEquals(TestPayload.SIZE, b.size)
        assertEquals(p, TestPayload.decode(b))
        assertNull(TestPayload.decode(b.copyOf(17)))
    }

    @Test fun noEnrollmentButParticipationMustBeEstablished() {
        val h = Harness(myNode = me)
        h.engine.ab.setPeer(peer)                       // chosen, participation not established
        assertTrue(h.engine.ab.sendSingle()!!.startsWith("Enter Device B's participation code"))
        h.engine.ab.setPeer(peer, confirmed = true)     // stock B the operator controls; no registry, no server
        assertNotNull(h.engine.ab.setMode(DeliveryMode.DIRECT_ONLY)) // direct-only needs a MeshSurvey B
        assertNull(h.engine.ab.sendSingle())
        assertTrue(h.store.testDevices().isEmpty())
        assertTrue(h.engine.ab.sendSingle()!!.startsWith("Wait"))
        assertEquals("OPERATOR_CONTROLS_B", h.store.testSessions().first().participation!!.name)
    }

    @Test fun classificationFollowsFirmwareSemantics() {
        assertEquals(TxStatus.DELIVERED, RoutingOutcome.classify(peer, peer, 0))
        assertEquals(TxStatus.REJECTED, RoutingOutcome.classify(peer, peer, 35))      // PKI_UNKNOWN_PUBKEY from B
        assertEquals(TxStatus.RELAY_OBSERVED, RoutingOutcome.classify(me, peer, 0))    // implicit ACK
        assertEquals(TxStatus.RELAY_OBSERVED, RoutingOutcome.classify(0x77, peer, 0))  // next-hop relay's 0-hop ACK
        assertEquals(TxStatus.RELAY_OBSERVED, RoutingOutcome.classify(0, peer, 0))     // local report with from=0
        assertEquals(TxStatus.NO_ACK, RoutingOutcome.classify(me, peer, 5))            // MAX_RETRANSMIT
        assertEquals(TxStatus.NOT_TRANSMITTED, RoutingOutcome.classify(me, peer, 9))   // DUTY_CYCLE_LIMIT
        assertFalse(RoutingOutcome.canReplace(TxStatus.DELIVERED, TxStatus.NO_ACK))
        assertTrue(RoutingOutcome.canReplace(TxStatus.NO_ACK, TxStatus.DELIVERED))
    }

    @Test fun ackedStatusesIncludingLateAckAfterNoAck() {
        val h = Harness(myNode = me)
        h.engine.ab.setPeer(peer, confirmed = true); h.fix()
        h.engine.ab.sendSingle()
        val id1 = sentPackets(h).last().id
        routing(h, me, id1, 0)
        assertEquals(TxStatus.RELAY_OBSERVED, h.store.testTx().last().status)
        routing(h, peer, id1, 0)
        assertEquals(TxStatus.DELIVERED, h.store.testTx().last().status)

        h.clock.advance(200_000); h.fix(); assertNull(h.engine.ab.sendSingle())
        val id2 = sentPackets(h).last().id
        routing(h, me, id2, 5)
        assertEquals(TxStatus.NO_ACK, h.store.testTx().last().status)
        routing(h, peer, id2, 0) // a late destination ACK proves delivery after all
        assertEquals(TxStatus.DELIVERED, h.store.testTx().last().status)

        h.clock.advance(200_000); h.fix(); assertNull(h.engine.ab.sendSingle())
        val id3 = sentPackets(h).last().id
        routing(h, me, id3, 9)
        assertEquals(TxStatus.NOT_TRANSMITTED, h.store.testTx().last().status)
        assertEquals("DUTY_CYCLE_LIMIT", h.store.testTx().last().routingError)

        h.clock.advance(200_000); h.fix(); assertNull(h.engine.ab.sendSingle())
        h.clock.advance(91_000); h.engine.tick()
        assertEquals(TxStatus.UNKNOWN, h.store.testTx().last().status)
        val st = h.engine.abState.value
        assertEquals(listOf(2, 0, 0, 1, 1), listOf(st.delivered, st.relayObserved, st.noAck, st.notTransmitted, st.unknown))
    }

    @Test fun directOnlyIsOneHopZeroTransmissionWithoutAck() {
        val h = Harness(myNode = me)
        assertNull(h.engine.ab.setTargetWithCode(peer, 123456))
        assertNull(h.engine.ab.setMode(DeliveryMode.DIRECT_ONLY))
        h.engine.ab.sendSingle()
        val raw = Wire.parse(Wire.parse(h.link.sent.last()).f(1)!!.bytes!!)
        assertNull(raw.f(10), "want_ack must not be set")
        assertNull(raw.f(9), "hop_limit must be 0 (omitted)")
        val t = h.store.testTx().last()
        assertEquals(TxStatus.UNKNOWN, t.status); assertEquals(DeliveryMode.DIRECT_ONLY, t.deliveryMode); assertEquals(0, t.hopLimit)
        assertEquals("LONG_FAST", t.preset); assertEquals(906.875, t.frequencyMhz!!, 1e-6)
    }

    @Test fun autoSessionRespectsAirtimeBudget() {
        val h = Harness(myNode = me)
        h.engine.ab.setPeer(peer, confirmed = true)
        assertTrue(h.engine.ab.startAuto(intervalS = 10, durationS = 300)!!.contains("at least 30"))
        assertTrue(h.engine.ab.startAuto(intervalS = 60, durationS = 7200)!!.contains("Duration"))
        val b = h.engine.ab.budget()!!
        assertTrue(b.minIntervalS > 60, "LONG_FAST with ACK needs more than 60 s at 2 % worst-case duty, got ${b.minIntervalS}")
        assertNull(h.engine.ab.startAuto(intervalS = 60, durationS = 600))
        assertEquals(b.minIntervalS, h.engine.abState.value.effectiveIntervalS)
        repeat(700) { h.clock.advance(1_000); h.engine.tick() }
        assertFalse(h.engine.abState.value.autoRunning)
        val sent = h.store.testTx()
        assertEquals(minOf(600 / b.minIntervalS + 1, b.maxTestsPerSession), sent.size)
        assertTrue(sent.zipWithNext().all { (a, c) -> c.sentMs - a.sentMs >= b.minIntervalS * 1000L })
        assertEquals("duration reached", h.store.testSessions().first { it.mode == TestMode.AUTO }.endReason)
    }

    @Test fun autoSessionStopsOnDisconnectAndBackground() {
        val h = Harness(myNode = me)
        h.engine.ab.setPeer(peer, confirmed = true)
        h.engine.ab.startAuto(60, 600); h.engine.onAppBackground()
        assertEquals("app moved to background", h.store.testSessions().first().endReason)
        h.clock.advance(200_000)
        h.engine.ab.startAuto(60, 600); h.engine.disconnect()
        assertTrue(h.store.testSessions().any { it.endReason == "radio connection lost" })
    }

    @Test fun referenceRecordsOnlyDuringExplicitSessionWithMatchingCode() {
        val b = Harness(myNode = peer)
        fun send(id: Long, code: Int?, to: Long = peer) = b.rx(FromRadioEncoder.packet(me, to, id, Port.PRIVATE,
            TestPayload("aabbccddeeff0011", id.toInt(), b.liveTime(), code).encode(), snr = -4f, rssi = -115, rxTime = b.liveTime(), hopLimit = 0, hopStart = 0))
        send(1, 111111)                                   // no reference session -> not recorded
        assertEquals(0, b.store.testRx().size); assertEquals(1, b.engine.referenceState.value.ignoredNoSession)
        assertNull(b.engine.reference.start(600))
        val code = b.engine.referenceState.value.session!!.refCode!!
        send(2, if (code == 999_999) 100_000 else code + 1) // wrong code
        send(3, null)                                     // v1 payload without a code
        assertEquals(2, b.engine.referenceState.value.ignoredWrongCode)
        send(4, code)                                     // participating
        send(5, code, to = 0x123)                         // not addressed to B
        val r = b.store.testRx().single()
        assertEquals(-115, r.rssi); assertEquals("LONG_FAST", r.preset)
        assertEquals(b.engine.referenceState.value.session!!.id, r.refSessionId)
        // session ends by itself
        b.clock.advance(601_000); b.engine.tick()
        assertNull(b.engine.referenceState.value.session)
        send(6, code)
        assertEquals(1, b.store.testRx().size)
        assertTrue(b.store.observations().any { it.type.name == "SURVEY_TEST" }) // still an RF observation
    }

    @Test fun endToEndCompareViaBundles() {
        val clock = ManualClock()
        val a = Harness(clock = clock, myNode = me)
        val b = Harness(clock = clock, myNode = peer)
        assertNull(b.engine.reference.start(3600))
        val code = b.engine.referenceState.value.session!!.refCode!!
        a.engine.ab.setTargetWithCode(peer, code); a.engine.ab.setMode(DeliveryMode.DIRECT_ONLY); a.fix()
        // #0 received by B, #1 lost while B logging, #2 after B disconnected
        repeat(3) { i ->
            if (i > 0) { clock.advance(65_000); a.fix() }
            a.engine.ab.sendSingle()
            val pkt = sentPackets(a).last()
            if (i == 0) b.rx(FromRadioEncoder.packet(me, peer, pkt.id, Port.PRIVATE, pkt.payload, snr = 3f, rssi = -101, rxTime = clock.wallMs() / 1000))
            if (i == 1) { clock.advance(95_000); b.engine.tick(); b.engine.disconnect() }
        }
        clock.advance(120_000); a.engine.tick()
        val res = Bundle.import(Bundle.export(b.store, b.engine.deviceId, "t", clock.wallMs()), a.store, a.engine.deviceId, clock.wallMs())
        assertEquals(1, res.testRx)
        val sid = a.store.testTx().first().testSessionId
        val refs = a.store.testSessions().filter { it.role == TestRole.B_REFERENCE && it.originDevice == b.engine.deviceId }
        assertEquals(1, refs.size)
        val cmp = SessionCompare.compare(a.store.testTx(sid), a.store.testRx(sid), a.store.windows(b.engine.deviceId), bRefSessions = refs)
        assertEquals(listOf(Outcome.RECEIVED_AT_B, Outcome.NOT_RECEIVED, Outcome.UNKNOWN), cmp.rows.map { it.outcome })
        assertEquals(0.5, cmp.measuredDeliveryRatio)
    }

    @Test fun gridDistinguishesNoDataNothingHeardNoAckAndNotReceived() {
        val h = Harness(myNode = me)
        h.engine.ab.setPeer(peer, confirmed = true)
        h.fix(45.0, -75.0)
        h.rx(FromRadioEncoder.packet(3, 0xffffffffL, 1, Port.TEXT, null, snr = 4f, rssi = -100, rxTime = h.liveTime(), hopLimit = 3, hopStart = 3))
        h.clock.advance(6_000); h.fix(45.01, -75.0)                       // surveyed, nothing heard
        h.clock.advance(200_000); h.fix(45.02, -75.0); h.engine.ab.sendSingle() // no ACK after retries
        routing(h, me, sentPackets(h).last().id, 5)
        val g = Grid(250.0)
        val tests = h.store.testTx()
        val cells = Coverage.aggregate(g, h.store.observations(), h.store.track(), tests)
        assertEquals(CellClass.MEASURED, cells[g.key(45.0, -75.0)]!!.cls)
        assertEquals(CellClass.SURVEYED_NOTHING_HEARD, cells[g.key(45.01, -75.0)]!!.cls)
        assertEquals(CellClass.TEST_NO_ACK, cells[g.key(45.02, -75.0)]!!.cls)   // unknown, NOT "failed"
        assertNull(cells[g.key(45.05, -75.0)])
        // with B's log showing it was listening and got nothing, the same test becomes "not received"
        val confirmed = Coverage.aggregate(g, emptyList(), emptyList(), tests) { Outcome.NOT_RECEIVED }
        assertEquals(CellClass.TEST_NOT_RECEIVED, confirmed[g.key(45.02, -75.0)]!!.cls)
    }

    @Test fun gridKeysAreStable() {
        val g = Grid(100.0)
        val k = g.key(-33.8688, 151.2093)
        val b = g.bounds(k)
        assertTrue(-33.8688 >= b.lat0 && -33.8688 < b.lat1 && 151.2093 >= b.lon0 && 151.2093 < b.lon1)
        assertEquals(k, g.key(b.centerLat, b.centerLon))
    }

    @Test fun simulatorProducesSimulatedSurveyAndTestFlow() {
        val clock = ManualClock()
        val world = SimWorld(seed = 3)
        val loc = SimulatedLocation(world, clock)
        val engine = SurveyEngine(SqlStore(testDriver()), clock)
        engine.connect(SimulatedLink(world, clock, { loc.current().let { it.lat to it.lon } }))
        engine.ab.setPeer(world.peerNodeNum, confirmed = true)
        repeat(1800) { i ->
            clock.advance(1_000); engine.onFix(loc.current()); engine.tick()
            if (i == 5) assertNull(engine.ab.startAuto(60, 1200))
        }
        val obs = engine.store.observations()
        assertTrue(obs.size > 20, "expected simulated receptions, got ${obs.size}")
        assertTrue(obs.all { it.source == Source.SIMULATED })
        val tx = engine.store.testTx()
        assertTrue(tx.size >= 5)
        assertTrue(tx.all { it.source == Source.SIMULATED && it.status != TxStatus.SENT })
        assertNotNull(tx.first().frequencyMhz)
    }
}
