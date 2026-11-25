package meshsurvey.core

import meshsurvey.core.engine.SurveyEngine
import meshsurvey.core.model.ClockEventKind
import meshsurvey.core.model.DeliveryMode
import meshsurvey.core.model.Fix
import meshsurvey.core.model.FixSource
import meshsurvey.core.model.PresetChangeStatus
import meshsurvey.core.model.SessionStatus
import meshsurvey.core.model.TxStatus
import meshsurvey.core.preset.MemoryBackupStore
import meshsurvey.core.preset.PresetManager
import meshsurvey.core.proto.BROADCAST
import meshsurvey.core.proto.MeshProto
import meshsurvey.core.proto.Port
import meshsurvey.core.proto.ProtoWriter
import meshsurvey.core.proto.Wire
import meshsurvey.core.proto.f
import meshsurvey.core.radio.RadioState
import meshsurvey.core.sim.FromRadioEncoder
import meshsurvey.core.store.SqlStore
import meshsurvey.core.testsupport.testDriver
import meshsurvey.core.util.ManualClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Engine connected to a hand-driven link with an arbitrary LoRa config, firmware and channel name. */
class CustomRadio(
    lora: ByteArray, firmware: String? = "2.7.15.abc", channelName: String? = "",
    val backups: MemoryBackupStore = MemoryBackupStore(), val clock: ManualClock = ManualClock(),
    store: SqlStore = SqlStore(testDriver()), val me: Long = 0x0a0a0a0a,
) {
    val link = Harness.TestLink()
    val engine = SurveyEngine(store, clock, configBackups = backups)
    init { engine.connect(link); config(lora, firmware, channelName) }
    fun rx(b: ByteArray) = link.l!!.onFromRadio(b)
    fun lastNonce() = link.sent.mapNotNull { Wire.parse(it).f(3)?.u32 }.last()
    fun config(lora: ByteArray, firmware: String? = "2.7.15.abc", channelName: String? = "") {
        val n = lastNonce()
        rx(FromRadioEncoder.myInfo(me))
        firmware?.let { rx(FromRadioEncoder.metadata(it, 43)) }
        rx(FromRadioEncoder.loraConfigRaw(lora))
        channelName?.let { rx(FromRadioEncoder.channel(0, it, 1)) }
        rx(FromRadioEncoder.configComplete(n))
    }
    fun adminSent() = link.sent.count { b -> Wire.parse(b).f(1)?.bytes?.let { Wire.parse(it).f(4)?.bytes?.let { d -> Wire.parse(d).f(1)?.int == Port.ADMIN } } == true }
}

fun lora(use: Boolean = true, preset: Int = 0, region: Int = 1, slot: Int = 0, override: Float = 0f, tx: Boolean = true, offset: Float = 0f) =
    ProtoWriter().bool(1, use).varint(2, preset.toLong()).float(6, offset).varint(7, region.toLong()).varint(8, 3).bool(9, tx)
        .int32(10, 20).varint(11, slot.toLong()).float(14, override).varint(103, 0xabcd).toByteArray()

class PresetSafetyTest {
    private fun blockers(r: CustomRadio, target: Int = 8) = r.engine.presets.plan(target).blockers.joinToString(" | ")

    @Test fun acceptsPlainPresetConfig() {
        val r = CustomRadio(lora())
        assertEquals(RadioState.READY, r.engine.radio.state)
        assertTrue(r.engine.presets.plan(8).canApply, blockers(r))
    }

