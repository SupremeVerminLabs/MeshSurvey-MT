package meshsurvey.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import meshsurvey.core.community.Community
import meshsurvey.core.community.ContributionStatus
import meshsurvey.core.community.Contributions
import meshsurvey.core.community.Destination
import meshsurvey.core.community.PrivacyZone
import meshsurvey.core.community.ShareSettings
import meshsurvey.core.export.Bundle
import meshsurvey.core.model.SessionStatus
import meshsurvey.core.model.Source
import meshsurvey.core.model.SurveySession
import meshsurvey.core.store.SqlStore
import meshsurvey.core.testsupport.testDriver
import meshsurvey.core.util.newUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A stand-in for the sheet with the script's duplicate rules (accepted batch ids, completed session keys). Not Google. */
class FakeSheet {
    val batches = HashMap<String, Int>(); val complete = HashSet<String>(); val bodies = ArrayList<String>()
    var failAfter = Int.MAX_VALUE; var calls = 0
    fun post(@Suppress("UNUSED_PARAMETER") url: String, body: String): String {
        calls++
        if (calls > failAfter) throw RuntimeException("offline")
        bodies += body
        val o = Json.parseToJsonElement(body).jsonObject
        fun s(k: String) = (o[k] as JsonPrimitive).content
        if (s("type") == "withdraw") return """{"ok":true,"message":"2 rows removed"}"""
        if (s("provenance") != "local_rf") return """{"ok":false,"message":"only real"}"""
        val key = s("session_key"); val id = s("batch_id")
        if (key in complete || id in batches) return """{"ok":true,"message":"already accepted","receipt":{"batch_id":"$id","accepted":0,"already":true}}"""
        val n = o["rows"]!!.jsonArray.size
        batches[id] = n
        if (batches.keys.count { it.startsWith(key) } == s("parts").toInt()) complete += key
        return """{"ok":true,"message":"$n rows added","receipt":{"batch_id":"$id","accepted":$n}}"""
    }
}

class CommunityTest {
    private val day = { _: Long -> "2026-11-27" }
    private val zone = PrivacyZone(37.95, -122.0)
    private fun obs(lat: Double?, lon: Double?, session: String, snr: Double = -3.0, src: Source = Source.LOCAL_RF, fix: String? = "GPS", acc: Double? = 5.0,
                    heard: String = "mt:!0000abcd", ms: Long = 1_000) =
        PredictionTest.obs(lat ?: 0.0, lon ?: 0.0, -100, snr, heard).copy(lat = lat, lon = lon, source = src, fixSource = fix, accuracyM = acc, sessionId = session, rxMs = ms)
    private fun session(id: String = newUuid(), src: Source = Source.LOCAL_RF, status: SessionStatus = SessionStatus.ENDED, start: Long = 1_000) =
        SurveySession(id, start, start + 600_000, status, 0x111, "SERIAL", "2.7", "LONG_FAST", "US", src, hwModel = 4, txPowerDbm = 0)
    private fun ready(st: SqlStore) = Contributions(st, "t").also { it.update(ShareSettings(zones = listOf(zone), zonesConfirmed = true)) }

    @Test fun privacyFiltersRunBeforeThePayloadIsBuilt() {
        val s = ShareSettings(zones = listOf(zone, PrivacyZone(38.5, -121.5, 500.0, "Work")))
        val list = listOf(obs(37.97, -122.03, "x"), obs(37.97001, -122.03001, "x", snr = -9.0), obs(37.9501, -122.0001, "x"), obs(38.5, -121.5, "x"),
            obs(null, null, "x"), obs(37.98, -122.04, "x", src = Source.SIMULATED), obs(37.98, -122.04, "x", src = Source.IMPORTED), obs(37.98, -122.04, "x", fix = "MOCK"),
            obs(37.99, -122.05, "x", snr = 60.0, acc = 80.0), obs(37.99, -122.06, "x", heard = "mt:!00000111"))
        val (rows, ex) = Community.build(list, s, day, ownNodes = setOf(0x111L))
        assertEquals(2, ex.homeZone); assertEquals(1, ex.noPosition); assertEquals(2, ex.simulatedOrImported); assertEquals(1, ex.mock)
        assertEquals(3, rows.size)
        assertEquals(2, rows[0].n); assertEquals(-6.0, rows[0].snrMedian)
        assertTrue(rows[1].flags.containsAll(listOf("gps_poor", "snr_implausible")))
        assertEquals("own_radio", rows[2].heardFrom)
        // the serialiser refuses simulated sessions outright
        assertFailsWith<IllegalArgumentException> { Community.sessionPart(session(src = Source.SIMULATED), rows, 0, 1, emptyMap(), "tok12345", "t", "0".repeat(64)) }
        val json = Community.sessionPart(session("11111111-2222-4333-8444-555555555555"), rows, 0, 1, mapOf("radio_model" to "TBEAM"), "tok12345", "t", "0".repeat(64))
        assertFalse(json.contains("11111111-2222")); assertFalse(json.contains("\"device")); assertTrue(json.contains("\"provenance\":\"local_rf\""))
    }

