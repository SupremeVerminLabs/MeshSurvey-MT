package meshsurvey.desktop

import com.fazecast.jSerialComm.SerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import meshsurvey.core.engine.SurveyEngine
import meshsurvey.core.map.MbTiles
import meshsurvey.core.store.JdbcSqlDriver
import meshsurvey.core.model.Fix
import meshsurvey.core.model.FixSource
import meshsurvey.core.radio.BleHelperProcess
import meshsurvey.core.radio.BleHelperScan
import meshsurvey.core.radio.OsLocation
import meshsurvey.core.radio.BleScanResult
import meshsurvey.core.radio.HelperBleLink
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
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import meshsurvey.core.gps.Nmea

private val post: (Runnable) -> Unit = { SwingUtilities.invokeLater(it) }

class DesktopPlatform(private val home: File, private val engine: SurveyEngine) : PlatformServices {
    override val platformName = "Desktop (${System.getProperty("os.name")})"
    private val tiles = TileDiskCache(File(home, "tiles"), "MeshSurvey/$APP_VERSION (Meshtastic RF survey tool)") { openMbtiles(it) }
    private val simGps = SimGps(engine.clock, post)
    private var nmea: NmeaGps? = null

    override suspend fun discoverConnections(): List<ConnectionOption> = withContext(Dispatchers.IO) {
        val list = ArrayList<ConnectionOption>()
        SerialPort.getCommPorts().forEach { p ->
            list += ConnectionOption("serial:${p.systemPortName}", LinkKind.SERIAL, "${p.systemPortName} — ${p.descriptivePortName}",
                note = "Meshtastic or MeshCore (USB companion firmware); detected automatically when connecting.")
        }
        if (list.isEmpty()) list += ConnectionOption("serial:none", LinkKind.SERIAL, "No serial ports found", available = false,
            note = "Plug the radio in with a data-capable USB cable; on Linux add yourself to the 'dialout' group.")
        list += ConnectionOption("tcp", LinkKind.TCP, "Network node (enter host above)", needsHost = true,
            note = "Meshtastic nodes with Wi-Fi/Ethernet use TCP port 4403. For a MeshCore Wi-Fi companion radio, enter host:port (detected automatically).")
        list += bluetoothOptions()
        list += ConnectionOption("sim", LinkKind.SIMULATED, "Simulated radio (development only, data marked SIMULATED)")
        list
    }

