package meshsurvey.ui

import androidx.compose.ui.graphics.ImageBitmap
import meshsurvey.core.model.Fix
import meshsurvey.core.radio.LinkKind
import meshsurvey.core.radio.RadioLink
import meshsurvey.core.sim.SimWorld
import meshsurvey.core.sim.SimulatedLink
import meshsurvey.core.sim.SimulatedLocation
import meshsurvey.core.util.Clock

/** A way to reach a radio, as discovered by the platform. */
data class ConnectionOption(
    val id: String,
    val kind: LinkKind,
    val label: String,
    val available: Boolean = true,
    val note: String? = null,
    val needsHost: Boolean = false,
)

data class GpsOption(val id: String, val label: String, val needsManual: Boolean = false, val note: String? = null)

/** Everything the shared UI needs from Android or desktop. */
interface PlatformServices {
    val platformName: String
    /** May take a few seconds (BLE scan). Must include unavailable options with a [ConnectionOption.note] explaining why. */
    suspend fun discoverConnections(): List<ConnectionOption>
    /** Creates (does not start) a link. Throws with a user-readable message on failure. */
    fun openLink(option: ConnectionOption, host: String?): RadioLink
    fun gpsOptions(): List<GpsOption>
    fun startGps(option: GpsOption, manual: Triple<Double, Double, Double?>?, onFix: (Fix) -> Unit, onStatus: (String) -> Unit)
    fun stopGps()
    fun saveText(suggestedName: String, mime: String, text: String, done: (String?) -> Unit)
    fun openText(done: (String?) -> Unit)
    /** Lets the user pick a local .mbtiles file; returns a readable local path (Android copies it into app storage). */
    fun pickMbtiles(done: (String?) -> Unit)
    /** Opens an MBTiles file read-only (null if it can't be opened). */
    fun openMbtiles(path: String): meshsurvey.core.map.MbTiles?
    /**
     * Fetches (or reads from the tile cache) one map tile. offlineOnly = never touch the network.
     * URLs of the form "mbtiles:<path>#z/x/y" are served from a local MBTiles file.
     */
    suspend fun tileBytes(url: String, offlineOnly: Boolean): ByteArray?
    fun formatTime(ms: Long): String
    fun formatDateTime(ms: Long): String
    /**
     * Called with true when the user connects a radio and false when they disconnect. Android starts its
     * foreground service (with a partial wake lock) here, while the app is visible, and keeps it through
     * reconnects, so passive logging continues with the screen locked.
     */
    fun onSurveyActive(active: Boolean) {}
    /** Opens the OS setting that exempts the app from battery optimisation; false if not applicable. */
    fun openBatterySettings(): Boolean = false
    /** Default GPS source to start automatically (phone GPS on Android), or null. */
    fun defaultGps(): GpsOption? = null
}

expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/** One simulated world shared by the simulated radio and the simulated GPS. */
object SimSupport {
    val world by lazy { SimWorld(seed = 7) }
    private var location: SimulatedLocation? = null
    fun location(clock: Clock): SimulatedLocation = location ?: SimulatedLocation(world, clock).also { location = it }
    /** Simulated radio. Receiver position follows whatever GPS source is active. */
    fun link(clock: Clock, receiver: () -> Fix?, peerSendsTests: Boolean, peerRefCode: () -> Int? = { null }): RadioLink =
        SimulatedLink(world, clock, { receiver()?.let { it.lat to it.lon } }, peerSendsTests, peerRefCode)
}