    @Test fun freshInstallNeverUploadsAndDecliningKeepsLocalData() {
        val st = SqlStore(testDriver())
        val c = Contributions(st, "t")
        assertFalse(c.settings.autoUpload); assertTrue(c.settings.promptAfterSession); assertEquals(Destination.DEFAULT, c.settings.destination)
        val ses = session(); st.insertSession(ses); st.insertObservation(obs(37.97, -122.03, ses.id))
        assertTrue(c.autoCandidates(10_000_000).isEmpty())
        val sheet = FakeSheet()
        assertTrue(c.upload(ses.id, day, emptySet(), 5, sheet::post).contains("privacy zones"))     // no zones confirmed yet: nothing sent
        assertEquals(0, sheet.calls)
        c.keepLocal(ses.id, 5)
        assertEquals(ContributionStatus.EXCLUDED, c.status(ses.id)!!.status)
        assertEquals(1, st.observations().size); assertEquals(1, st.sessions().size)
        c.update(c.settings.copy(zones = listOf(zone), zonesConfirmed = true))
        assertTrue(c.autoCandidates(10_000_000).isEmpty())                                            // auto-upload still off
    }

    @Test fun historicalSessionUploadsOnceResumesWithoutDuplicatesAndWithdraws() {
        val st = SqlStore(testDriver())
        val ses = session(id = "0123456789abcdef")                                                      // an older (pre-UUID) session id still works
        st.insertSession(ses)
        for (i in 0 until 700) st.insertObservation(obs(37.97 + i * 0.001, -122.03, ses.id, ms = 1_000L + i))
        val c = ready(st)
        val sum = c.summary(ses.id, day, emptySet(), 5)!!
        assertNull(sum.ineligible); assertEquals(700, sum.cells); assertEquals("MeshSurvey community sheet", sum.destination)
        val sheet = FakeSheet().apply { failAfter = 1 }                                                // network drops after the first part
        assertTrue(c.upload(ses.id, day, emptySet(), 5, sheet::post).contains("Partly uploaded"))
        assertEquals(ContributionStatus.PARTIAL, c.status(ses.id)!!.status)
        assertTrue(c.status(ses.id)!!.nextRetryMs!! >= 5 + Contributions.FIRST_RETRY_MS)               // backoff, no tight loop
        sheet.failAfter = Int.MAX_VALUE
        assertTrue(c.upload(ses.id, day, emptySet(), 6, sheet::post).startsWith("Uploaded: 700 of 700"))
        assertEquals(3, sheet.batches.size); assertEquals(700, sheet.batches.values.sum())             // 3 parts, each sent once
        assertEquals(ContributionStatus.UPLOADED, c.status(ses.id)!!.status)
        assertEquals("Already uploaded", c.upload(ses.id, day, emptySet(), 7, sheet::post))
        // the same session imported on another device: the sheet recognises it, nothing is added
        val other = SqlStore(testDriver())
        Bundle.import(Bundle.export(st, "devA", "t", 10), other, "devB", 11)
        assertEquals(ses.id, other.sessions().single().id)                                             // UUID preserved through export/import
        val c2 = ready(other)
        assertTrue(c2.upload(ses.id, day, emptySet(), 12, sheet::post).startsWith("Uploaded: 0 of"))
        assertEquals(700, sheet.batches.values.sum())
        // withdrawal uses the private token; local data unchanged
        assertTrue(c.withdraw(ses.id, 13, sheet::post).startsWith("Withdrawn"))
        assertEquals(ContributionStatus.WITHDRAWN, c.status(ses.id)!!.status)
        assertTrue(sheet.bodies.last().contains(c.status(ses.id)!!.deleteToken!!))
        assertFalse(sheet.bodies.first().contains(c.status(ses.id)!!.deleteToken!!))                   // only its hash went with the data
        assertEquals(700, st.observations().size)
    }

