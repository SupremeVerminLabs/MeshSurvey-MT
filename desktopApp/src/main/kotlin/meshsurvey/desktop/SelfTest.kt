package meshsurvey.desktop

import com.fazecast.jSerialComm.SerialPort
import meshsurvey.core.engine.SurveyEngine
import meshsurvey.core.model.PresetChangeStatus
import meshsurvey.core.preset.FileBackupStore
import meshsurvey.core.sim.SimWorld
import meshsurvey.core.sim.SimulatedLink
import meshsurvey.core.sim.SimulatedLocation
import meshsurvey.core.store.JdbcSqlDriver
import meshsurvey.core.store.SqlStore
import meshsurvey.core.util.ManualClock
import java.io.File
import java.nio.file.Files

/**
 * Headless check of an installed build (no radio, no window): bundled Java runtime, SQLite native
 * library, serial native library, Skia (map tile decoding), and a simulated survey + A/B session +
 * preset change/restore with file backups. Writes a JSON report; exit code 0 = all passed.
 * Everything exercised here is SIMULATED; it says nothing about radio hardware.
 */
object SelfTest {
    fun run(report: File): Int {
        val results = LinkedHashMap<String, String>()
        fun check(name: String, block: () -> String) {
            results[name] = try { "PASS: " + block() } catch (t: Throwable) { "FAIL: ${t::class.simpleName}: ${t.message}" }
        }
        val dir = Files.createTempDirectory("meshsurvey-selftest").toFile()
        check("java_runtime") { "${System.getProperty("java.version")} ${System.getProperty("java.vendor")} ${System.getProperty("os.name")}" }
        check("sqlite_native") {
            val s = SqlStore(JdbcSqlDriver("jdbc:sqlite:" + File(dir, "t.db").absolutePath))
            "schema v${s.schemaVersion()}"
        }
        check("serial_native") { "${SerialPort.getCommPorts().size} serial port(s) visible" }
        check("skia_decode") {
            // 1x1 PNG
            val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
            val img = org.jetbrains.skia.Image.makeFromEncoded(png)
            "${img.width}x${img.height}"
        }
        // Windows: the bundled Bluetooth helper starts without extra runtimes and answers.
        // (CI runners have no Bluetooth adapter, so "adapter none" is the expected answer there.)
        if (WindowsBle.command() == null && !System.getProperty("os.name").orEmpty().startsWith("Windows")) {
            results["ble_helper"] = "SKIP: not Windows"
        } else check("ble_helper") {
            val cmd = WindowsBle.command() ?: error(WindowsBle.unavailableReason())
            val q = java.util.concurrent.LinkedBlockingQueue<String>()
            val h = meshsurvey.core.radio.BleHelperProcess(cmd, { q.put(it) }, { q.put("__exit $it") })
            try {
                h.send("adapter")
                var answer: String? = null
                val end = System.currentTimeMillis() + 15_000
                while (answer == null && System.currentTimeMillis() < end) {
                    val l = q.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    if (l.startsWith("adapter") || l.startsWith("error") || l.startsWith("__exit")) answer = l
                }
                require(answer != null && answer.startsWith("adapter")) { "helper did not answer (${answer ?: "timeout"})" }
                "helper runs; $answer"
            } finally { h.close() }
        }
        check("simulated_survey") {
            val clock = ManualClock()
            val world = SimWorld(seed = 9)
            val loc = SimulatedLocation(world, clock)
            val e = SurveyEngine(SqlStore(JdbcSqlDriver("jdbc:sqlite:" + File(dir, "sim.db").absolutePath)), clock,
                configBackups = FileBackupStore(File(dir, "backups")))
            val link = SimulatedLink(world, clock, { loc.current().let { it.lat to it.lon } })
            e.connect(link)
            repeat(5) { clock.advance(1000); e.onFix(loc.current()); e.tick() }
            e.ab.setPeer(world.peerNodeNum, confirmed = true)
            e.ab.startAuto(60, 900)
            repeat(1000) { clock.advance(1000); e.onFix(loc.current()); e.tick() }
            val obs = e.store.countObservations(); val tx = e.store.testTx()
            require(obs > 0) { "no observations" }
            require(tx.isNotEmpty() && tx.none { it.status.name == "SENT" }) { "test outcomes missing" }
            require(e.presets.apply(e.presets.plan(8), autoRestore = false) == null) { "preset apply refused" }
            repeat(10) { clock.advance(1000); e.tick() }
            require(e.presetChange.value?.status == PresetChangeStatus.ACTIVE) { "preset not confirmed" }
            require(e.presets.restore() == null) { "restore refused" }
            repeat(10) { clock.advance(1000); e.tick() }
            require(e.presetChange.value == null) { "restore not confirmed" }
            require(File(dir, "backups").listFiles()!!.isNotEmpty()) { "no backup file" }
            "$obs observations, ${tx.size} tests, preset change + restore confirmed (SIMULATED)"
        }
        val ok = results.values.all { it.startsWith("PASS") || it.startsWith("SKIP") }
        val json = buildString {
            append("{\n  \"meshsurvey_selftest\": \"${if (ok) "PASS" else "FAIL"}\",\n  \"version\": \"$APP_VERSION\",\n")
            append(results.entries.joinToString(",\n") { (k, v) -> "  \"$k\": \"${v.replace("\\", "\\\\").replace("\"", "'")}\"" })
            append("\n}\n")
        }
        report.absoluteFile.parentFile?.mkdirs()
        report.writeText(json)
        println(json)
        return if (ok) 0 else 1
    }
}