    @Test fun refusesCustomOverrideAndAmbiguousConfigs() {
        assertTrue(blockers(CustomRadio(lora(use = false))).contains("custom LoRa configuration"))
        assertTrue(blockers(CustomRadio(lora(override = 915.0f))).contains("frequency override"))
        assertTrue(blockers(CustomRadio(lora(tx = false))).contains("Transmit is disabled"))
        assertTrue(blockers(CustomRadio(lora(region = 0))).contains("UNSET"))
        assertTrue(blockers(CustomRadio(lora(region = 29))).contains("not in this app's verified table"))       // EU_866 (2.8)
        assertTrue(blockers(CustomRadio(lora(region = 3)), 8).contains("does not fit region EU_868"))            // 500 kHz in 250 kHz band
        assertTrue(blockers(CustomRadio(lora(slot = 80))).contains("Frequency slot 80 does not exist"))          // 104 slots -> 52 on ShortTurbo
        assertTrue(CustomRadio(lora(slot = 40)).engine.presets.plan(8).canApply)                                  // slot 40 exists in both
        assertTrue(blockers(CustomRadio(lora(), firmware = "2.8.1.x")).contains("2.8"))
        assertTrue(blockers(CustomRadio(lora(), firmware = null)).contains("Firmware version unknown"))
        assertTrue(blockers(CustomRadio(lora(), channelName = null)).contains("cannot be determined"))           // default slot, name unknown
        assertTrue(CustomRadio(lora(offset = 0.01f)).engine.presets.plan(8).warnings.any { it.contains("offset") })
    }

    @Test fun backupFailureMeansNothingIsSent() {
        val r = CustomRadio(lora())
        r.backups.failNextSave = true
        val err = r.engine.presets.apply(r.engine.presets.plan(8), autoRestore = false)
        assertTrue(err!!.contains("backup"))
        assertEquals(0, r.adminSent())
        assertNull(r.engine.presetChange.value)
        assertTrue(r.engine.store.presetChanges().isEmpty())
    }

    @Test fun backupWrittenBeforeSendAndResolvedOnRestore() {
        val r = CustomRadio(lora())
        val original = r.engine.radio.info.loraRaw!!
        assertNull(r.engine.presets.apply(r.engine.presets.plan(8), autoRestore = false))
        assertEquals(1, r.adminSent())
        val rec = r.backups.loadAll().single()
        assertFalse(rec.resolved); assertTrue(rec.change.originalRaw.contentEquals(original))
        assertTrue(PresetManager.manualRestoreText(rec.change).contains("LONG_FAST"))
        // radio reboots onto the temp config, then the user restores, radio comes back on the original
        r.rx(FromRadioEncoder.rebooted()); r.config(rec.change.tempRaw)
        assertEquals(PresetChangeStatus.ACTIVE, r.engine.presetChange.value!!.status)
        assertNull(r.engine.presets.restore())
        r.rx(FromRadioEncoder.rebooted()); r.config(original)
        assertNull(r.engine.presetChange.value)
        assertTrue(r.backups.loadAll().single().resolved)
    }

    @Test fun firmwareChangingAnotherFieldIsFlagged() {
        val r = CustomRadio(lora())
        r.engine.presets.apply(r.engine.presets.plan(8), autoRestore = false)
        val temp = r.engine.store.presetChanges().last().tempRaw
        val altered = Wire.patchVarints(temp, mapOf(10 to 22L)) // radio clamped tx_power
        r.rx(FromRadioEncoder.rebooted()); r.config(altered)
        val c = r.engine.store.presetChanges().last()
        assertEquals(PresetChangeStatus.APPLY_UNCONFIRMED, c.status)
        assertTrue(c.note!!.contains("tx_power"), c.note)
        r.engine.ab.setPeer(0x0b0b0b0b, confirmed = true)
        assertTrue(r.engine.ab.sendSingle()!!.contains("unconfirmed")) // tests blocked
    }

    @Test fun backupSurvivesDatabaseLoss() {
        val backups = MemoryBackupStore()
        val r = CustomRadio(lora(), backups = backups)
        r.engine.presets.apply(r.engine.presets.plan(6), autoRestore = true)
        // database lost (deleted/corrupted, or reinstalled app data) — backup store survives
        val fresh = SurveyEngine(SqlStore(testDriver()), r.clock, configBackups = backups)
        val c = fresh.presetChange.value!!
        assertEquals("SHORT_FAST", c.targetPreset); assertEquals(PresetChangeStatus.APPLY_UNCONFIRMED, c.status)
        assertTrue(c.note!!.contains("backup"))
    }

