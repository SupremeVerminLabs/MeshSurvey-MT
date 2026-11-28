package meshsurvey.core

import meshsurvey.core.community.Community
import meshsurvey.core.community.ShareSettings
import meshsurvey.core.community.Uploader
import meshsurvey.core.model.Source
import meshsurvey.core.store.SqlStore
import meshsurvey.core.testsupport.testDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommunityTest {
    private val day = { _: Long -> "2026-11-27" }
    private fun o(lat: Double?, lon: Double?, snr: Double = -3.0, src: Source = Source.LOCAL_RF, fix: String? = "GPS", acc: Double? = 5.0, heard: String = "mt:!0000abcd") =
        PredictionTest.obs(lat ?: 0.0, lon ?: 0.0, -100, snr, heard).copy(lat = lat, lon = lon, source = src, fixSource = fix, accuracyM = acc)

    @Test fun privacyRulesAndFlags() {
        val home = ShareSettings(enabled = true, homeLat = 37.95, homeLon = -122.0)
        val obs = listOf(
            o(37.97, -122.03), o(37.97001, -122.03001, snr = -9.0),           // same ~37 m square
            o(37.9501, -122.0001),                                              // inside the 250 m home zone
            o(null, null), o(37.98, -122.04, src = Source.SIMULATED), o(37.98, -122.04, fix = "MOCK"),
            o(37.99, -122.05, snr = 60.0, acc = 80.0),                          // odd: shared with flags
            o(37.99, -122.06, heard = "mt:!00000111"),                          // heard one of our own radios
        )
        val (rows, ex) = Community.build(obs, home, day, ownNodes = setOf(0x111L))
        assertEquals(1, ex.homeZone); assertEquals(1, ex.noPosition); assertEquals(1, ex.simulatedOrImported); assertEquals(1, ex.mock)
        assertEquals(3, rows.size)
        val a = rows[0]
        assertEquals(2, a.n); assertEquals(-6.0, a.snrMedian); assertEquals("mt:!0000abcd", a.heardFrom)
        assertTrue(a.lat != 37.97 || a.lon != -122.03)                         // square centre, not the real point
        assertTrue(rows[1].flags.containsAll(listOf("gps_poor", "snr_implausible", "single_reception")))
        assertEquals("own_radio", rows[2].heardFrom)
        val json = Community.payload(rows, "tok12345", "t")
        assertFalse(json.contains("device")); assertTrue(json.contains("\"flags\":\"gps_poor;single_reception;snr_implausible\""))
    }

    @Test fun uploaderNeedsConsentAndAHomeZoneAndMovesItsWatermarkOnlyOnSuccess() {
        val st = SqlStore(testDriver())
        st.insertObservation(o(37.97, -122.03).copy(rxMs = 1_000))
        val up = Uploader(st, "t")
        val sent = ArrayList<String>()
        val ok: (String, String) -> String = { _, b -> sent += b; """{"ok":true,"message":"1 rows added"}""" }
        assertEquals("Sharing is off", up.upload(10_000_000, day, emptySet(), ok))
        up.update(ShareSettings(enabled = true, endpoint = "https://script.google.com/macros/s/x/exec"))
        assertTrue(up.upload(10_000_000, day, emptySet(), ok).contains("home privacy zone"))
        up.update(up.settings.copy(homeLat = 10.0, homeLon = 10.0))
        assertTrue(up.upload(10_000_000, day, emptySet()) { _, _ -> throw RuntimeException("offline") }.contains("will retry"))
        assertEquals(0L, up.watermark)
        assertTrue(up.upload(10_000_000, day, emptySet(), ok).startsWith("Shared 1 squares"))
        assertEquals(1, sent.size); assertTrue(up.watermark > 1_000)
        assertEquals("Nothing new to share", up.upload(10_000_000, day, emptySet(), ok))
        assertEquals(up.settings, Uploader(st, "t").settings)                  // settings persist
        assertEquals(up.submitter, Uploader(st, "t").submitter)
        assertTrue(up.reportOddity("odd", 37.97, -122.03, "2026-11-27", ok) == "Report sent")
        assertTrue(sent.last().contains("\"type\":\"oddity\""))
    }

    @Test fun aggregateParsing() {
        val c = Community.parseAggregate("""{"ok":true,"cells":[{"lat":37.9,"lon":-122,"preset":"LONG_FAST","protocol":"meshtastic","snr_median":-4.5,"n":7,"contributors":2,"flagged":true},{"bad":1}]}""")
        assertEquals(1, c.size); assertEquals(2, c[0].contributors); assertTrue(c[0].flagged)
    }
}
