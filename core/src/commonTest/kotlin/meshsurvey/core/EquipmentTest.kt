package meshsurvey.core

import meshsurvey.core.export.Bundle
import meshsurvey.core.model.AntennaType
import meshsurvey.core.model.EquipmentProfile
import meshsurvey.core.model.Fix
import meshsurvey.core.model.FixSource
import meshsurvey.core.model.Mounting
import meshsurvey.core.model.Placement
import meshsurvey.core.proto.BROADCAST
import meshsurvey.core.proto.Port
import meshsurvey.core.sim.FromRadioEncoder
import meshsurvey.core.store.Schema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EquipmentTest {
    private val rooftop = EquipmentProfile("p1", "Rooftop base", radioMake = "RAKwireless", radioModel = "RAK4631",
        antennaMake = "Generic", antennaModel = "5.8 dBi fibreglass", antennaGainDbi = 5.8, antennaType = AntennaType.EXTERNAL,
        placement = Placement.OUTDOOR, mounting = Mounting.STATIONARY, antennaHeightM = 9.0, feedlineType = "LMR-400", feedlineLengthM = 6.0)

    @Test fun profilesArePersistedWithUnknownsKeptUnknown() {
        val h = Harness()
        assertTrue(Schema.version >= 4)
        h.engine.saveProfile(rooftop)
        h.engine.saveProfile(EquipmentProfile("p2", "Car"))
        val got = h.store.profiles().associateBy { it.id }
        assertEquals(5.8, got["p1"]!!.antennaGainDbi); assertEquals(Mounting.STATIONARY, got["p1"]!!.mounting)
        assertNull(got["p2"]!!.antennaGainDbi); assertNull(got["p2"]!!.placement)
        assertEquals("no details entered", got["p2"]!!.summary())
        assertTrue(got["p1"]!!.createdMs > 0)
        h.engine.deleteProfile("p2")
        assertEquals(listOf("p1"), h.store.profiles().map { it.id })
        assertEquals(2, h.store.profiles(includeDeleted = true).size)   // old sessions keep their reference
    }

    @Test fun sessionRecordsProfileAndRadioReportedSnapshotAndChangeStartsNewSession() {
        val h = Harness()
        h.engine.saveProfile(rooftop)
        val first = h.store.sessions().single()
        assertNull(first.profileId)
        assertEquals("US", first.region); assertTrue(first.frequencySource != null)
        h.engine.setActiveProfile("p1")
        val sessions = h.store.sessions()
        assertEquals(2, sessions.size)
        assertEquals("p1", sessions.first { it.endedMs == null }.profileId)
    }

    @Test fun observationRecordsRealPositionAgeForStationaryRadioPositions() {
        val h = Harness()
        val determined = h.clock.wallMs() - 4 * 60_000
        h.engine.onFix(Fix(45.0, -75.0, null, null, h.clock.wallMs(), h.clock.monotonicMs(), FixSource.RADIO, positionTimeMs = determined))
        h.rx(FromRadioEncoder.packet(7, BROADCAST, 100, Port.TELEMETRY, byteArrayOf(), snr = 5f, rssi = -95, rxTime = h.liveTime()))
        val o = h.store.observations().single()
        assertEquals("RADIO", o.fixSource)
        assertEquals(4 * 60_000L, o.positionAgeMs)
        assertEquals(0L, o.fixAgeMs)
    }

    @Test fun bundleCarriesProfilesAndProvenance() {
        val a = Harness(myNode = 0x0a)
        a.engine.saveProfile(rooftop); a.engine.setActiveProfile("p1")
        a.fix()
        a.rx(FromRadioEncoder.packet(7, BROADCAST, 100, Port.TELEMETRY, byteArrayOf(), snr = 5f, rssi = -95, rxTime = a.liveTime()))
        val text = Bundle.export(a.store, a.engine.deviceId, "t", a.clock.wallMs())
        assertTrue(text.contains("\"version\":2")); assertTrue(text.contains("Rooftop base")); assertTrue(text.contains("\"profile_id\":\"p1\""))
        val b = Harness(myNode = 0x0b)
        val r = Bundle.import(text, b.store, b.engine.deviceId, b.clock.wallMs())
        assertEquals(1, r.observations)
        val imported = b.store.profiles().single()
        assertEquals("p1", imported.id); assertEquals(a.engine.deviceId, imported.originDevice)
        assertTrue(b.store.observations().single().positionAgeMs != null)
        val imported2 = b.store.sessions().filter { it.originDevice == a.engine.deviceId }
        assertEquals(2, imported2.size)                                    // the profile change started a second session
        assertTrue(imported2.any { it.profileId == "p1" } && imported2.any { it.profileId == null })
        b.store.recoverInterrupted(b.clock.wallMs())                       // crash recovery never touches imported sessions
        assertEquals(imported2.map { it.status }.toSet(), b.store.sessions().filter { it.originDevice == a.engine.deviceId }.map { it.status }.toSet())
    }
}
