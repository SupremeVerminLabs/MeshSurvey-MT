package meshsurvey.android

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.delay
import meshsurvey.core.engine.SurveyEngine
import meshsurvey.core.map.MbTiles
import meshsurvey.core.store.AndroidSqlDriver
import meshsurvey.core.model.Fix
import meshsurvey.core.model.FixSource
import meshsurvey.core.radio.LinkKind
import meshsurvey.core.radio.RadioLink
import meshsurvey.core.radio.StreamLinkBase
import meshsurvey.core.radio.TcpLink
import meshsurvey.ui.ConnectionOption
import meshsurvey.ui.Formats
import meshsurvey.ui.GpsOption
import meshsurvey.ui.PlatformServices
import meshsurvey.ui.SIM_GPS_ID
import meshsurvey.ui.SimGps
import meshsurvey.ui.TileDiskCache
import java.io.File
import java.io.InputStream
import java.io.OutputStream

private val mainHandler = Handler(Looper.getMainLooper())
private val post: (Runnable) -> Unit = { mainHandler.post(it) }

@SuppressLint("MissingPermission")
class AndroidPlatform(private val app: Context, private val engine: SurveyEngine) : PlatformServices {
    override val platformName = "Android ${android.os.Build.VERSION.RELEASE}"
    private val tiles = TileDiskCache(File(app.cacheDir, "tiles"), "MeshSurvey/0.1.0 (Android; Meshtastic RF survey tool)") { openMbtiles(it) }
    private val simGps = SimGps(engine.clock, post)
    private var locListener: LocationListener? = null
    /** Set by MainActivity: launches a document picker. */
    var fileUi: FileUi? = null

    interface FileUi {
        fun save(name: String, mime: String, text: String, done: (String?) -> Unit)
        fun open(done: (String?) -> Unit)
        /** Picks a document and copies it to [dest]; returns the destination path. */
        fun copyIn(dest: File, done: (String?) -> Unit)
    }

