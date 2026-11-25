package meshsurvey.core

import meshsurvey.core.engine.SurveyEngine
import meshsurvey.core.map.MbTiles
import meshsurvey.core.model.PresetChangeStatus
import meshsurvey.core.model.TestRole
import meshsurvey.core.proto.MeshEnums
import meshsurvey.core.proto.MeshProto
import meshsurvey.core.proto.ProtoWriter
import meshsurvey.core.proto.Wire
import meshsurvey.core.proto.all
import meshsurvey.core.proto.f
import meshsurvey.core.radio.Airtime
import meshsurvey.core.radio.FreqSource
import meshsurvey.core.radio.FrequencyCalc
import meshsurvey.core.radio.LoraParams
import meshsurvey.core.radio.Presets
import meshsurvey.core.radio.RadioState
import meshsurvey.core.radio.TestBudget
import meshsurvey.core.sim.SimWorld
import meshsurvey.core.sim.SimulatedLink
import meshsurvey.core.store.Schema
import meshsurvey.core.store.SqlStore
import meshsurvey.core.testsupport.testDriver
import meshsurvey.core.util.ManualClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RfPlanTest {
    private fun calc(region: Int, preset: Int, name: String? = "", slot: Int = 0, override: Double? = null, use: Boolean = true) =
        FrequencyCalc.compute(region, use, preset, channelNum = slot, overrideMhz = override, offsetMhz = 0.0, primaryChannelName = name)

    @Test fun matchesPublishedDefaults() {
        // Published defaults: US LongFast slot 20 = 906.875 MHz; EU_868 slot 1 = 869.525 MHz; EU_433 slot 4 = 433.875 MHz
        calc(1, 0).let { assertEquals(906.875, it.mhz!!, 1e-4); assertEquals(20, it.slot); assertEquals(104, it.numSlots); assertEquals(FreqSource.CALCULATED, it.source) }
        calc(3, 0).let { assertEquals(869.525, it.mhz!!, 1e-4); assertEquals(1, it.slot) }
        calc(2, 0).let { assertEquals(433.875, it.mhz!!, 1e-4); assertEquals(4, it.slot) }
    }

    @Test fun shortTurboAndOtherCases() {
        calc(1, 8).let { assertEquals(926.75, it.mhz!!, 1e-4); assertEquals(50, it.slot); assertEquals(52, it.numSlots) }
        assertEquals(913.125, calc(1, 4).mhz!!, 1e-4)                          // MediumFast US default
        assertEquals(906.875, calc(1, 0, name = "LongFast").mhz!!, 1e-4)        // explicit name equal to the default
        assertEquals(911.75, calc(1, 8, name = "LongFast").mhz!!, 1e-4)        // same name, 500 kHz plan: slot 20 of 52
        calc(1, 0, slot = 1).let { assertEquals(902.125, it.mhz!!, 1e-4); assertEquals(1, it.slot) } // explicit slot
        calc(1, 0, override = 915.5).let { assertEquals(FreqSource.CONFIGURED_OVERRIDE, it.source); assertEquals(915.5, it.mhz!!, 1e-9) }
        assertEquals(FreqSource.UNKNOWN, calc(1, 0, name = null).source)           // channel name not yet received
        assertEquals(FreqSource.UNKNOWN, calc(1, 12).source)                       // NARROW_FAST: not in firmware master
        assertEquals(FreqSource.UNKNOWN, calc(29, 0).source)                       // EU_866 (2.8 region) not in table
        calc(3, 8).let { assertTrue(it.note.contains("falls back")); assertEquals(869.525, it.mhz!!, 1e-4) } // 500 kHz in EU_868
    }

    @Test fun djb2() {
        assertEquals(5381L, FrequencyCalc.djb2(""))
        assertEquals(19L, FrequencyCalc.djb2("LongFast") % 104)
    }

    @Test fun airtimeMatchesSemtechFormula() {
        // SF7 / 125 kHz / CR 4/5 / 8-symbol preamble / 20-byte payload = 56.58 ms (Semtech LoRa calculator)
        assertEquals(56.58, Airtime.ms(20, LoraParams(125.0, 7, 5), preamble = 8), 0.01)
        val lf = Airtime.ms(Airtime.testPacketBytes, Presets.params(0)!!)
        val st = Airtime.ms(Airtime.testPacketBytes, Presets.params(8)!!)
        assertTrue(lf > 500 && lf < 900, "LongFast test packet ${lf} ms")
        assertTrue(st < 60, "ShortTurbo test packet ${st} ms")
    }

    @Test fun budgets() {
        val lfAck = TestBudget.of(Presets.params(0)!!, wantAck = true)
        val lfDirect = TestBudget.of(Presets.params(0)!!, wantAck = false)
        val stAck = TestBudget.of(Presets.params(8)!!, wantAck = true)
        assertEquals(3, lfAck.transmissionsPerTest); assertEquals(1, lfDirect.transmissionsPerTest)
        assertTrue(lfAck.minIntervalS > lfDirect.minIntervalS)
        assertEquals(30, stAck.minIntervalS) // short airtime: the 30 s absolute minimum applies
        assertTrue(lfAck.worstCaseMsPerTest * lfAck.maxTestsPerSession <= TestBudget.MAX_SESSION_AIRTIME_MS)
    }

    @Test fun patchPreservesEveryOtherFieldByteForByte() {
        val original = ProtoWriter().varint(7, 1).varint(8, 3).bool(9, true).int32(10, 27).varint(11, 20).bool(13, true)
            .varint(103, 0x1234).varint(103, 0x5678).bool(104, true).varint(106, 2).toByteArray()
        val patched = Wire.patchVarints(original, mapOf(1 to 1L, 2 to 8L))
        val f = Wire.parse(patched)
        assertEquals(8, f.f(2)!!.int); assertTrue(f.f(1)!!.bool)
        assertEquals(listOf(0x1234L, 0x5678L), f.all(103).map { it.raw })
        // stripping the two patched fields gives back the original bytes exactly
        assertTrue(Wire.patchVarints(patched, mapOf(1 to 0L, 2 to 0L)).contentEquals(original))
        assertTrue(Wire.sameFields(original, Wire.parse(original).reversed().let { original }))
        assertFalse(Wire.sameFields(original, patched))
    }

    @Test fun heartbeatNeverAsksForNodeInfoBroadcast() {
        // PhoneAPI: heartbeat nonce == 1 triggers a NodeInfo broadcast. Ours is an empty Heartbeat (nonce 0).
        val hb = Wire.parse(MeshProto.heartbeat()).f(7)!!
        assertEquals(0, hb.bytes!!.size)
    }

    @Test fun adminMessageShape() {
        val raw = ProtoWriter().bool(1, true).varint(2, 8).toByteArray()
        val tr = MeshProto.sendAdminLocal(0x51a00001, MeshProto.adminSetLora(raw), 42)
        val pkt = Wire.parse(Wire.parse(tr).f(1)!!.bytes!!)
        assertEquals(0x51a00001L, pkt.f(2)!!.u32)
        assertNull(pkt.f(10)) // no want_ack
        val data = Wire.parse(pkt.f(4)!!.bytes!!)
        assertEquals(6, data.f(1)!!.int)
        val lora = Wire.parse(Wire.parse(Wire.parse(data.f(2)!!.bytes!!).f(34)!!.bytes!!).f(6)!!.bytes!!)
        assertEquals(8, lora.f(2)!!.int)
        assertEquals(8, MeshEnums.presetNumber("SHORT_TURBO"))
    }
}

