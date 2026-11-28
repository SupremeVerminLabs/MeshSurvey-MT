package meshsurvey.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import meshsurvey.core.engine.Edition
import meshsurvey.core.engine.Station
import meshsurvey.core.model.Fix
import meshsurvey.core.model.FixSource
import meshsurvey.core.store.JdbcSqlDriver
import meshsurvey.core.store.SqlStore
import meshsurvey.core.util.ManualClock
import meshsurvey.ui.MapFilter
import meshsurvey.ui.MeshSurveyApp
import meshsurvey.ui.PlatformServices
import meshsurvey.ui.SimSupport
import meshsurvey.ui.Tab
import meshsurvey.ui.ThemeMode
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * `--render-screens <dir>`: renders the real UI off-screen with SIMULATED data (a separate temporary database) at phone and
 * desktop sizes, in dark and light themes, so layout and contrast can be checked on every build without a radio or a display.
 * Map tiles are not fetched (plain background), so the images do not depend on the network.
 */
object Screens {
    private data class Shot(val name: String, val wDp: Int, val hDp: Int, val density: Float, val tab: Tab, val theme: ThemeMode,
                            val startOpen: Boolean = false, val prediction: Boolean = false, val heard: Boolean = false, val settings: Boolean = false)

    fun run(out: File): Int {
        out.mkdirs()
        val tmp = File(System.getProperty("java.io.tmpdir"), "meshsurvey-screens-${System.nanoTime()}").apply { mkdirs() }
        val clock = ManualClock(System.currentTimeMillis() - 2 * 3_600_000L, 1_000_000L)
        val store = SqlStore(JdbcSqlDriver("jdbc:sqlite:" + File(tmp, "m.db").absolutePath))
        val station = Station(store, clock, Edition.LITE, "screens", simStoreFactory = { SqlStore(JdbcSqlDriver("jdbc:sqlite:" + File(tmp, "sim.db").absolutePath)) })
        val sim = station.addSimulatedReceiver()
        val world = SimSupport.world
        var fix: Fix? = null
        sim.connect(SimSupport.link(clock, { fix }, peerSendsTests = false))
        // 90 minutes of a simulated drive: two loops at different distances from the centre.
        for (i in 0 until 5400) {
            clock.advance(1000)
            val r = if (i < 2700) 0.012 else 0.028
            val a = i / 2700.0 * 2 * Math.PI
            fix = Fix(world.centerLat + r * sin(a), world.centerLon + r * 1.4 * cos(a), 6.0, null, clock.wallMs(), clock.monotonicMs(), FixSource.SIMULATED)
            station.onFix(fix!!); station.tick()
        }
        // a synthetic prediction around the simulated repeater, compared with what the drive heard from it
        val rpt = world.node(world.repeaterNum)!!
        val pid = sim.store.insertPrediction("Sim Hill Repeater (synthetic)", null, ringsGeoJson(rpt.lat, rpt.lon), clock.wallMs())
        sim.store.setPredictionTarget(pid, "mt:!" + world.repeaterNum.toString(16).padStart(8, '0'))

        val real = DesktopPlatform(tmp, station.primary)
        val platform = object : PlatformServices by real {
            override suspend fun tileBytes(url: String, offlineOnly: Boolean): ByteArray? = null
        }
        val shots = listOf(
            Shot("phone-map-dark", 412, 892, 2f, Tab.MAP, ThemeMode.DARK),
            Shot("phone-map-prediction-dark", 412, 892, 2f, Tab.MAP, ThemeMode.DARK, prediction = true),
            Shot("phone-monitor-dark", 412, 892, 2f, Tab.MONITOR, ThemeMode.DARK),
            Shot("phone-test-dark", 412, 892, 2f, Tab.TEST, ThemeMode.DARK),
            Shot("phone-setup-dark", 412, 892, 2f, Tab.SETUP, ThemeMode.DARK),
            Shot("phone-monitor-light", 412, 892, 2f, Tab.MONITOR, ThemeMode.LIGHT),
            Shot("desktop-map-dark", 1280, 800, 1f, Tab.MAP, ThemeMode.DARK, heard = true),
            Shot("desktop-map-prediction-light", 1280, 800, 1f, Tab.MAP, ThemeMode.LIGHT, prediction = true),
            Shot("desktop-monitor-dark", 1280, 800, 1f, Tab.MONITOR, ThemeMode.DARK, startOpen = true),
            Shot("narrow-monitor-dark", 760, 640, 1f, Tab.MONITOR, ThemeMode.DARK, startOpen = true),
            Shot("phone-history-dark", 412, 892, 2f, Tab.HISTORY, ThemeMode.DARK),
            Shot("desktop-history-light", 1280, 800, 1f, Tab.HISTORY, ThemeMode.LIGHT),
            Shot("phone-settings-dark", 412, 892, 2f, Tab.MAP, ThemeMode.DARK, settings = true),
        )
        var failures = 0
        for (s in shots) {
            try {
                val scene = ImageComposeScene((s.wDp * s.density).toInt(), (s.hDp * s.density).toInt(), Density(s.density)) {
                    MeshSurveyApp(station, platform, runTickLoop = false, initialTab = s.tab, initialSettings = s.settings) { m ->
                        m.engine = sim
                        m.theme = s.theme
                        m.startOpen = s.startOpen
                        m.mapFilter = MapFilter(predictionId = if (s.prediction) pid else null, showHeard = s.heard, cellM = 250)
                        m.map.centerOn(world.centerLat, world.centerLon, 13.0)
                        m.centeredOnce = true
                    }
                }
                repeat(10) { scene.render(System.nanoTime()); Thread.sleep(400) }   // history reads and names places in the background
                val img = scene.render(System.nanoTime())
                File(out, s.name + ".png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                scene.close()
                println("rendered ${s.name}")
            } catch (e: Throwable) {
                failures++
                System.err.println("FAILED ${s.name}: ${e.stackTraceToString()}")
            }
        }
        return if (failures == 0) 0 else 1
    }

    /** Concentric square-ish rings (GeoJSON, Site Planner style) of predicted dBm around a point. */
    private fun ringsGeoJson(lat: Double, lon: Double): String {
        fun ring(rDeg: Double): String = (0..36).joinToString(",", "[", "]") { k ->
            val a = k / 36.0 * 2 * Math.PI
            "[${lon + rDeg * 1.4 * cos(a)},${lat + rDeg * sin(a)}]"
        }
        val bands = listOf(-125 to 0.045, -118 to 0.032, -111 to 0.021, -104 to 0.012, -97 to 0.005)
        return bands.joinToString(",", """{"type":"FeatureCollection","properties":{"name":"Sim Hill Repeater (synthetic)"},"features":[""", "]}") { (dbm, r) ->
            """{"type":"Feature","properties":{"dbm":$dbm},"geometry":{"type":"Polygon","coordinates":[${ring(r)}]}}"""
        }
    }
}