    override suspend fun discoverConnections(): List<ConnectionOption> {
        val list = ArrayList<ConnectionOption>()
        val bt = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (bt == null || !bt.isEnabled) {
            list += ConnectionOption("ble:none", LinkKind.BLE, "Bluetooth is off", available = false)
        } else {
            val found = LinkedHashMap<String, String>()
            bt.bondedDevices?.forEach { found[it.address] = (it.name ?: "?") + " (paired)" }
            val scanner = bt.bluetoothLeScanner
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, r: ScanResult) {
                    found.putIfAbsent(r.device.address, (r.device.name ?: r.scanRecord?.deviceName ?: "Meshtastic") + " ${r.rssi} dBm")
                }
            }
            try {
                scanner?.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleLink.SERVICE)).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
                delay(4000)
            } catch (e: SecurityException) {
                list += ConnectionOption("ble:perm", LinkKind.BLE, "Bluetooth permission missing", available = false, note = e.message)
            } finally { try { scanner?.stopScan(cb) } catch (_: Exception) {} }
            found.forEach { (addr, label) -> list += ConnectionOption("ble:$addr", LinkKind.BLE, label,
                note = "Close the official Meshtastic app first: a node accepts one Bluetooth client at a time.") }
        }
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        UsbSerialProber.getDefaultProber().findAllDrivers(usb).forEachIndexed { i, d ->
            list += ConnectionOption("usb:$i", LinkKind.SERIAL, "${d.device.productName ?: "USB serial"} (${d.device.deviceName})",
                note = if (usb.hasPermission(d.device)) null else "Android will ask for USB permission on connect")
        }
        list += ConnectionOption("tcp", LinkKind.TCP, "Network node (enter host above)", needsHost = true)
        list += ConnectionOption("sim", LinkKind.SIMULATED, "Simulated radio (development only, data marked SIMULATED)")
        return list
    }

    override fun openLink(option: ConnectionOption, host: String?): RadioLink = when (option.kind) {
        LinkKind.BLE -> BleLink(app, option.id.removePrefix("ble:"), option.label)
        LinkKind.SERIAL -> {
            val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
            val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb).getOrNull(option.id.removePrefix("usb:").toInt())
                ?: throw IllegalStateException("USB device no longer attached")
            if (!usb.hasPermission(driver.device)) {
                usb.requestPermission(driver.device, PendingIntent.getBroadcast(app, 0, Intent("meshsurvey.USB_PERMISSION").setPackage(app.packageName),
                    PendingIntent.FLAG_IMMUTABLE))
                throw IllegalStateException("Grant USB permission, then press Connect again")
            }
            UsbSerialLink(usb, driver.ports.first(), option.label)
        }
        LinkKind.TCP -> {
            val h = host?.trim().orEmpty().ifEmpty { throw IllegalArgumentException("Enter the node's host name or IP") }
            val parts = h.split(":")
            TcpLink(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 4403, post)
        }
        LinkKind.SIMULATED -> throw IllegalStateException("handled by shared UI")
    }

    override fun gpsOptions() = listOf(
        GpsOption("phone", "Phone GPS", note = "Accuracy is Android's reported horizontal accuracy (68% confidence radius)."),
        GpsOption(SIM_GPS_ID, "Simulated position (development only)"),
    )

    override fun startGps(option: GpsOption, manual: Triple<Double, Double, Double?>?, onFix: (Fix) -> Unit, onStatus: (String) -> Unit) {
        stopGps()
        if (option.id == SIM_GPS_ID) { simGps.start(onFix); onStatus("Simulated GPS — not a real position"); return }
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                @Suppress("DEPRECATION")
                val mock = if (android.os.Build.VERSION.SDK_INT >= 31) loc.isMock else loc.isFromMockProvider
                // Fix age is derived from elapsedRealtimeNanos (when the fix was computed), never from loc.time,
                // so cached or delayed locations are correctly treated as old.
                onFix(Fix(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                    if (loc.hasAltitude()) loc.altitude else null, loc.time, loc.elapsedRealtimeNanos / 1_000_000 + monoOffset(),
                    if (mock) FixSource.MOCK else FixSource.GPS))
                onStatus("Phone GPS")
            }
            override fun onProviderDisabled(provider: String) { onStatus("Location is turned off") }
            override fun onProviderEnabled(provider: String) {}
            @Deprecated("old API") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l, Looper.getMainLooper())
            locListener = l; onStatus("Waiting for GPS fix…")
        } catch (e: SecurityException) { onStatus("Location permission missing") }
    }

    /** Fix.monotonicMs must share the engine clock's timebase (System.nanoTime), not elapsedRealtime. */
    private fun monoOffset(): Long = engine.clock.monotonicMs() - android.os.SystemClock.elapsedRealtimeNanos() / 1_000_000

    override fun stopGps() {
        simGps.stop()
        locListener?.let { (app.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(it) }
        locListener = null
    }

    override fun saveText(suggestedName: String, mime: String, text: String, done: (String?) -> Unit) =
        fileUi?.save(suggestedName, mime, text, done) ?: done(null)

    override fun openText(done: (String?) -> Unit) = fileUi?.open(done) ?: done(null)

    override fun pickMbtiles(done: (String?) -> Unit) {
        // SQLite cannot open a content:// URI, so the chosen file is copied into app storage.
        val dir = File(app.filesDir, "maps").apply { mkdirs() }
        fileUi?.copyIn(File(dir, "map-${System.currentTimeMillis()}.mbtiles"), done) ?: done(null)
    }

    override fun openMbtiles(path: String): MbTiles? =
        try { if (File(path).isFile) MbTiles(AndroidSqlDriver.openReadOnly(path)) else null } catch (_: Exception) { null }

    override suspend fun tileBytes(url: String, offlineOnly: Boolean) = tiles.bytes(url, offlineOnly)
    override fun formatTime(ms: Long) = Formats.time(ms)
    override fun formatDateTime(ms: Long) = Formats.dateTime(ms)

    /** Started on the user's Connect (app visible, so Android allows it) and kept through reconnects until Disconnect. */
    override fun onSurveyActive(active: Boolean) {
        val i = Intent(app, SurveyService::class.java)
        try { if (active) app.startForegroundService(i) else app.stopService(i) } catch (e: Exception) {
            engine.event("Could not start background logging service: ${e.message}")
        }
    }

    override fun openBatterySettings(): Boolean = try {
        app.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }

    override fun defaultGps(): GpsOption? = gpsOptions().first()
}

/** Meshtastic over USB serial on Android (usb-serial-for-android drivers: CP210x, CH34x, FTDI, CDC-ACM). */
class UsbSerialLink(private val usb: UsbManager, private val port: UsbSerialPort, label: String) : StreamLinkBase(post) {
    override val kind = LinkKind.SERIAL
    override val description = "USB $label"

    override fun openStreams(): Pair<InputStream, OutputStream> {
        val conn = usb.openDevice(port.driver.device) ?: throw IllegalStateException("Cannot open USB device")
        port.open(conn)
        port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        port.dtr = true; port.rts = true
        val input = object : InputStream() {
            private val buf = ByteArray(1024)
            override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = port.read(buf, 1000)
                if (n <= 0) return 0
                val c = minOf(n, len); System.arraycopy(buf, 0, b, off, c); return c
            }
        }
        val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()))
            override fun write(b: ByteArray) = port.write(b, 2000)
            override fun write(b: ByteArray, off: Int, len: Int) = port.write(b.copyOfRange(off, off + len), 2000)
        }
        return input to output
    }

    override fun afterOpen(out: OutputStream) { out.write(ByteArray(32) { 0xC3.toByte() }); Thread.sleep(100) }
    override fun closeTransport() { try { port.close() } catch (_: Exception) {} }
}
