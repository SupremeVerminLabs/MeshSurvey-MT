package meshsurvey.core

import meshsurvey.core.model.Observation
import meshsurvey.core.model.PacketType
import meshsurvey.core.model.RxStatus
import meshsurvey.core.model.Source
import meshsurvey.core.quality.ConfidenceClass
import meshsurvey.core.quality.ConfidenceRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfidenceTest {
    private fun obs(lat: Double? = 45.0, acc: Double? = 5.0, fix: String? = "GPS", rssi: Int? = -100, snr: Double? = 3.0,
                    preset: String? = "LONG_FAST", src: Source = Source.LOCAL_RF, status: RxStatus = RxStatus.DECODED, posAge: Long? = 1000) =
        Observation(sessionId = "s", receiverNode = 1, rxMs = 0, nodeRxTime = null, lat = lat, lon = lat?.let { -75.0 }, accuracyM = acc,
            fixAgeMs = 1000, rssi = rssi, snr = snr, packetId = 1, fromNode = 2, toNode = 3, channel = 0, portnum = 1,
            type = PacketType.TEXT, status = status, hopStart = 3, hopLimit = 3, hopsAway = 0, relayByte = null, payloadLen = 10,
            preset = preset, region = "US", frequencyMhz = null, summary = null, source = src, fixSource = fix, positionAgeMs = posAge)

    @Test fun cleanGpsObservationIsValid() {
        val c = ConfidenceRules.classify(obs())
        assertEquals(ConfidenceClass.VALID, c.cls); assertTrue(c.reasons.isEmpty()); assertTrue(c.usableForAggregates)
    }

    @Test fun eachProblemIsFlaggedWithAReasonAndTheWorstWins() {
        assertEquals(ConfidenceClass.LIMITED, ConfidenceRules.classify(obs(acc = 60.0)).cls)
        assertEquals(ConfidenceClass.INVALID_FOR_AGGREGATION, ConfidenceRules.classify(obs(acc = 500.0)).cls)
        assertEquals(ConfidenceClass.INSUFFICIENT, ConfidenceRules.classify(obs(lat = null)).cls)
        assertEquals(ConfidenceClass.POSSIBLY_MISCONFIGURED, ConfidenceRules.classify(obs(rssi = 12)).cls)
        assertEquals(ConfidenceClass.INVALID_FOR_AGGREGATION, ConfidenceRules.classify(obs(src = Source.SIMULATED)).cls)
        assertEquals(ConfidenceClass.INVALID_FOR_AGGREGATION, ConfidenceRules.classify(obs(fix = "MOCK")).cls)
        val multi = ConfidenceRules.classify(obs(acc = 60.0, preset = null, rssi = 5))
        assertEquals(ConfidenceClass.POSSIBLY_MISCONFIGURED, multi.cls); assertEquals(3, multi.reasons.size)
    }

    @Test fun stationaryRadioPositionAgeIsExplained() {
        assertEquals(ConfidenceClass.VALID, ConfidenceRules.classify(obs(fix = "RADIO", acc = null, posAge = 5 * 60_000)).cls)
        val old = ConfidenceRules.classify(obs(fix = "RADIO", acc = null, posAge = 90 * 60_000))
        assertEquals(ConfidenceClass.LIMITED, old.cls); assertTrue(old.reasons.single().contains("90 min"))
        assertEquals(ConfidenceClass.VALID, ConfidenceRules.classify(obs(fix = "MANUAL", acc = null)).cls)
    }

    @Test fun lateQueuedReceptionIsInsufficient() {
        assertEquals(ConfidenceClass.INSUFFICIENT, ConfidenceRules.classify(obs(status = RxStatus.QUEUED_LATE, lat = null)).cls)
    }
}