    override fun openLink(option: ConnectionOption, host: String?): RadioLink = when (option.kind) {
        LinkKind.SERIAL -> SerialLink(option.id.removePrefix("serial:"))
        LinkKind.TCP -> {
            val h = host?.trim().orEmpty().ifEmpty { throw IllegalArgumentException("Enter the node's host name or IP") }
            val (hn, port) = h.split(":").let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 4403) }
            TcpLink(hn, port, post = post)
        }
        LinkKind.BLE -> {
            val cmd = WindowsBle.command() ?: throw IllegalArgumentException(WindowsBle.unavailableReason())
            // MeshCore companion radios only accept bonded (PIN-paired) connections; without a PIN, Windows waits for a pairing
            // that never comes. Their default PIN is 123456 (radios with a screen show theirs); an already paired radio skips this.
            val pin = host?.trim()?.ifEmpty { null } ?: if (option.label.contains(", MeshCore")) "123456" else null
            HelperBleLink(cmd, option.id.removePrefix("ble:"), option.label.substringBefore(" ("), pin, post)
        }
        else -> throw IllegalArgumentException("${option.kind.label} is not supported on desktop")
    }

    /** Windows only: lists Meshtastic and MeshCore radios found by a short Bluetooth scan through the helper. */
    private fun bluetoothOptions(): List<ConnectionOption> {
        val cmd = WindowsBle.command()
            ?: return listOf(ConnectionOption("ble", LinkKind.BLE, "Bluetooth LE", available = false, note = WindowsBle.unavailableReason()))
        return when (val r = BleHelperScan.scan(cmd, seconds = 5)) {
            is BleScanResult.Unavailable -> listOf(ConnectionOption("ble", LinkKind.BLE, "Bluetooth LE", available = false, note = r.reason))
            is BleScanResult.Found ->
                if (r.devices.isEmpty()) listOf(ConnectionOption("ble", LinkKind.BLE, "No Meshtastic or MeshCore radios found", available = false,
                    note = "Make sure the radio is on, nearby, and not connected to another phone or app (a radio accepts one Bluetooth connection at a time)."))
                else r.devices.map { d ->
                    val extra = listOfNotNull(d.rssi?.let { "$it dBm" }, if (d.paired) "paired" else null).joinToString(", ")
                    val proto = d.protocol?.label ?: "protocol found on connect"
                    ConnectionOption("ble:${d.address}", LinkKind.BLE, "${d.name} (${d.address}, $proto${if (extra.isEmpty()) "" else ", $extra"})",
                        needsPin = true, note = if (d.protocol == meshsurvey.core.radio.MeshProtocol.MESHCORE)
                            "MeshCore companion radio: listen-only. PIN is shown on its screen, or 123456 by default. Not yet verified with a real radio."
                        else "Windows Bluetooth (verified with a T-Beam Supreme).")
                }
        }
    }

    override fun openBatterySettings(): Boolean = false

    /** Keeps Windows from sleeping while any radio is connected (through the helper); normal power settings otherwise. */
    private var awake: BleHelperProcess? = null
    override fun onSurveyActive(active: Boolean) {
        if (active && awake == null) {
            val cmd = WindowsBle.command() ?: return
            awake = runCatching { BleHelperProcess(cmd, { line ->
                if (line == "awake on") post(Runnable { engine.event("Keeping this PC awake while radios are connected") })
            }, { post(Runnable { if (awake != null) { awake = null; engine.event("Keep-awake helper stopped; the PC may sleep") } }) }) }.getOrNull()
            awake?.send("awake on")
        } else if (!active) {
            awake?.let { h -> awake = null; thread(isDaemon = true) { h.send("awake off"); h.close() }; engine.event("PC may sleep again (no radio connected)") }
        }
    }

    override fun gpsOptions(): List<GpsOption> {
        val ports = SerialPort.getCommPorts().map { GpsOption("nmea:${it.systemPortName}", "NMEA GPS on ${it.systemPortName}",
            note = "USB GPS receiver (4800–115200 baud auto). Accuracy is taken from \$GxGST when the receiver sends it; otherwise it is recorded as unknown.") }
        val winLoc = if (WindowsBle.command() != null) listOf(GpsOption("winloc", "Windows location (laptop GPS or Wi-Fi)",
            note = "For using this PC on the move. Uses Windows' location service: satellite if the PC has a GPS, otherwise Wi-Fi " +
                "(often 20–100 m). IP-address positions are refused. Needs Location turned on for desktop apps in Windows Settings.")) else emptyList()
        return winLoc + ports + GpsOption("manual", "Fixed position (stationary receiver)", needsManual = true,
            note = "For a fixed site. Recorded with source MANUAL.") +
            GpsOption(SIM_GPS_ID, "Simulated position (development only)")
    }

    override fun startGps(option: GpsOption, manual: Triple<Double, Double, Double?>?, onFix: (Fix) -> Unit, onStatus: (String) -> Unit) {
        stopGps()
        when {
            option.id == SIM_GPS_ID -> { simGps.start(onFix); onStatus("Simulated GPS — not a real position") }
            option.id == "manual" && manual != null -> {
                // A fixed position is re-asserted every second so it stays "fresh".
                simGpsManual = java.util.Timer(true).also { t ->
                    t.scheduleAtFixedRate(object : java.util.TimerTask() {
                        override fun run() = post(Runnable {
                            onFix(Fix(manual.first, manual.second, manual.third, null, engine.clock.wallMs(), engine.clock.monotonicMs(), FixSource.MANUAL))
                        })
                    }, 0, 1000)
                }
                onStatus("Fixed position ${manual.first}, ${manual.second}")
            }
            option.id == "winloc" -> {
                val cmd = WindowsBle.command()
                if (cmd == null) { onStatus(WindowsBle.unavailableReason()); return }
                onStatus("Windows location: starting…")
                winLoc = BleHelperProcess(cmd, { line -> post(Runnable {
                    when {
                        line.startsWith("pos ") -> when (val r = OsLocation.parse(line, engine.clock.wallMs(), engine.clock.monotonicMs())) {
                            is OsLocation.Result.Ok -> {
                                onFix(r.fix)
                                onStatus("Windows location (${r.sourceLabel})" + (r.fix.accuracyM?.let { " ±${it.toInt()} m" } ?: ""))
                            }
                            is OsLocation.Result.Rejected -> onStatus(r.reason)
                            null -> {}
                        }
                        line.startsWith("error location") -> onStatus("Windows location: " + line.removePrefix("error location").trim())
                        line == "location started" -> onStatus("Windows location: waiting for a position…")
                    }
                }) }, { post(Runnable { if (winLoc != null) onStatus("Windows location stopped unexpectedly") }) }).also { it.send("location") }
            }
            option.id.startsWith("nmea:") -> {
                nmea = NmeaGps(option.id.removePrefix("nmea:"), engine, onFix, onStatus).also { it.start() }
            }
        }
    }

    private var simGpsManual: java.util.Timer? = null

    private var winLoc: BleHelperProcess? = null

    override fun stopGps() {
        winLoc?.let { h -> winLoc = null; thread(isDaemon = true) { h.send("location-stop"); h.close() } }
        simGps.stop(); nmea?.stop(); nmea = null; simGpsManual?.cancel(); simGpsManual = null
    }

    override fun saveText(suggestedName: String, mime: String, text: String, done: (String?) -> Unit) {
        val d = FileDialog(null as Frame?, "Save", FileDialog.SAVE).apply { file = suggestedName; isVisible = true }
        val f = d.file?.let { File(d.directory, it) }
        if (f == null) { done(null); return }
        thread { f.writeText(text); post(Runnable { done(f.absolutePath) }) }
    }

    override fun openText(done: (String?) -> Unit) {
        val d = FileDialog(null as Frame?, "Open", FileDialog.LOAD).apply { isVisible = true }
        val f = d.file?.let { File(d.directory, it) }
        if (f == null) { done(null); return }
        done(f.readText())
    }

    override fun pickMbtiles(done: (String?) -> Unit) {
        val d = FileDialog(null as Frame?, "Open MBTiles map", FileDialog.LOAD).apply { file = "*.mbtiles"; isVisible = true }
        done(d.file?.let { File(d.directory, it).absolutePath })
    }

    override fun openMbtiles(path: String): MbTiles? =
        try { if (File(path).isFile) MbTiles(JdbcSqlDriver("jdbc:sqlite:$path", readOnly = true)) else null } catch (_: Exception) { null }

    override suspend fun tileBytes(url: String, offlineOnly: Boolean) = tiles.bytes(url, offlineOnly)
    override fun formatTime(ms: Long) = Formats.time(ms)
    override fun formatDateTime(ms: Long) = Formats.dateTime(ms)
    override fun formatDay(ms: Long) = Formats.day(ms)
    override fun utcOffsetMs(ms: Long) = Formats.utcOffsetMs(ms)
    override fun loadPlaces() = runCatching { Formats.places(DesktopPlatform::class.java.getResourceAsStream("/places/places.tsv.gz")) }.getOrNull()
}

