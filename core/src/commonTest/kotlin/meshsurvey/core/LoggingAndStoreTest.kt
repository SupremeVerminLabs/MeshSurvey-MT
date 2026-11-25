package meshsurvey.core

import meshsurvey.core.engine.SurveyEngine
import meshsurvey.core.export.Bundle
import meshsurvey.core.export.Csv
import meshsurvey.core.export.GeoJson
import meshsurvey.core.log.LoggerConfig
import meshsurvey.core.model.Fix
import meshsurvey.core.model.FixSource
import meshsurvey.core.model.PacketType
import meshsurvey.core.model.RxStatus
import meshsurvey.core.model.SessionStatus
import meshsurvey.core.model.Source
import meshsurvey.core.model.TestDevice
import meshsurvey.core.proto.BROADCAST
import meshsurvey.core.proto.Port
import meshsurvey.core.proto.Wire
import meshsurvey.core.proto.f
import meshsurvey.core.radio.LinkKind
import meshsurvey.core.radio.LinkState
import meshsurvey.core.radio.RadioLink
import meshsurvey.core.radio.RadioState
import meshsurvey.core.sim.FromRadioEncoder
import meshsurvey.core.store.ObsFilter
import meshsurvey.core.store.Schema
import meshsurvey.core.store.SqlStore
import meshsurvey.core.testsupport.testDriver
import meshsurvey.core.util.ManualClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared harness: engine + fake serial link driven by hand. */
class Harness(store: SqlStore = SqlStore(testDriver()), val clock: ManualClock = ManualClock(), myNode: Long = 0x0a0a0a0a) {
    class TestLink : RadioLink {
        override val kind = LinkKind.SERIAL
        override val description = "test"
        var l: RadioLink.Listener? = null
        val sent = ArrayList<ByteArray>()
        override fun start(listener: RadioLink.Listener) { l = listener; listener.onLinkState(LinkState.CONNECTED) }
        override fun send(toRadio: ByteArray): Boolean { sent += toRadio; return true }
        override fun close() { l?.onLinkState(LinkState.DISCONNECTED, "closed") }
    }
    val link = TestLink()
    val engine = SurveyEngine(store, clock)
    val store get() = engine.store

    init {
        engine.connect(link)
        val nonce = Wire.parse(link.sent.first()).f(3)!!.u32
        rx(FromRadioEncoder.myInfo(myNode))
        rx(FromRadioEncoder.loraConfig(0, 1, 20, 3, true))
        rx(FromRadioEncoder.configComplete(nonce))
    }
    fun rx(b: ByteArray) = link.l!!.onFromRadio(b)
    fun fix(lat: Double = 45.0, lon: Double = -75.0, acc: Double = 5.0) =
        engine.onFix(Fix(lat, lon, acc, null, clock.wallMs(), clock.monotonicMs(), FixSource.GPS))
    fun liveTime() = clock.wallMs() / 1000
}

class LoggingAndStoreTest {

    @Test fun passiveLoggingAttachesFreshFixOnly() {
        val h = Harness()
        assertEquals(RadioState.READY, h.engine.radio.state)
        h.fix()
        h.rx(FromRadioEncoder.packet(7, BROADCAST, 100, Port.TELEMETRY, byteArrayOf(), snr = 5f, rssi = -95, rxTime = h.liveTime(), hopLimit = 3, hopStart = 3, relay = 7))
        h.clock.advance(15_000) // fix now 15 s old > 10 s limit
        h.rx(FromRadioEncoder.packet(7, BROADCAST, 101, Port.POSITION, byteArrayOf(), snr = 2f, rssi = -100, rxTime = h.liveTime()))
        val obs = h.store.observations()
        assertEquals(2, obs.size)
        assertNotNull(obs[0].lat); assertEquals(0, obs[0].hopsAway); assertEquals(7, obs[0].relayByte)
        assertNull(obs[1].lat); assertEquals(15_000L, obs[1].fixAgeMs)
        assertEquals(Source.LOCAL_RF, obs[0].source)
        assertEquals("LONG_FAST", obs[0].preset)
        // US, LONG_FAST, explicit slot 20 (loraConfig channelNum = 20): calculated, not reported
        assertEquals(906.875, obs[0].frequencyMhz!!, 1e-6)
        assertEquals("CALCULATED", obs[0].frequencySource)
    }

