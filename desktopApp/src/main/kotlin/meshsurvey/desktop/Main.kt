package meshsurvey.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import meshsurvey.core.engine.Edition
import meshsurvey.core.engine.SurveyEngine
import meshsurvey.core.preset.FileBackupStore
import meshsurvey.core.store.JdbcSqlDriver
import meshsurvey.core.store.SqlStore
import meshsurvey.ui.JvmClock
import meshsurvey.ui.MeshSurveyApp
import java.io.File
import kotlin.system.exitProcess

val APP_VERSION: String = System.getProperty("meshsurvey.version") ?: MainMarker::class.java.`package`?.implementationVersion ?: "0.1.0"
private object MainMarker

fun main(args: Array<String>) {
    // Used by CI on the *installed* app to prove the bundled runtime has everything it needs.
    val st = args.indexOf("--self-test")
    if (st >= 0) exitProcess(SelfTest.run(File(args.getOrNull(st + 1) ?: "meshsurvey-selftest.json")))

    val home = File(System.getProperty("meshsurvey.home") ?: (System.getProperty("user.home") + File.separator + ".meshsurvey")).apply { mkdirs() }
    // The Windows launcher has no console, so write any crash to a file the user can send us.
    val crashLog = File(home, "crash.log")
    fun logCrash(where: String, e: Throwable) = runCatching {
        crashLog.appendText("=== ${java.time.Instant.now()} MeshSurvey $APP_VERSION ($where) ===\n${e.stackTraceToString()}\n")
    }
    Thread.setDefaultUncaughtExceptionHandler { t, e -> logCrash("thread ${t.name}", e) }
    try { runApp(home) } catch (e: Throwable) { logCrash("startup", e); throw e }
}

private fun runApp(home: File) {
    val store = SqlStore(JdbcSqlDriver("jdbc:sqlite:" + File(home, "meshsurvey.db").absolutePath))
    val engine = SurveyEngine(store, JvmClock(), Edition.LITE, APP_VERSION,
        configBackups = FileBackupStore(File(home, "preset-backups")))
    val platform = DesktopPlatform(home, engine)

    application {
        val windowState = rememberWindowState(size = DpSize(1200.dp, 820.dp))
        Window(onCloseRequest = { engine.disconnect(); platform.stopGps(); exitApplication() },
            title = "MeshSurvey Lite — Meshtastic RF survey", state = windowState) {
            // No unattended transmission: a minimised window stops any automated test session.
            LaunchedEffect(windowState.isMinimized) { if (windowState.isMinimized) engine.onAppBackground() }
            MeshSurveyApp(engine, platform, runTickLoop = true)
        }
    }
}