class PresetChangeTest {
    private fun setup(): Triple<SurveyEngine, SimulatedLink, ManualClock> {
        val clock = ManualClock()
        val world = SimWorld(seed = 5)
        val link = SimulatedLink(world, clock, { world.centerLat to world.centerLon })
        val engine = SurveyEngine(SqlStore(testDriver()), clock)
        engine.connect(link)
        repeat(3) { clock.advance(1000); engine.tick() }
        assertEquals(RadioState.READY, engine.radio.state)
        return Triple(engine, link, clock)
    }

    private fun run(engine: SurveyEngine, clock: ManualClock, s: Int) = repeat(s) { clock.advance(1000); engine.tick() }

    @Test fun planShowsEverythingAndSendsNothing() {
        val (engine, link, _) = setup()
        val plan = engine.presets.plan(8)
        assertTrue(plan.canApply, plan.blockers.toString())
        assertEquals("LONG_FAST", plan.currentName); assertEquals("SHORT_TURBO", plan.targetName)
        assertEquals(906.875, plan.frequencyBefore.mhz!!, 1e-4); assertEquals(926.75, plan.frequencyAfter.mhz!!, 1e-4)
        assertTrue(plan.warnings.any { it.contains("500 kHz") })
        assertTrue(plan.warnings.any { it.contains("Device B must") })
        assertEquals(0, link.adminMessagesReceived) // planning alone never touches the radio
        assertFalse(engine.presets.plan(0).canApply) // already on LONG_FAST
        assertFalse(engine.presets.plan(13).canApply) // parameters unknown
    }