    @Test fun ownPacketsAndMqttAreNotRfObservations() {
        val h = Harness(myNode = 0x0a0a0a0a)
        h.fix()
        h.rx(FromRadioEncoder.packet(0x0a0a0a0a, BROADCAST, 1, Port.TEXT, null, snr = 5f, rssi = -50))
        h.rx(FromRadioEncoder.packet(8, BROADCAST, 2, Port.TEXT, null, snr = 5f, rssi = -50, viaMqtt = true))
        assertEquals(0, h.store.countObservations())
        assertEquals(1, h.engine.stats.value.skippedOwn); assertEquals(1, h.engine.stats.value.skippedMqtt)
    }

    @Test fun samePacketIdIsNeverMerged() {
        val h = Harness(); h.fix()
        repeat(2) { h.rx(FromRadioEncoder.packet(9, BROADCAST, 555, Port.TEXT, null, snr = 1f, rssi = -110, rxTime = h.liveTime())) }
        assertEquals(2, h.store.countObservations())
    }

    @Test fun queuedLatePacketsGetNoPosition() {
        val h = Harness(); h.fix()
        h.rx(FromRadioEncoder.packet(9, BROADCAST, 1, Port.TEXT, null, snr = 1f, rssi = -110, rxTime = h.liveTime() - 600))
        val o = h.store.observations().single()
        assertEquals(RxStatus.QUEUED_LATE, o.status); assertNull(o.lat)
    }

    @Test fun unsyncedNodeClockIsIgnoredNotTrusted() {
        val h = Harness(); h.fix()
        // node clock 3 days behind: can't be used to judge lateness -> treated as live (not during config download)
        h.rx(FromRadioEncoder.packet(9, BROADCAST, 1, Port.TEXT, null, snr = 1f, rssi = -110, rxTime = h.liveTime() - 3 * 86_400))
        assertEquals(RxStatus.DECODED, h.store.observations().single().status)
    }

    @Test fun encryptedReceptionsKeepHeaderMetadata() {
        val h = Harness(); h.fix()
        h.rx(FromRadioEncoder.packet(9, BROADCAST, 1, null, null, snr = -3f, rssi = -112, rxTime = h.liveTime(), encrypted = ByteArray(40)))
        val o = h.store.observations().single()
        assertEquals(RxStatus.ENCRYPTED, o.status); assertEquals(PacketType.ENCRYPTED, o.type); assertEquals(40, o.payloadLen)
    }

    @Test fun contentIsNotStoredByDefaultAndTextNever() {
        val h = Harness(); h.fix()
        val tel = FromRadioEncoder.deviceTelemetry(h.liveTime(), 80, 4.0f, 10f, 1f)
        h.rx(FromRadioEncoder.packet(9, BROADCAST, 1, Port.TELEMETRY, tel, snr = 1f, rssi = -100, rxTime = h.liveTime()))
        assertNull(h.store.observations().single().summary)
        h.engine.setLoggerConfig(LoggerConfig(storeSummaries = true))
        h.rx(FromRadioEncoder.packet(9, BROADCAST, 2, Port.TELEMETRY, tel, snr = 1f, rssi = -100, rxTime = h.liveTime()))
        h.rx(FromRadioEncoder.packet(9, BROADCAST, 3, Port.TEXT, "secret".encodeToByteArray(), snr = 1f, rssi = -100, rxTime = h.liveTime()))
        val o = h.store.observations()
        assertTrue(o[1].summary!!.startsWith("device bat=80%"))
        assertNull(o[2].summary)
        assertFalse(Csv.observations(o).contains("secret"))
    }

    @Test fun trackOnlyWhileMovingAndFlagsRadioReady() {
        val h = Harness()
        h.fix(45.0, -75.0); h.clock.advance(6_000)
        h.fix(45.0, -75.0) // not moved: skipped
        h.clock.advance(6_000); h.fix(45.001, -75.0)
        val t = h.store.track()
        assertEquals(2, t.size); assertTrue(t.all { it.radioReady })
    }

    @Test fun sessionLifecycleAndCrashRecovery() {
        val driverStore = SqlStore(testDriver())
        val h = Harness(driverStore)
        assertEquals(SessionStatus.ACTIVE, driverStore.sessions().single().status)
        // simulate a crash: a fresh engine on the same DB recovers the open session
        val e2 = SurveyEngine(driverStore, h.clock)
        assertEquals(1, e2.recoveredOnStart)
        assertEquals(SessionStatus.INTERRUPTED, driverStore.sessions().single().status)
        assertEquals(Schema.version, driverStore.schemaVersion())
    }

    @Test fun disconnectEndsSessionAndWindow() {
        val h = Harness()
        h.clock.advance(60_000); h.engine.tick()
        h.engine.disconnect()
        assertEquals(SessionStatus.ENDED, h.store.sessions().single().status)
        val w = h.store.windows().single()
        assertTrue(w.endMs - w.startMs >= 60_000)
    }