    @Test fun openChangeForADisconnectedRadioIsLeftAlone() {
        val r = CustomRadio(lora())
        r.engine.presets.apply(r.engine.presets.plan(8), autoRestore = false)
        r.engine.disconnect()
        val other = CustomRadio(lora(), store = r.engine.store, clock = r.clock, me = 0x0c0c0c0c, backups = r.backups)
        assertNotNull(other.engine.presetChange.value)          // still surfaced
        assertNotNull(other.engine.presets.restore())           // but cannot be "restored" on a different radio
        assertEquals(0, other.adminSent())
    }
}

class AbHardeningTest {
    private val peer = 0x0b0b0b0bL
    private fun sentIds(h: Harness) = h.link.sent.mapNotNull { Wire.parse(it).f(1)?.bytes }.map { MeshProto.decodePacket(it).id }
    private fun ack(h: Harness, from: Long, id: Long, err: Int = 0) =
        h.rx(FromRadioEncoder.packet(from, 0x0a0a0a0a, 777, Port.ROUTING, FromRadioEncoder.routing(err), requestId = id, snr = 1f, rssi = -110))

    @Test fun lateAckAfterTimeoutStillCounts() {
        val h = Harness(); h.engine.ab.setPeer(peer, confirmed = true); h.engine.ab.sendSingle()
        val id = sentIds(h).last()
        h.clock.advance(95_000); h.engine.tick()
        assertEquals(TxStatus.UNKNOWN, h.store.testTx().last().status)
        ack(h, peer, id)
        val t = h.store.testTx().last()
        assertEquals(TxStatus.DELIVERED, t.status); assertTrue(t.routingError!!.startsWith("LATE_ACK_"))
        assertEquals(1, h.engine.abState.value.lateAcks)
    }

    @Test fun interruptedTestsBecomeUnknownAndLateAckAfterRestartUpgrades() {
        val store = SqlStore(testDriver())
        val h = Harness(store)
        h.engine.ab.setPeer(peer, confirmed = true); h.engine.ab.sendSingle()
        val id = sentIds(h).last()
        // app killed while the test awaited its ACK; restart on the same database
        val h2 = Harness(store)
        val t = store.testTx().last()
        assertEquals(TxStatus.UNKNOWN, t.status); assertEquals("INTERRUPTED", t.routingError)
        ack(h2, peer, id) // radio delivers the queued routing report after reconnect
        assertEquals(TxStatus.DELIVERED, store.testTx().last().status)
    }

    @Test fun queueRejectionIsNotTransmittedAndFullQueueBlocks() {
        val h = Harness(); h.engine.ab.setPeer(peer, confirmed = true); h.engine.ab.sendSingle()
        val id = sentIds(h).last()
        h.rx(FromRadioEncoder.queueStatus(res = 1, free = 0, maxLen = 16, packetId = id))
        val t = h.store.testTx().last()
        assertEquals(TxStatus.NOT_TRANSMITTED, t.status); assertEquals("QUEUE_REJECTED_1", t.routingError)
        h.clock.advance(200_000)
        assertTrue(h.engine.ab.sendSingle()!!.contains("queue is full"))
        h.rx(FromRadioEncoder.queueStatus(0, 15, 16, 0))
        assertNull(h.engine.ab.sendSingle())
    }

