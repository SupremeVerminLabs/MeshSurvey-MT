package meshsurvey.core

import meshsurvey.core.analysis.EquipmentCompare
import meshsurvey.core.analysis.Health
import meshsurvey.core.analysis.MeasurementGroup
import meshsurvey.core.analysis.PairClass
import meshsurvey.core.analysis.PairCompare
import meshsurvey.core.analysis.ReceiverData
import meshsurvey.core.analysis.Severity
import meshsurvey.core.analysis.Stats
import meshsurvey.core.model.ClockEvent
import meshsurvey.core.model.ClockEventKind
import meshsurvey.core.model.LoggingWindow
import meshsurvey.core.model.Observation
import meshsurvey.core.model.PacketType
import meshsurvey.core.model.RxStatus
import meshsurvey.core.model.SessionStatus
import meshsurvey.core.model.Source
import meshsurvey.core.model.SurveySession
import meshsurvey.core.model.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalysisTest {
    private val t0 = 1_800_000_000_000L
    private fun ob(from: Long, id: Long, t: Long, snr: Double? = 5.0, rssi: Int? = -100, lat: Double? = 45.0, lon: Double? = -75.0,
                   preset: String? = "LONG_FAST", fix: String? = "GPS", acc: Double? = 5.0, status: RxStatus = RxStatus.DECODED) =
        Observation(sessionId = "s", receiverNode = 1, rxMs = t, nodeRxTime = null, lat = lat, lon = lon, accuracyM = acc, fixAgeMs = 500,
            rssi = rssi, snr = snr, packetId = id, fromNode = from, toNode = -1, channel = 0, portnum = 67, type = PacketType.TELEMETRY,
            status = status, hopStart = 3, hopLimit = 3, hopsAway = 0, relayByte = null, payloadLen = 20, preset = preset, region = "US",
            frequencyMhz = null, summary = null, source = Source.LOCAL_RF, fixSource = fix, positionAgeMs = 500)
    private fun session(id: String, start: Long, end: Long?, preset: String = "LONG_FAST", status: SessionStatus = SessionStatus.ENDED) =
        SurveySession(id, start, end, status, 1, "SERIAL", "2.7.26", preset, "US", Source.LOCAL_RF)
    private fun rx(label: String, node: Long, obs: List<Observation>, track: List<TrackPoint> = emptyList(),
                   windows: List<LoggingWindow> = listOf(LoggingWindow(t0, t0 + 3_600_000, null)), preset: String = "LONG_FAST") =
        ReceiverData(label, node, listOf(session(label, t0, t0 + 3_600_000, preset)), obs, track, windows)

    // ------------------------------------------------------------ pair comparison
    @Test fun pairClassificationAndDenominators() {
        val mobile = rx("Car", 0xA, listOf(
            ob(7, 1, t0 + 1000), ob(7, 2, t0 + 2000), ob(8, 9, t0 + 9000, lat = 45.01),
            ob(0xB, 5, t0 + 3000),                                  // sent by the reference radio: excluded
            ob(7, 6, t0 + 4000, status = RxStatus.QUEUED_LATE),     // late: excluded
        ), track = listOf(TrackPoint(sessionId = "Car", ms = t0 + 5000, lat = 45.005, lon = -75.0, accuracyM = 4.0, radioReady = true, source = Source.LOCAL_RF)))
        val reference = rx("Home", 0xB, listOf(
            ob(7, 1, t0 + 1500), ob(7, 2, t0 + 2400), ob(7, 3, t0 + 5003), ob(7, 4, t0 + 30_000),
            ob(9, 77, t0 + 7_200_000),                              // mobile not logging then
        ))
        val r = PairCompare.compare(mobile, reference)
        val s = r.stats
        assertEquals(2, s.both); assertEquals(2, s.referenceOnly); assertEquals(1, s.mobileOnly)
        assertEquals(1, s.excludedUnavailable); assertEquals(1, s.excludedOwnPackets); assertEquals(1, s.excludedLateQueued)
        assertEquals(5, s.comparable)
        assertEquals(0.5, s.mobileShareOfReference)                 // 2 of (2 + 2): denominator is reference's comparable packets
        assertTrue(s.shareText(s.mobileShareOfReference, "x", 4).contains("not a packet delivery rate"))
        val p3 = r.packets.first { it.packetId == 3L }
        assertEquals(PairClass.REFERENCE_ONLY, p3.cls); assertEquals(45.005, p3.mobilePosition!!.lat)     // nearest track point within 10 s
        val p4 = r.packets.first { it.packetId == 4L }
        assertNull(p4.mobilePosition); assertTrue(p4.note.contains("not placed on the map"))              // never invented
        assertEquals(-450L, s.clockSkewMs)                                                                // median of -500, -400
    }

    @Test fun incompatibleSettingsAndAmbiguousIdsAreSeparated() {
        val mobile = rx("Car", 0xA, listOf(ob(7, 1, t0 + 1000, preset = "SHORT_TURBO"), ob(7, 2, t0 + 2000), ob(7, 3, t0 + 3000), ob(7, 3, t0 + 900_000)), preset = "SHORT_TURBO")
        val reference = rx("Home", 0xB, listOf(ob(7, 1, t0 + 1000), ob(7, 2, t0 + 2_000_000), ob(7, 8, t0 + 50_000)))
        val r = PairCompare.compare(mobile, reference)
        assertEquals(PairClass.INCOMPATIBLE_CONFIG, r.packets.first { it.packetId == 1L }.cls)
        assertEquals(PairClass.AMBIGUOUS, r.packets.first { it.packetId == 2L }.cls)     // same id 33 min apart
        assertEquals(PairClass.AMBIGUOUS, r.packets.first { it.packetId == 3L }.cls)     // id twice in one log
        assertEquals(PairClass.INCOMPATIBLE_CONFIG, r.packets.first { it.packetId == 8L }.cls)   // mobile session was on another preset
        assertEquals(0, r.stats.comparable)
    }

    // ------------------------------------------------------------ equipment comparison
    private fun cluster(from: Long, base: Int, snr: Double, n: Int, lat: Double, rssi: Int = -100) =
        (0 until n).map { ob(from, base + it.toLong(), t0 + (base + it) * 60_000L, snr = snr + (it % 3) - 1, rssi = rssi + (it % 3) - 1, lat = lat) }

    @Test fun equipmentComparisonUsesSamePlaceSameSenderStrata() {
        val a = (0 until 6).flatMap { i -> cluster(100L + i, i * 100, 2.0, 5, 45.0 + i * 0.01) } + cluster(999, 9000, 15.0, 40, 46.0)   // A also heard a strong nearby node B never heard
        val b = (0 until 6).flatMap { i -> cluster(100L + i, 5000 + i * 100, 6.0, 5, 45.0 + i * 0.01) }
        val c = EquipmentCompare.compare(MeasurementGroup("New antenna", a, emptyList()), MeasurementGroup("Old antenna", b, emptyList()))
        assertEquals(6, c.strata.size); assertTrue(c.sufficient)
        assertEquals(-4.0, c.matchedSnrDiffDb)                        // like-for-like: A is 4 dB weaker
        assertTrue(c.naiveSnrDiffDb!! > 0, "naive=${c.naiveSnrDiffDb}")                              // the naive overall median would wrongly say A is stronger
        val desc = EquipmentCompare.describe(c.matchedSnrDiffDb, "New antenna", "Old antenna"); assertTrue(desc.contains("4.0 dB weaker"), desc)
        assertNotNull(c.matchedSnrCi95)
    }

    @Test fun equipmentComparisonRefusesWhenGroupsDidNotOverlap() {
        val a = cluster(1, 0, 2.0, 40, 45.0); val b = cluster(2, 1000, 2.0, 40, 47.0)
        val c = EquipmentCompare.compare(MeasurementGroup("A", a, emptyList()), MeasurementGroup("B", b, listOf(session("x", t0, t0 + 1, preset = "MEDIUM_FAST"))))
        assertTrue(!c.sufficient)
        assertTrue(c.limitations.any { it.contains("no fair comparison") })
        assertTrue(c.limitations.any { it.contains("Different modem presets") })
    }

    // ------------------------------------------------------------ health
    @Test fun soloInsufficientDataIsSaidPlainly() {
        val f = Health.solo(listOf(session("s", t0, t0 + 60_000)), (0 until 10).map { ob(7, it.toLong(), t0 + it) })
        val d = f.single { it.ruleId == "SOLO-DATA" }
        assertTrue(d.insufficient); assertTrue(d.statement.contains("No conclusions"))
        assertTrue(f.none { it.severity == Severity.WARNING })
    }

    @Test fun soloReportsInterruptionsConfigMixAndVariability() {
        val sessions = listOf(session("a", t0, t0 + 1_000_000, status = SessionStatus.INTERRUPTED), session("b", t0 + 2_000_000, t0 + 3_000_000, preset = "SHORT_TURBO"))
        val steady = cluster(1, 0, 5.0, 30, 45.0).map { it.copy(fixSource = "RADIO", accuracyM = null) }
        val noisy = (0 until 30).map { ob(2, 500L + it, t0 + it * 1000, snr = if (it % 2 == 0) -12.0 else 8.0, fix = "RADIO", acc = null) }
        val f = Health.solo(sessions, steady + noisy, listOf(ClockEvent(wallMs = t0, kind = ClockEventKind.TICK_GAP, magnitudeMs = 120_000, sessionId = "a")))
        assertTrue(f.any { it.ruleId == "SOLO-INTERRUPT" && it.evidence.any { e -> e.contains("ended unexpectedly") } })
        assertTrue(f.any { it.ruleId == "SOLO-CONFIG" })
        val v = f.single { it.ruleId == "SOLO-VARIABILITY" }
        assertTrue(v.evidence.single().startsWith("node 2")); assertTrue(v.alternatives.isNotEmpty()); assertTrue(v.caveats.isNotEmpty())
    }

    private fun pairedSideBySide(mobileShare: Int, snrOffset: Double): meshsurvey.core.analysis.PairResult {
        val refObs = (0 until 200).map { ob(7 + (it % 5).toLong(), it.toLong(), t0 + it * 10_000L, snr = 5.0) }
        val mobObs = refObs.filter { it.packetId % 100 < mobileShare }.map { it.copy(rxMs = it.rxMs + 200, snr = it.snr!! + snrOffset, lat = 45.0001) }
        return PairCompare.compare(rx("Car", 0xA, mobObs), rx("Home", 0xB, refObs))
    }

    @Test fun pairedWeakerReceiverGetsAQualifiedWarning() {
        val f = Health.paired(pairedSideBySide(mobileShare = 50, snrOffset = -6.0))
        val w = f.single { it.ruleId == "PAIR-WEAKER" }
        assertTrue(w.title.startsWith("Car")); assertTrue(w.statement.contains("Consider inspecting"))
        assertTrue(w.caveats.any { it.contains("cannot by themselves establish") })
        assertTrue(w.evidence.any { it.contains("not a packet delivery rate") })
    }

    @Test fun contradictoryEvidenceIsNotBlamedOnTheAntenna() {
        val f = Health.paired(pairedSideBySide(mobileShare = 50, snrOffset = +2.0))
        assertTrue(f.none { it.ruleId == "PAIR-WEAKER" })
        assertTrue(f.single { it.ruleId == "PAIR-CONTRADICTORY" }.statement.contains("does not point at the antenna"))
    }

    @Test fun pairedReceiversApartAreNotJudgedOnEquipment() {
        val refObs = (0 until 100).map { ob(7, it.toLong(), t0 + it * 10_000L) }
        val mobObs = refObs.take(40).map { it.copy(rxMs = it.rxMs + 100, lat = 45.2) }
        val f = Health.paired(PairCompare.compare(rx("Car", 0xA, mobObs), rx("Home", 0xB, refObs)))
        assertEquals(listOf("PAIR-LOCATION"), f.map { it.ruleId })
    }

    @Test fun historicalDeclineAndNoBaseline() {
        val before = (0 until 6).flatMap { i -> cluster(100L + i, i * 100, 6.0, 6, 45.0 + i * 0.01) }
        val now = (0 until 6).flatMap { i -> cluster(100L + i, 5000 + i * 100, 1.0, 6, 45.0 + i * 0.01) }
        val f = Health.historical(MeasurementGroup("This month", now, emptyList()), MeasurementGroup("Last month", before, emptyList()))
        val d = f.single()
        assertEquals("HIST-DECLINE", d.ruleId); assertEquals(Severity.WARNING, d.severity); assertTrue(d.inspection.isNotEmpty())
        val none = Health.historical(MeasurementGroup("Now", now, emptyList()), MeasurementGroup("Elsewhere", cluster(1, 0, 6.0, 40, 40.0), emptyList()))
        assertTrue(none.single().insufficient)
    }

    @Test fun statsAreStandardQuantilesAndDeterministic() {
        val s = Stats.summary(listOf(1.0, 2.0, 3.0, 4.0, 5.0))!!
        assertEquals(3.0, s.median); assertEquals(2.0, s.p25); assertEquals(4.0, s.p75); assertEquals(2.0, s.iqr)
        val v = (1..20).map { it.toDouble() }
        assertEquals(Stats.medianCi95(v), Stats.medianCi95(v))
    }
}
