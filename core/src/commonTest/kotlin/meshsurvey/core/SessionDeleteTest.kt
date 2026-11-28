package meshsurvey.core

import meshsurvey.core.model.SessionStatus
import meshsurvey.core.model.Source
import meshsurvey.core.model.SurveySession
import meshsurvey.core.model.TrackPoint
import meshsurvey.core.store.ObsFilter
import meshsurvey.core.store.SqlStore
import meshsurvey.core.testsupport.testDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionDeleteTest {
    private fun session(id: String, status: SessionStatus) = SurveySession(id, 1_000, if (status == SessionStatus.ACTIVE) null else 2_000, status,
        1, "SERIAL", "2.7", "LONG_FAST", "US", Source.LOCAL_RF)

    @Test fun deletesOneSessionWithItsDataAndRefusesTheActiveOne() {
        val st = SqlStore(testDriver())
        st.insertSession(session("old", SessionStatus.ENDED)); st.insertSession(session("live", SessionStatus.ACTIVE))
        st.insertObservation(PredictionTest.obs(45.0, -75.0, -90).copy(sessionId = "old"))
        st.insertObservation(PredictionTest.obs(45.0, -75.0, -91).copy(sessionId = "live", rxMs = 5_000))
        st.insertTrack(TrackPoint(sessionId = "old", ms = 1_500, lat = 45.0, lon = -75.0, accuracyM = 5.0, radioReady = true, source = Source.LOCAL_RF))
        assertEquals(mapOf("old" to 1, "live" to 1), st.observationCountsBySession())
        assertFalse(st.deleteSession("live"))                                 // logging now: refused, nothing removed
        assertTrue(st.deleteSession("old"))
        assertEquals(listOf("live"), st.sessions().map { it.id })
        assertEquals(1, st.observations(ObsFilter()).size)
        assertTrue(st.track().isEmpty())
        assertFalse(st.deleteSession("old"))
    }
}
