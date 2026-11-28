package meshsurvey.core

import meshsurvey.core.history.HistObs
import meshsurvey.core.history.History
import meshsurvey.core.history.Places
import meshsurvey.core.model.Source
import meshsurvey.core.model.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryTest {
    private val places = Places.parse(sequenceOf(
        "Concord\t37.9780\t-122.0311\tUS\tCA",
        "Walnut Creek\t37.9063\t-122.0650\tUS\tCA",
        "Clayton\t37.9410\t-121.9358\tUS\tCA",
        "bad line",
        "Ottawa\t45.4112\t-75.6981\tCA\t",
    ))

    @Test fun nearestPlaceWithinRangeOnly() {
        assertEquals("Concord, CA", places.nearest(37.975, -122.03)!!.label)
        assertEquals("Ottawa (CA)", places.nearest(45.42, -75.70)!!.label)         // no region code: country
        assertNull(places.nearest(0.0, -140.0))                                     // open ocean
        assertEquals(4, places.size)
    }

    private fun tp(ms: Long, lat: Double, lon: Double, src: Source = Source.LOCAL_RF) =
        TrackPoint(sessionId = "s", ms = ms, lat = lat, lon = lon, accuracyM = 5.0, radioReady = true, source = src)

    @Test fun groupsByLocalDayAndNamesThePlaceMostCovered() {
        val pst = { _: Long -> -8 * 3_600_000L }
        val day1 = 1_764_230_400_000L          // 2025-11-27 08:00 UTC = 00:00 PST
        val track = (0 until 60).map { tp(day1 + 3_600_000L + it * 10_000L, 37.978, -122.031 + it * 0.0001) } +     // around Concord
            (0 until 20).map { tp(day1 + 7_200_000L + it * 10_000L, 37.906, -122.065) } +                              // a bit of Walnut Creek
            listOf(tp(day1 + History.DAY_MS + 3_600_000L, 37.941, -121.936))                                           // next day, Clayton
        val obs = listOf(HistObs(day1 + 3_700_000L, 37.978, -122.03, 0x11, -3.0, 1, "LONG_FAST", "meshtastic", Source.LOCAL_RF),
            HistObs(day1 + 3_800_000L, null, null, 0x12, 4.5, 2, "MEDIUM_FAST", "meshtastic", Source.LOCAL_RF),
            HistObs(day1 - 60_000L, 37.978, -122.03, 0x11, 1.0, 1, "LONG_FAST", "meshtastic", Source.LOCAL_RF))   // 23:59 the day before
        val days = History.days(track, obs, pst, places)
        assertEquals(3, days.size)
        val d = days[1]                                                              // newest first
        assertEquals("Concord, CA", d.primaryPlace)
        assertEquals("Concord, CA & Walnut Creek, CA", History.title(d))
        assertEquals(2, d.receptions); assertEquals(2, d.nodesHeard); assertEquals(4.5, d.bestSnr)
        assertEquals(setOf("LONG_FAST", "MEDIUM_FAST"), d.presets); assertEquals(80, d.trackPoints)
        assertTrue(d.distanceKm > 0.4)
        assertEquals("Clayton, CA", days[0].primaryPlace)
        assertEquals(1, days[2].receptions)
        assertTrue(!d.simulated)
    }

    @Test fun dayWithoutPositionsHasNoPlace() {
        val days = History.days(emptyList(), listOf(HistObs(5_000_000L, null, null, 1, null, null, null, "meshcore", Source.SIMULATED)), { 0L }, places)
        assertEquals("no positions", History.title(days.single()))
        assertTrue(days.single().simulated)
    }
}