    @Test fun simultaneousSessionsStayDistinctAndSimulatedOnesAreExcluded() {
        val st = SqlStore(testDriver())
        val a = session(start = 5_000); val b = session(start = 5_000); val sim = session(src = Source.SIMULATED)
        listOf(a, b, sim).forEach { st.insertSession(it); st.insertObservation(obs(37.97, -122.03, it.id)) }
        assertNotEquals(Community.sessionKey(a.id), Community.sessionKey(b.id))
        val c = ready(st); val sheet = FakeSheet()
        c.upload(a.id, day, emptySet(), 9, sheet::post); c.upload(b.id, day, emptySet(), 9, sheet::post)
        assertEquals(2, sheet.complete.size)
        assertTrue(c.upload(sim.id, day, emptySet(), 9, sheet::post).contains("simulated"))
        assertEquals(ContributionStatus.EXCLUDED, c.status(sim.id)!!.status)
        assertEquals(2, sheet.calls)
        // auto-upload, once permitted, picks up only real finished sessions not yet decided
        c.update(c.settings.copy(autoUpload = true))
        assertTrue(c.autoCandidates(10).isEmpty())
    }

    @Test fun equipmentIsOptInAndCoarse() {
        val st = SqlStore(testDriver())
        st.upsertProfile(meshsurvey.core.model.EquipmentProfile("p", "My callsign K1ABC rig", radioMake = "LilyGO", antennaGainDbi = 5.8,
            antennaType = meshsurvey.core.model.AntennaType.EXTERNAL, placement = meshsurvey.core.model.Placement.OUTDOOR, mounting = meshsurvey.core.model.Mounting.STATIONARY, notes = "roof"))
        val ses = session().copy(profileId = "p")
        val c = ready(st)
        assertTrue(c.equipmentFor(ses).isEmpty())
        c.update(c.settings.copy(shareRadioModel = true, shareAntennaCategory = true, shareAntennaInternalExternal = true, shareInstallation = true, shareTxPower = true))
        val e = c.equipmentFor(ses)
        assertEquals("gain_3_to_6dbi", e["antenna_category"]); assertEquals("external", e["antenna"]); assertEquals("outdoor_stationary", e["installation"])
        assertEquals("firmware_default", e["tx_power_dbm"])
        assertFalse(e.values.any { it.contains("K1ABC") || it.contains("roof") || it.contains("LilyGO") })
    }

    @Test fun settingsPersistAndTheFirstVersionMigratesWithoutGrantingUploads() {
        val st = SqlStore(testDriver())
        st.setMeta(Contributions.KEY_SETTINGS_V1, """{"enabled":true,"endpoint":"https://example.org/exec","home_lat":1.0,"home_lon":2.0,"auto":true}""")
        val c = Contributions(st, "t")
        assertEquals(Destination.CUSTOM, c.settings.destination); assertEquals(1, c.settings.zones.size); assertFalse(c.settings.autoUpload)
        c.update(c.settings.copy(shareTxPower = true))
        assertEquals(c.settings, Contributions(st, "t").settings)
    }

    @Test fun communityMapIsCachedAndNotDownloadedAgainWhenUnchanged() {
        val st = SqlStore(testDriver())
        val c = Contributions(st, "t")
        var gets = 0
        val first = """{"ok":true,"version":"v1","cells":[{"lat":37.9,"lon":-122,"preset":"LONG_FAST","protocol":"meshtastic","snr_median":-4.5,"n":7,"contributors":2,"pending_review":1}]}"""
        val m = c.refreshMap("a", "u?x", 1000, force = false, maxAgeMs = 3_600_000) { gets++; first }
        assertEquals(1, m.cells.size); assertEquals(1, m.cells[0].pendingReview)
        c.refreshMap("a", "u?x", 2000, force = false, maxAgeMs = 3_600_000) { gets++; first }
        assertEquals(1, gets)                                                                       // fresh cache: no request
        var asked = ""
        val again = c.refreshMap("a", "u?x", 3000, force = true, maxAgeMs = 3_600_000) { u -> gets++; asked = u; """{"ok":true,"unchanged":true,"version":"v1"}""" }
        assertTrue(asked.endsWith("&since=v1")); assertEquals(1, again.cells.size); assertEquals(3000L, again.fetchedMs)
        val down = c.refreshMap("a", "u?x", 4000, force = true, maxAgeMs = 3_600_000) { throw RuntimeException("offline") }
        assertEquals(1, down.cells.size); assertTrue(down.message!!.contains("not refreshed"))           // outage: cached data stays
    }
}
