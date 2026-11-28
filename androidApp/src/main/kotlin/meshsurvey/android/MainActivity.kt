package meshsurvey.android

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import meshsurvey.core.engine.Edition
import meshsurvey.core.engine.SurveyEngine
import meshsurvey.core.engine.Station
import meshsurvey.core.preset.FileBackupStore
import meshsurvey.core.store.AndroidSqlDriver
import meshsurvey.core.store.SqlStore
import meshsurvey.ui.JvmClock
import meshsurvey.ui.MeshSurveyApp

/** Owns the engine for the whole process and runs its 1 Hz tick independent of any activity. */
class MeshSurveyApplication : Application() {
    lateinit var station: Station; private set
    val engine: SurveyEngine get() = station.primary
    lateinit var platform: AndroidPlatform; private set
    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        station = Station(SqlStore(AndroidSqlDriver(this)), JvmClock(), Edition.LITE, BuildConfig.VERSION_NAME,
            configBackups = FileBackupStore(java.io.File(filesDir, "preset-backups")),
            simStoreFactory = { SqlStore(AndroidSqlDriver(this, "simulation.db")) })
        platform = AndroidPlatform(this, engine)
        scope.launch { while (true) { delay(1000); station.tick() } }
    }
}

class MainActivity : ComponentActivity() {
    private val app get() = application as MeshSurveyApplication
    private var pendingSave: Pair<String, (String?) -> Unit>? = null
    private var pendingOpen: ((String?) -> Unit)? = null
    private var pendingCopy: Pair<java.io.File, (String?) -> Unit>? = null

    private val saveLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val (text, done) = pendingSave ?: return@registerForActivityResult
        pendingSave = null
        val uri: Uri? = r.data?.data
        if (uri == null) { done(null); return@registerForActivityResult }
        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
        done(uri.lastPathSegment ?: uri.toString())
    }

    private val openLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val done = pendingOpen ?: return@registerForActivityResult
        pendingOpen = null
        done(uri?.let { u -> contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() } })
    }

    private val copyLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val (dest, done) = pendingCopy ?: return@registerForActivityResult
        pendingCopy = null
        if (uri == null) { done(null); return@registerForActivityResult }
        Thread {
            val ok = try { contentResolver.openInputStream(uri)?.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }; true } catch (_: Exception) { false }
            runOnUiThread { done(if (ok) dest.absolutePath else null) }
        }.start()
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) { perms += Manifest.permission.BLUETOOTH_SCAN; perms += Manifest.permission.BLUETOOTH_CONNECT }
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(perms.toTypedArray())

        app.platform.fileUi = object : AndroidPlatform.FileUi {
            override fun save(name: String, mime: String, text: String, done: (String?) -> Unit) {
                pendingSave = text to done
                saveLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mime).putExtra(Intent.EXTRA_TITLE, name))
            }
            override fun open(done: (String?) -> Unit) { pendingOpen = done; openLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }
            override fun copyIn(dest: java.io.File, done: (String?) -> Unit) { pendingCopy = dest to done; copyLauncher.launch(arrayOf("*/*")) }
        }
        setContent { MeshSurveyApp(app.station, app.platform, runTickLoop = false) }
    }

    /** No unattended transmission: leaving the screen stops automated tests. Passive logging continues. */
    override fun onStop() { super.onStop(); app.station.onAppBackground() }
}

/**
 * Keeps the process alive for passive logging while the user has a radio connected, including with the
 * screen locked. Holds a partial wake lock so BLE callbacks, GPS and the 1 Hz engine tick keep running
 * under Doze. Never transmits by itself; automated tests stop when the activity leaves the screen.
 */
class SurveyService : Service() {
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("survey", "Passive logging", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "survey")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("MeshSurvey is logging receptions")
            .setContentText("Passive logging continues with the screen locked. Disconnect in the app to stop.")
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(1, n)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        if (wakeLock == null) {
            wakeLock = getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "MeshSurvey:logging").apply { setReferenceCounted(false); acquire() }
        }
        return START_NOT_STICKY
    }
}
