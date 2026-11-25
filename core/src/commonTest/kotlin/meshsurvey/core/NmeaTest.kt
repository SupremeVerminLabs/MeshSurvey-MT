package meshsurvey.core

import meshsurvey.core.gps.Nmea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NmeaTest {
    @Test fun parsesGga() {
        val s = Nmea.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47") as Nmea.Sentence.Gga
        assertEquals(48.1173, s.lat!!, 1e-4); assertEquals(11.516667, s.lon!!, 1e-5)
        assertEquals(1, s.quality); assertEquals(8, s.satellites); assertEquals(545.4, s.altitudeM)
    }
    @Test fun rejectsBadChecksumAndNoFix() {
        assertNull(Nmea.parse("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*48"))
        val nofix = Nmea.parse("\$GNGGA,,,,,,0,00,99.99,,,,,,*56") as Nmea.Sentence.Gga
        assertEquals(0, nofix.quality); assertNull(nofix.lat)
    }
    @Test fun parsesGstSouthWest() {
        val g = Nmea.parse("\$GPGST,172814.0,0.006,0.023,0.020,273.6,3.0,4.0,0.040*68")
        assertTrue(g is Nmea.Sentence.Gst); assertEquals(5.0, (g as Nmea.Sentence.Gst).horizontalSigmaM, 1e-9)
        assertEquals(-33.5, Nmea.coord("3330.000", "S")!!, 1e-9); assertEquals(-70.25, Nmea.coord("07015.000", "W")!!, 1e-9)
    }
}