    @Test fun filtersWork() {
        val h = Harness(); h.fix(acc = 50.0)
        h.rx(FromRadioEncoder.packet(1, BROADCAST, 1, Port.TEXT, null, snr = -10f, rssi = -120, rxTime = h.liveTime()))
        h.fix(acc = 4.0)
        h.rx(FromRadioEncoder.packet(2, BROADCAST, 2, Port.TELEMETRY, null, snr = 6f, rssi = -90, rxTime = h.liveTime()))
        assertEquals(1, h.store.countObservations(ObsFilter(minSnr = 0.0)))
        assertEquals(1, h.store.countObservations(ObsFilter(maxAccuracyM = 10.0)))
        assertEquals(1, h.store.countObservations(ObsFilter(fromNodes = setOf(2L))))
        assertEquals(1, h.store.countObservations(ObsFilter(types = setOf(PacketType.TEXT))))
        assertEquals(2, h.store.countObservations(ObsFilter(preset = "LONG_FAST")))
        assertEquals(0, h.store.countObservations(ObsFilter(preset = "SHORT_TURBO")))
    }

    @Test fun exportsAndImportDedupe() {
        val a = Harness()
        a.store.upsertTestDevice(TestDevice(5, "mine", 0))
        a.fix()
        a.rx(FromRadioEncoder.packet(5, BROADCAST, 11, Port.TELEMETRY, null, snr = 3f, rssi = -100, rxTime = a.liveTime()))
        val bundle = Bundle.export(a.store, a.engine.deviceId, "test", a.clock.wallMs())
        val geo = Json.parseToJsonElement(GeoJson.export(a.store.observations(), a.store.track(), emptyList())).jsonObject
        assertEquals("FeatureCollection", geo["type"]!!.jsonPrimitive.content)
        assertEquals(1, geo["features"]!!.jsonArray.count { it.jsonObject["geometry"]!!.jsonObject["type"]!!.jsonPrimitive.content == "Point" })

        val b = Harness()
        val r1 = Bundle.import(bundle, b.store, b.engine.deviceId, b.clock.wallMs())
        assertNull(r1.error); assertEquals(1, r1.observations)
        val imported = b.store.observations().single()
        assertEquals(a.engine.deviceId, imported.originDevice)
        assertEquals(Source.LOCAL_RF, imported.source) // source preserved
        val r2 = Bundle.import(bundle, b.store, b.engine.deviceId, b.clock.wallMs())
        assertTrue(r2.duplicateFile)
        // same rows in a different file (re-export with whitespace) are still not duplicated
        val r3 = Bundle.import(bundle + " ", b.store, b.engine.deviceId, b.clock.wallMs())
        assertEquals(0, r3.observations); assertEquals(1, b.store.countObservations())
        // own export refused
        assertTrue(Bundle.import(bundle, a.store, a.engine.deviceId, a.clock.wallMs()).ownExport)
    }

    @Test fun csvEscaping() {
        val h = Harness(); h.fix()
        h.engine.setLoggerConfig(LoggerConfig(storeSummaries = true))
        h.rx(FromRadioEncoder.packet(9, BROADCAST, 1, Port.NODEINFO, FromRadioEncoder.user("!9", "Comma, \"Quote\"", "C", 0).toByteArray(),
            snr = 1f, rssi = -100, rxTime = h.liveTime()))
        val csv = Csv.observations(h.store.observations())
        assertTrue(csv.contains("\"nodeinfo C Comma, \"\"Quote\"\" CLIENT\""))
    }
}

class ReconnectTest {
    @Test fun reconnectsWithBackoffUntilUserDisconnects() {
        val clock = ManualClock()
        val engine = SurveyEngine(SqlStore(testDriver()), clock)
        val links = ArrayList<Harness.TestLink>()
        engine.connect { Harness.TestLink().also { links += it } }
        assertEquals(1, links.size)
        links.last().l!!.onLinkState(LinkState.DISCONNECTED, "cable pulled")
        assertEquals(5, engine.reconnectInS)
        repeat(4) { clock.advance(1_000); engine.tick() }
        assertEquals(1, links.size)
        clock.advance(1_000); engine.tick()
        assertEquals(2, links.size)
        links.last().l!!.onLinkState(LinkState.FAILED, "again")
        assertEquals(10, engine.reconnectInS) // doubled
        engine.disconnect()
        assertNull(engine.reconnectInS)
        repeat(20) { clock.advance(1_000); engine.tick() }
        assertEquals(2, links.size)
    }
}
