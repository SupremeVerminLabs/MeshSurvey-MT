package meshsurvey.core

import meshsurvey.core.engine.SurveyEngine
import meshsurvey.core.model.PresetChangeStatus
import meshsurvey.core.model.SessionStatus
import meshsurvey.core.preset.FileBackupStore
import meshsurvey.core.proto.BROADCAST
import meshsurvey.core.proto.Port
import meshsurvey.core.sim.FromRadioEncoder
import meshsurvey.core.store.SqlStore
import meshsurvey.core.testsupport.fileTestDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Child process: logs data, starts a temporary preset change, then dies without any shutdown. */
object CrashingWriter {
    @JvmStatic fun main(args: Array<String>) {
        val dir = File(args[0])
        val r = CustomRadio(lora(), store = SqlStore(fileTestDriver(File(dir, "survey.db").absolutePath)))
        val h = r // reuse helper; log 50 receptions with a fix
        h.engine.onFix(meshsurvey.core.model.Fix(45.0, -75.0, 5.0, null, h.clock.wallMs(), h.clock.monotonicMs(), meshsurvey.core.model.FixSource.GPS))
        repeat(50) { h.rx(FromRadioEncoder.packet(9, BROADCAST, 1000L + it, Port.TEXT, null, snr = 1f, rssi = -100, rxTime = h.clock.wallMs() / 1000)) }
        val e = SurveyEngine(SqlStore(fileTestDriver(File(dir, "survey2.db").absolutePath)), h.clock,
            configBackups = FileBackupStore(File(dir, "backups")))
        val r2 = Harness.TestLink(); e.connect(r2)
        println("WROTE 50"); System.out.flush()
        Runtime.getRuntime().halt(137) // no close(), no shutdown hooks
    }
}

class DurabilityTest {
    @Test fun committedDataSurvivesAbruptProcessDeath() {
        val dir = Files.createTempDirectory("meshsurvey-crash").toFile()
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val p = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
            "-Dbridge.script=" + System.getProperty("bridge.script", "tools/sqlite_bridge.py"),
            CrashingWriter::class.java.name, dir.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(137, p.waitFor(), out)
        assertTrue(out.contains("WROTE 50"), out)
        val store = SqlStore(fileTestDriver(File(dir, "survey.db").absolutePath))
        assertEquals(50, store.countObservations())
        SurveyEngine(store, meshsurvey.core.util.ManualClock())
        assertEquals(SessionStatus.INTERRUPTED, store.sessions().single().status)
    }

    @Test fun fileBackupIsAtomicReadableAndReconciled() {
        val dir = Files.createTempDirectory("meshsurvey-backup").toFile()
        val backups = FileBackupStore(dir)
        val r = CustomRadio(lora(), backups = meshsurvey.core.preset.MemoryBackupStore())
        val plan = r.engine.presets.plan(8)
        val change = meshsurvey.core.model.PresetChange(nodeNum = 0x0a0a0a0a, createdMs = 1234, originalRaw = plan.originalRaw, tempRaw = plan.tempRaw,
            originalPreset = "LONG_FAST", targetPreset = "SHORT_TURBO", status = PresetChangeStatus.APPLYING, statusMs = 1234, autoRestore = true)
        backups.save(change)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        val text = dir.listFiles()!!.single().readText()
        assertTrue(text.contains("meshtastic --set lora.use_preset true --set lora.modem_preset LONG_FAST"))
        // a fresh install/database picks the open change up from the file
        val e = SurveyEngine(SqlStore(meshsurvey.core.testsupport.testDriver()), meshsurvey.core.util.ManualClock(), configBackups = backups)
        assertEquals("SHORT_TURBO", e.presetChange.value!!.targetPreset)
        backups.markResolved(0x0a0a0a0a, 1234)
        assertTrue(backups.loadAll().single().resolved)
    }
}