/** Meshtastic over USB serial (115200 8N1), using jSerialComm. */
class SerialLink(private val portName: String) : StreamLinkBase(post) {
    override val kind = LinkKind.SERIAL
    override val description = "USB serial $portName"
    private var port: SerialPort? = null

    override fun openStreams(): Pair<InputStream, OutputStream> {
        val p = SerialPort.getCommPort(portName)
        p.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0)
        if (!p.openPort()) throw IllegalStateException("Could not open $portName (in use by another app, or no permission)")
        port = p
        return p.inputStream to p.outputStream
    }

    /** Same as the official clients: a burst of START2 bytes wakes a sleeping device's UART. */
    override fun afterOpen(out: OutputStream) { out.write(ByteArray(32) { 0xC3.toByte() }); out.flush(); Thread.sleep(100) }

    override fun closeTransport() { port?.closePort() }
}

/** Minimal NMEA 0183 reader: GGA/RMC for position, GST for accuracy. */
class NmeaGps(private val portName: String, private val engine: SurveyEngine, private val onFix: (Fix) -> Unit, private val onStatus: (String) -> Unit) {
    @Volatile private var running = false
    private var port: SerialPort? = null
    private var lastAccuracy: Double? = null
    private var lastAccuracyMs = 0L

    fun start() {
        running = true
        thread(isDaemon = true, name = "nmea") {
            for (baud in intArrayOf(9600, 4800, 38400, 115200)) {
                if (!running) return@thread
                val p = SerialPort.getCommPort(portName)
                p.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
                p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 2000, 0)
                if (!p.openPort()) { post(Runnable { onStatus("Cannot open $portName") }); return@thread }
                port = p
                val reader = p.inputStream.bufferedReader(Charsets.US_ASCII)
                var good = 0
                val deadline = System.currentTimeMillis() + 4000
                try {
                    while (running) {
                        val line = reader.readLine() ?: break
                        if (Nmea.checksumOk(line.trim())) { good++; handle(line) }
                        if (good == 0 && System.currentTimeMillis() > deadline) break
                    }
                } catch (_: Exception) {}
                p.closePort()
                if (good > 0) return@thread
            }
            post(Runnable { onStatus("No NMEA data on $portName") })
        }
    }

    fun stop() { running = false; port?.closePort() }

    private fun handle(line: String) {
        when (val s = Nmea.parse(line)) {
            is Nmea.Sentence.Gst -> { lastAccuracy = s.horizontalSigmaM; lastAccuracyMs = engine.clock.monotonicMs() }
            is Nmea.Sentence.Gga -> {
                val lat = s.lat; val lon = s.lon
                if (!s.isMeasuredFix || lat == null || lon == null) {
                    // quality 0 = no fix; 6 = estimated/dead reckoning (often a stale last position); 7/8 = manual/simulated
                    post(Runnable { onStatus("GPS $portName: no measured fix (quality ${s.quality})") }); return
                }
                val skew = s.utcSecondOfDay?.let { meshsurvey.core.gps.gpsClockSkewS(it, engine.clock.wallMs()) }
                if (skew != null && kotlin.math.abs(skew) > 30) {
                    post(Runnable { onStatus("GPS $portName: receiver time differs from this PC's clock by ${skew.toInt()} s — fix not used (stale data or wrong PC clock)") })
                    return
                }
                val acc = lastAccuracy?.takeIf { engine.clock.monotonicMs() - lastAccuracyMs < 5000 }
                post(Runnable {
                    onFix(Fix(lat, lon, acc, s.altitudeM, engine.clock.wallMs(), engine.clock.monotonicMs(), FixSource.NMEA_SERIAL))
                    onStatus("GPS $portName: fix (sats ${s.satellites ?: "?"}, accuracy ${acc?.let { "±%.0f m".format(it) } ?: "unknown — receiver sends no GST"})")
                })
            }
            null -> {}
        }
    }
}
