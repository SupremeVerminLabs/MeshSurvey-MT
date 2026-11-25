package meshsurvey.core.testsupport

import meshsurvey.core.engine.SurveyEngine
import meshsurvey.core.export.Bundle
import meshsurvey.core.export.Csv
import meshsurvey.core.export.GeoJson
import meshsurvey.core.sim.SimWorld
import meshsurvey.core.sim.SimulatedLink
import meshsurvey.core.sim.SimulatedLocation
import meshsurvey.core.store.SqlStore
import meshsurvey.core.util.ManualClock
import java.io.File

/** Generates samples/ from the simulator. Everything produced is source=SIMULATED. */
fun main(args: Array<String>) {
    val dir = File(args.getOrElse(0) { "samples" }).apply { mkdirs() }
    // Device A: 45-minute simulated drive, passive logging + one automated 10-minute test session
    run {
        val clock = ManualClock(wall = 1_764_072_000_000L)
        val world = SimWorld(seed = 11)
        val loc = SimulatedLocation(world, clock)
        val e = SurveyEngine(SqlStore(PythonSqlDriver()), clock, appVersion = "0.1.0-sample")
        e.connect(SimulatedLink(world, clock, { loc.current().let { it.lat to it.lon } }))
        e.ab.setPeer(world.peerNodeNum, confirmed = true)
        repeat(45 * 60) { i ->
            clock.advance(1_000); e.onFix(loc.current()); e.tick()
            if (i == 600) e.ab.startAuto(60, 1200)   // interval is raised to the LONG_FAST airtime minimum
        }
        e.disconnect()
        File(dir, "simulated-device-A.bundle.json").writeText(Bundle.export(e.store, e.deviceId, "0.1.0-sample", clock.wallMs()))
        File(dir, "simulated-device-A.observations.csv").writeText(Csv.observations(e.store.observations()))
        File(dir, "simulated-device-A.tests.csv").writeText(Csv.tests(e.store.testTx()))
        File(dir, "simulated-device-A.geojson").writeText(GeoJson.export(e.store.observations(), e.store.track(), e.store.testTx()))
        println("A: ${e.store.countObservations()} observations, ${e.store.track().size} track points, ${e.store.testTx().size} tests " +
            e.store.testTx().groupingBy { it.status }.eachCount())
    }
    // Device B role: simulated peer sends tests to us
    run {
        val clock = ManualClock(wall = 1_764_080_000_000L)
        val world = SimWorld(seed = 12)
        val loc = SimulatedLocation(world, clock, radiusM = 1_500.0)
        val e = SurveyEngine(SqlStore(PythonSqlDriver()), clock, appVersion = "0.1.0-sample")
        e.connect(SimulatedLink(world, clock, { loc.current().let { it.lat to it.lon } }, peerSendsTests = true,
            peerRefCode = { e.referenceState.value.session?.refCode }))
        e.reference.start(15 * 60)
        repeat(15 * 60) { clock.advance(1_000); e.onFix(loc.current()); e.tick() }
        e.disconnect()
        File(dir, "simulated-device-B-reference.bundle.json").writeText(Bundle.export(e.store, e.deviceId, "0.1.0-sample", clock.wallMs()))
        println("B: ${e.store.testRx().size} test receptions logged")
    }
}