    @Test fun radioReportedAirtimeIsSeparateAndGuardsSessions() {
        val h = Harness(); h.engine.ab.setPeer(peer, confirmed = true)
        fun telemetry(ch: Float, tx: Float) = h.rx(FromRadioEncoder.packet(0x0a0a0a0a, BROADCAST, h.clock.wallMs() % 100000 + 5, Port.TELEMETRY,
            FromRadioEncoder.deviceTelemetry(h.liveTime(), 90, 4.0f, ch, tx)))
        assertNull(h.engine.radioMetrics.value)
        telemetry(45f, 1.5f)
        assertEquals(45.0, h.engine.radioMetrics.value!!.channelUtilPct!!, 0.01)
        assertTrue(h.engine.ab.startAuto(60, 600)!!.contains("busy"))
        telemetry(12f, 1.5f)
        assertNull(h.engine.ab.startAuto(60, 600))
        val s = h.store.testSessions().first()
        assertEquals(1.5, s.airUtilTxStart!!, 0.01); assertEquals(12.0, s.chUtilStart!!, 0.01)
        h.engine.ab.stop()
        assertEquals(12.0, h.store.testSessions().first().chUtilEnd!!, 0.01)
        assertEquals(0, h.store.countObservations()) // own telemetry is not an RF observation
    }
}

class IntegrityTest {
    @Test fun futureFixesRejectedMockFixesTagged() {
        val h = Harness()
        h.engine.onFix(Fix(45.0, -75.0, 5.0, null, h.clock.wallMs(), h.clock.monotonicMs() + 60_000, FixSource.GPS))
        assertNull(h.engine.fix.value); assertEquals(1, h.engine.stats.value.rejectedFixes)
        h.engine.onFix(Fix(45.0, -75.0, 5.0, null, h.clock.wallMs(), h.clock.monotonicMs(), FixSource.MOCK))
        h.rx(FromRadioEncoder.packet(9, BROADCAST, 1, Port.TEXT, null, snr = 1f, rssi = -100, rxTime = h.liveTime()))
        assertEquals("MOCK", h.store.observations().single().fixSource)
    }

    @Test fun suspensionAndClockJumpsAreRecorded() {
        val h = Harness()
        h.engine.tick(); h.clock.advance(1_000); h.engine.tick()
        h.clock.advance(45_000); h.engine.tick()                    // app suspended 45 s
        h.clock.wall += 120_000; h.clock.advance(1_000); h.engine.tick() // wall clock set forward 2 min
        val ev = h.store.clockEvents()
        assertEquals(listOf(ClockEventKind.TICK_GAP, ClockEventKind.CLOCK_JUMP), ev.map { it.kind })
        assertEquals(45_000L, ev[0].magnitudeMs); assertEquals(120_000L, ev[1].magnitudeMs)
        assertNotNull(ev[0].sessionId)
        assertEquals(45_000L, h.engine.timing.value.longestGapMs)
    }

    @Test fun nmeaEstimatedFixesAreNotPositions() {
        val est = meshsurvey.core.gps.Nmea.parse("\$GPGGA,123519,4807.038,N,01131.000,E,6,08,0.9,545.4,M,46.9,M,,*47".let {
            val body = it.substring(1, it.indexOf('*')); var c = 0; body.forEach { ch -> c = c xor ch.code }
            "\$" + body + "*" + c.toString(16).uppercase().padStart(2, '0') }) as meshsurvey.core.gps.Nmea.Sentence.Gga
        assertFalse(est.isMeasuredFix)
        val real = meshsurvey.core.gps.Nmea.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47") as meshsurvey.core.gps.Nmea.Sentence.Gga
        assertTrue(real.isMeasuredFix); assertEquals(12 * 3600 + 35 * 60 + 19.0, real.utcSecondOfDay)
        assertEquals(-5.0, meshsurvey.core.gps.gpsClockSkewS(100.0, 105_000L), 1e-9)
    }

    @Test fun crashRecoveryClosesSessionsAndKeepsRows() {
        val store = SqlStore(testDriver())
        val h = Harness(store); h.fix()
        repeat(5) { h.rx(FromRadioEncoder.packet(9, BROADCAST, 100L + it, Port.TEXT, null, snr = 1f, rssi = -100, rxTime = h.liveTime())) }
        val e2 = SurveyEngine(store, h.clock) // process died; new process opens the same database
        assertTrue(e2.recoveredOnStart >= 1)
        assertEquals(5, store.countObservations())
        assertEquals(SessionStatus.INTERRUPTED, store.sessions().single().status)
    }
}

@Suppress("unused") private val keepDelivery = DeliveryMode.ACKED