    @Test fun applyConfirmTestTagRestoreExactly() {
        val (engine, link, clock) = setup()
        val original = link.loraRaw.copyOf()
        engine.ab.setPeer(SimWorld(seed = 5).peerNodeNum, confirmed = true)
        assertNull(engine.presets.apply(engine.presets.plan(8), autoRestore = true))
        assertEquals(PresetChangeStatus.APPLYING, engine.presetChange.value!!.status)
        assertTrue(engine.ab.sendSingle()!!.startsWith("Waiting")) // no tests while the radio config is unconfirmed
        run(engine, clock, 5) // simulated reboot + config re-read
        assertEquals("SHORT_TURBO", engine.radio.info.preset)
        assertEquals(PresetChangeStatus.ACTIVE, engine.presetChange.value!!.status)
        // unmodelled fields survived the change
        assertEquals(listOf(0x1234L, 0x5678L), Wire.parse(link.loraRaw).all(103).map { it.raw })
        assertEquals(27, Wire.parse(link.loraRaw).f(10)!!.int)
        // tests are tagged with the preset they ran on
        assertNull(engine.ab.startAuto(30, 60))
        run(engine, clock, 70)
        val tx = engine.store.testTx()
        assertTrue(tx.isNotEmpty() && tx.all { it.preset == "SHORT_TURBO" && it.frequencyMhz != null })
        assertEquals("SHORT_TURBO", engine.store.testSessions().first { it.role == TestRole.A_SURVEY }.preset)
        // session end triggered the agreed automatic restore
        run(engine, clock, 15)
        assertTrue(link.loraRaw.contentEquals(original), "restore must send the original bytes exactly")
        assertEquals("LONG_FAST", engine.radio.info.preset)
        assertNull(engine.presetChange.value) // RESTORED -> no open change
        assertEquals(PresetChangeStatus.RESTORED, engine.store.presetChanges().last().status)
    }

    @Test fun openChangeSurvivesRestartAndIsSurfaced() {
        val (engine, _, clock) = setup()
        engine.presets.apply(engine.presets.plan(6), autoRestore = false)
        run(engine, clock, 5)
        val store = engine.store
        val restarted = SurveyEngine(store, clock)
        assertEquals(PresetChangeStatus.ACTIVE, restarted.presetChange.value!!.status)
        assertEquals("SHORT_FAST", restarted.presetChange.value!!.targetPreset)
    }

    @Test fun unconfirmedApplyIsFlaggedNotAssumed() {
        val (engine, link, clock) = setup()
        engine.presets.apply(engine.presets.plan(8), autoRestore = false)
        link.close() // radio vanished before confirming
        run(engine, clock, 200)
        assertEquals(PresetChangeStatus.APPLY_UNCONFIRMED, engine.store.presetChanges().last().status)
    }
}

class StorageExtrasTest {
    @Test fun migratesV1DatabaseToCurrent() {
        val d = testDriver()
        Schema.migrations[0].forEach { d.execute(it) }
        d.execute("PRAGMA user_version = 1")
        d.execute("""INSERT INTO test_tx(test_session_id,seq,sent_ms,packet_id,to_node,want_ack,status,routing_error,source)
            VALUES('s',0,1,1,2,1,'IMPLICIT_ACK',NULL,'LOCAL_RF'),('s',1,2,2,2,1,'FAILED','MAX_RETRANSMIT','LOCAL_RF'),
                  ('s',2,3,3,2,1,'FAILED','DUTY_CYCLE_LIMIT','LOCAL_RF'),('s',3,4,4,2,1,'NOT_CONFIRMED',NULL,'LOCAL_RF')""")
        val store = SqlStore(d)
        assertEquals(Schema.version, store.schemaVersion())
        assertEquals(listOf("RELAY_OBSERVED", "NO_ACK", "NOT_TRANSMITTED", "UNKNOWN"), store.testTx().map { it.status.name })
    }

    @Test fun mbtilesReadsRasterWithTmsFlip() {
        val d = testDriver()
        d.execute("CREATE TABLE metadata(name TEXT, value TEXT)")
        d.execute("CREATE TABLE tiles(zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
        d.execute("INSERT INTO metadata VALUES('name','Test area'),('format','png'),('minzoom','10'),('maxzoom','14'),('attribution','© test')")
        // XYZ z=2 x=1 y=0  ->  TMS row = 2^2 - 1 - 0 = 3
        d.execute("INSERT INTO tiles VALUES(2, 1, 3, X'89504E470D0A1A0A')")
        val m = MbTiles(d)
        assertTrue(m.isRaster); assertEquals("© test", m.attribution); assertEquals(14, m.maxZoom)
        assertEquals(listOf(0x89.toByte(), 0x50, 0x4E, 0x47), m.tile(2, 1, 0)!!.take(4))
        assertNull(m.tile(2, 1, 3))
    }

    @Test fun mbtilesRefusesVector() {
        val d = testDriver()
        d.execute("CREATE TABLE metadata(name TEXT, value TEXT)")
        d.execute("CREATE TABLE tiles(zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
        d.execute("INSERT INTO metadata VALUES('format','pbf')")
        val m = MbTiles(d)
        assertFalse(m.isRaster); assertNull(m.tile(0, 0, 0)); assertTrue(m.describe().contains("not supported"))
    }
}
