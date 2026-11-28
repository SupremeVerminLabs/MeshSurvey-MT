package meshsurvey.core

import meshsurvey.core.map.Grid
import meshsurvey.core.map.PredictionCompare
import meshsurvey.core.map.PredictionCompare.CellClass
import meshsurvey.core.map.PredictionMap
import meshsurvey.core.model.Observation
import meshsurvey.core.model.PacketType
import meshsurvey.core.model.RxStatus
import meshsurvey.core.model.Source
import meshsurvey.core.model.TrackPoint
import meshsurvey.core.store.ObsFilter
import meshsurvey.core.store.SqlStore
import meshsurvey.core.testsupport.testDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PredictionTest {
    companion object {
        /** Square ring (lon/lat order, closed) of half-size [h] degrees around (lat, lon). */
        private fun square(lat: Double, lon: Double, h: Double) =
            "[[${lon - h},${lat - h}],[${lon + h},${lat - h}],[${lon + h},${lat + h}],[${lon - h},${lat + h}],[${lon - h},${lat - h}]]"

        /** Nested bands like the Site Planner's GeoJSON export: ≥ −110 (big), ≥ −100, ≥ −90 (small), with a hole in −100. */
        val SITE_PLANNER = """{"type":"FeatureCollection","properties":{"generator":"meshtastic-site-planner","name":"Hilltop"},"features":[
            {"type":"Feature","properties":{"dbm":-110,"label":"≥ -110 dBm"},"geometry":{"type":"MultiPolygon","coordinates":[[${square(45.0, -75.0, 0.10)}]]}},
            {"type":"Feature","properties":{"dbm":-100},"geometry":{"type":"MultiPolygon","coordinates":[[${square(45.0, -75.0, 0.05)},${square(45.03, -74.97, 0.005)}]]}},
            {"type":"Feature","properties":{"dbm":-90},"geometry":{"type":"Polygon","coordinates":[${square(45.0, -75.0, 0.01)}]}}]}"""

        fun obs(lat: Double, lon: Double, rssi: Int, snr: Double = 2.0, heard: String = "mt:!0000abcd") = Observation(
            sessionId = "s", receiverNode = 1, rxMs = 1_000, nodeRxTime = null, lat = lat, lon = lon, accuracyM = 5.0, fixAgeMs = 0,
            rssi = rssi, snr = snr, packetId = 1, fromNode = 0xabcd, toNode = 0xffffffffL, channel = 0, portnum = 3, type = PacketType.POSITION,
            status = RxStatus.DECODED, hopStart = 3, hopLimit = 3, hopsAway = 0, relayByte = 0xcd, payloadLen = 10, preset = "LONG_FAST",
            region = "US", frequencyMhz = null, summary = null, source = Source.LOCAL_RF, heardId = heard)
    }

    @Test fun readsSitePlannerBandsAndLooksUpThePredictedRange() {
        val (p, err) = PredictionMap.parse(SITE_PLANNER, "x.geojson")
        assertNull(err); p!!
        assertEquals("Hilltop", p.name); assertEquals(listOf(-110.0, -100.0, -90.0), p.levels); assertEquals(10.0, p.stepDb)
        assertEquals(PredictionMap.At.InBand(-90.0, null), p.at(45.0, -75.0))
        assertEquals(PredictionMap.At.InBand(-100.0, -90.0), p.at(45.03, -75.03))
        assertEquals(PredictionMap.At.InBand(-110.0, -100.0), p.at(45.03, -74.97))      // inside the hole of the −100 band
        assertEquals(PredictionMap.At.InBand(-110.0, -100.0), p.at(45.08, -75.0))
        assertEquals(PredictionMap.At.Outside, p.at(46.0, -75.0))
    }

    @Test fun refusesFilesWithoutSignalValues() {
        assertNotNull(PredictionMap.parse("\u0089PNG....", "a.png").second)
        assertNotNull(PredictionMap.parse("""{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"name":"x"},"geometry":{"type":"Point","coordinates":[1,2]}}]}""", "a.geojson").second)
    }

    @Test fun comparesMeasuredWithPredictedPerCellWithExplicitDenominators() {
        val p = PredictionMap.parse(SITE_PLANNER, "x")!!.first!!
        val g = Grid(250.0)
        val o = listOf(
            obs(45.0, -75.0, -88),          // top band (≥ −90): agrees
            obs(45.03, -75.03, -96),        // −100..−90: agrees
            obs(45.06, -75.0, -125),        // −110..−100: 15 dB weaker
            obs(45.04, -75.04, -80),        // −100..−90: 10 dB stronger
            obs(45.04, -75.04, -79),
            obs(45.08, -75.08, -105, snr = -9.0),   // in −110 band, near noise
        )
        val track = listOf(TrackPoint(sessionId = "s", ms = 0, lat = 44.95, lon = -74.95, accuracyM = 5.0, radioReady = true, source = Source.LOCAL_RF),
            TrackPoint(sessionId = "s", ms = 0, lat = 45.099, lon = -75.099, accuracyM = 5.0, radioReady = true, source = Source.LOCAL_RF))
        val s = PredictionCompare.compare(p, g, o + obs(46.0, -75.0, -100), track, 6.0)
        val byPos = { lat: Double, lon: Double -> s.cells[g.key(lat, lon)]!!.cls }
        assertEquals(CellClass.AGREES, byPos(45.0, -75.0))
        assertEquals(CellClass.AGREES, byPos(45.03, -75.03))
        assertEquals(CellClass.WEAKER, byPos(45.06, -75.0))
        assertEquals(CellClass.STRONGER, byPos(45.04, -75.04))
        assertEquals(CellClass.PREDICTED_NOT_HEARD, byPos(44.95, -74.95))
        assertEquals(-15.0, s.cells[g.key(45.06, -75.0)]!!.residualDb)
        assertEquals(5, s.heardCells); assertEquals(5, s.heardPredictedCells); assertEquals(0, s.heardNotPredictedCells)
        assertEquals(1, s.nearNoiseCells); assertEquals(1, s.outsideReceptions); assertEquals(6, s.receptionsUsed)
        assertTrue(s.lines().any { it.contains("weak evidence") })
    }

    @Test fun predictionsAreStoredAndTransmittersListedByDirectReceptions() {
        val st = SqlStore(testDriver())
        val id = st.insertPrediction("Hilltop", "hill.geojson", SITE_PLANNER, 5)
        st.setPredictionTarget(id, "mt:!0000abcd")
        assertEquals("mt:!0000abcd", st.predictions().single().target)
        assertEquals(SITE_PLANNER, st.predictionGeoJson(id))
        st.insertObservation(obs(45.0, -75.0, -90))
        st.insertObservation(obs(45.0, -75.0, -91, heard = "mt:~cd").copy(rxMs = 2_000, hopsAway = 2))
        st.insertObservation(obs(45.0, -75.0, -92, heard = "mc:a3f1").copy(rxMs = 3_000, protocol = "meshcore"))
        assertEquals(mapOf("mt:!0000abcd" to 1, "mc:a3" to 1), st.directTransmitters())
        assertEquals(1, st.observations(ObsFilter(heardIds = setOf("mt:!0000abcd"))).size)
        st.deletePrediction(id); assertTrue(st.predictions().isEmpty())
    }
}
