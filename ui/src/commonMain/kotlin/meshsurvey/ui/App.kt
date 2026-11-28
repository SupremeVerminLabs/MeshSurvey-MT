package meshsurvey.ui

import meshsurvey.core.radio.MeshProtocol

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import meshsurvey.core.engine.SurveyEngine
import meshsurvey.core.engine.Station
import meshsurvey.core.proto.MeshEnums
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.clickable
import meshsurvey.core.model.Fix
import meshsurvey.core.model.FixSource
import meshsurvey.core.model.Source
import meshsurvey.core.radio.RadioState

enum class Tab(val label: String) { MAP("Map"), MONITOR("Monitor"), HISTORY("History"), TEST("Test") }

/** UI-wide state that outlives tab switches. */
class UiModel(val station: Station, val platform: PlatformServices, scope: CoroutineScope) {
    /** The radio the screens currently show (each connected radio has its own engine). */
    var engine by mutableStateOf(station.primary)
    /** Which connection option each engine was connected with, to refuse connecting the same radio twice. */
    val connectedOption = mutableStateMapOf<SurveyEngine, String>()
    val tiles = TileLoader(platform, scope)
    val map = MapState()
    var mapFilter by mutableStateOf(MapFilter())
    var gpsOption by mutableStateOf<GpsOption?>(null)
    var gpsStatus by mutableStateOf("GPS off")
    var peerSendsTests by mutableStateOf(false)
    var centeredOnce = false
    /** Dark by default; stored with the app's settings. */
    var theme by mutableStateOf(station.store.getMeta("theme")?.let { t -> ThemeMode.entries.firstOrNull { it.name == t } } ?: ThemeMode.DARK)
    fun updateTheme(t: ThemeMode) { theme = t; station.store.setMeta("theme", t.name) }
    private val reportedDrawErrors = HashSet<String>()
    /** Map drawing failures go to the event log once each (they are bugs to report, not to hide). */
    fun reportDrawError(e: Throwable) {
        val key = "${e::class.simpleName}: ${e.message}".take(200)
        if (reportedDrawErrors.add(key)) engine.event("Map drawing error (layer skipped, please report): $key")
    }

    /** Parsed coverage predictions by stored id (parsing a large GeoJSON on every map refresh would be wasteful). */
    private val predictionCache = HashMap<Long, meshsurvey.core.map.PredictionMap?>()
    fun prediction(id: Long): meshsurvey.core.map.PredictionMap? = predictionCache.getOrPut(id) {
        engine.store.predictionGeoJson(id)?.let { meshsurvey.core.map.PredictionMap.parse(it, "prediction").first }
    }
    fun forgetPrediction(id: Long) { predictionCache.remove(id) }

    private val placesLazy by lazy { platform.loadPlaces() }

    /** Community contributions (real data only: the station's real database, never the simulation database). */
    val contributions by lazy { meshsurvey.core.community.Contributions(station.store, station.primary.appVersion) }
    var communityStatus by mutableStateOf("")
    /** Manual community-map refresh requests (Legend → Refresh), and what the map last reported. */
    var communityRefresh by mutableStateOf(0)
    var communityRefreshSeen = 0
    var communityMapInfo by mutableStateOf("")
    /** Session waiting for the end-of-session "contribute?" prompt. */
    var promptSession by mutableStateOf<String?>(null)
    fun localDay(ms: Long) = platform.formatDateTime(ms).take(10)
    fun ownNodes(): Set<Long> = station.receivers.value.mapNotNull { it.radio.info.myNodeNum }.toSet()
    /** Uploads or resumes one session; blocking network, so call from a background coroutine. */
    fun uploadSession(id: String): String = contributions.upload(id, ::localDay, ownNodes(), station.clock.wallMs()) { u, b -> platform.httpPost(u, b) }
        .also { m -> communityStatus = m; engine.event("Community: $m") }
    /** Offline place names, loaded once on first use (call off the UI thread). */
    fun places(): meshsurvey.core.history.Places? = placesLazy
    /** The start panel stays open until a radio is connected and the user picked what to do. */
    var startOpen by mutableStateOf(true)

    fun startGps(opt: GpsOption, manual: Triple<Double, Double, Double?>? = null) {
        platform.stopGps()
        station.receivers.value.forEach { it.radioGps.stop() }
        gpsOption = opt
        // The station has one position; it applies to every connected radio. Radio GPS uses the selected radio's GPS
        // (or the first radio that reports a position).
        if (opt.id == RADIO_GPS_ID) {
            val src = engine.takeIf { it.radioPosition.value != null } ?: station.receivers.value.firstOrNull { it.radioPosition.value != null } ?: engine
            radioGpsEngine = src
            src.radioGps.start({ station.onFix(it) }, { gpsStatus = (if (station.receivers.value.size > 1) "${src.radioLabel()}: " else "") + it })
        } else platform.startGps(opt, manual, { station.onFix(it) }, { gpsStatus = it })
    }

    /** The radio whose GPS is the station position (when radio GPS is chosen). */
    var radioGpsEngine: SurveyEngine? = null

    fun stopGps() { platform.stopGps(); station.receivers.value.forEach { it.radioGps.stop() }; gpsOption = null; gpsStatus = "GPS off" }

    /** Disconnects the selected radio; additional radios are removed, the first one stays as the empty slot. */
    fun disconnectSelected() {
        val e = engine
        connectedOption.remove(e)
        station.removeReceiver(e)
        if (e !== station.primary) engine = station.receivers.value.lastOrNull { it.connectionWanted.value } ?: station.primary
    }
}

/**
 * @param runTickLoop true when the UI owns the engine's 1 Hz tick (desktop). Android runs the
 * tick from its Application/foreground service instead so logging survives the activity.
 */
@Composable
fun MeshSurveyApp(station: Station, platform: PlatformServices, runTickLoop: Boolean, initialTab: Tab = Tab.MAP,
                  initialSettings: Boolean = false, onModel: (UiModel) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val model = remember { UiModel(station, platform, scope).also(onModel) }
    val engine = model.engine
    var tab by remember { mutableStateOf(initialTab) }
    var showSettings by remember { mutableStateOf(initialSettings) }

    // 1 Hz engine tick (heartbeats, test scheduling, ACK timeouts, simulator) on the UI thread.
    if (runTickLoop) LaunchedEffect(Unit) {
        while (true) { delay(1000); station.tick() }
    }

    // Background logging stays on while any radio is connected; radio GPS is picked up from whichever radio reports a position.
    LaunchedEffect(Unit) {
        var last: Boolean? = null
        while (true) {
            val any = station.anyWanted()
            if (any != last) { platform.onSurveyActive(any); last = any }
            if (model.gpsOption == null && station.receivers.value.any { it.radioPosition.value != null }) model.startGps(RADIO_GPS_OPTION)
            delay(1000)
        }
    }
    LaunchedEffect(Unit) { if (model.gpsOption == null) platform.defaultGps()?.let { model.startGps(it) } }
    // End of a real session: offer to contribute it (only if the user wants to be asked and it has not been decided).
    val receiversNow by station.receivers.collectAsState()
    receiversNow.filter { !station.isSimulated(it) }.forEach { e ->
        val ended by e.lastEndedSession.collectAsState()
        LaunchedEffect(ended) {
            val id = ended ?: return@LaunchedEffect
            val c = model.contributions
            if (c.settings.destination != meshsurvey.core.community.Destination.OFF && c.settings.promptAfterSession && !c.settings.autoUpload &&
                c.status(id) == null && model.promptSession == null) model.promptSession = id
        }
    }
    // Auto-upload (only when the user gave the standing permission in Settings), off the UI thread.
    if (runTickLoop) LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            // only with the user's standing permission; one session per minute at most, failed ones wait for their retry time
            model.contributions.autoCandidates(station.clock.wallMs()).firstOrNull()?.let { id ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { model.uploadSession(id) }
            }
        }
    }
    // No position source chosen (typical for a Windows base station) and the radio knows where it is:
    // use the radio's own position automatically. Android keeps the phone GPS, which suits driving.

    MeshSurveyTheme(model.theme, isSystemInDarkTheme()) {
        Surface(Modifier.fillMaxSize()) {
          BoxWithConstraints(Modifier.fillMaxSize()) {
           // Phone-width layout below 600 dp (Android phones, narrow desktop windows): fewer labels, panels collapsed.
           CompositionLocalProvider(LocalCompact provides (maxWidth < 600.dp)) {
            Column(Modifier.fillMaxSize()) {
                TopBar(model, onSettings = { showSettings = true }, onStart = { model.startOpen = true })
                PresetBanner(model)
                if (model.startOpen) StartPanel(model) { t, role ->
                    tab = t
                    role?.let { engine.ab.setRole(it) }
                    model.startOpen = false
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        Tab.MAP -> MapScreen(model)
                        Tab.MONITOR -> MonitorScreen(model)
                        Tab.HISTORY -> HistoryScreen(model)
                        Tab.TEST -> TestScreen(model)
                    }
                }
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(selected = tab == t, onClick = { tab = t },
                            icon = { Text(if (tab == t) "●" else "○") }, label = { Text(t.label) })
                    }
                }
            }
            if (showSettings) SettingsDialog(model) { showSettings = false }
            model.promptSession?.let { id -> ContributionDialog(model, id, afterSession = true) { model.promptSession = null } }
           }
          }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopBar(model: UiModel, onSettings: () -> Unit, onStart: () -> Unit) {
    val engine = model.engine
    val radio by engine.radioStatus.collectAsState()
    val fix by engine.fix.collectAsState()
    val simulated = engine.source() == Source.SIMULATED || fix?.source == FixSource.SIMULATED
    val pal = LocalPalette.current
    val compact = LocalCompact.current
    Row(Modifier.fillMaxWidth().background(pal.bar).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.Top) {
        // Status chips wrap onto a second line in narrow windows instead of running off the screen.
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!compact) Text("MeshSurvey ${engine.edition.name.lowercase().replaceFirstChar { it.uppercase() }}", color = pal.onBar,
                fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.align(Alignment.CenterVertically))
            val receivers = activeReceivers(model)
            val pm1 by engine.radioMetrics.collectAsState()
            if (receivers.size <= 1) Chip(radioLabel(radio.state) + (if (radio.info.protocol == MeshProtocol.MESHCORE) " · MeshCore" else "") + batteryShort(pm1), radioColor(radio.state))
            else receivers.forEach { e ->
                val st by e.radioStatus.collectAsState()
                val pm by e.radioMetrics.collectAsState()
                // one chip per radio; ▸ marks the one shown in the screens below; tap to switch
                Text((if (e === engine) "▸ " else "") + "${e.radioLabel()} · ${radioLabel(st.state)}" + batteryShort(pm), color = Color.White, fontSize = 12.sp,
                    maxLines = 1, modifier = Modifier.background(radioColor(st.state), RoundedCornerShape(10.dp)).clickable { model.engine = e }
                        .padding(horizontal = 8.dp, vertical = 2.dp))
            }
            Chip(gpsLabel(fix, engine, model), if (fix != null) ChipColors.good else ChipColors.idle)
            if (simulated) Chip("SIMULATED", ChipColors.alarm)
            val rxOnly by model.station.shared.rxOnly.collectAsState()
            // Always visible: whether this app may transmit. Tap to change (with an explanation in Settings).
            Text(if (rxOnly) "RX-only" else if (compact) "TX on" else "TX allowed", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.background(if (rxOnly) ChipColors.good else ChipColors.busy, RoundedCornerShape(10.dp))
                    .clickable { model.station.setRxOnly(!rxOnly) }.padding(horizontal = 8.dp, vertical = 2.dp))
            val tx by engine.appTx.collectAsState()
            if (tx.count > 0) Chip("App sent ${tx.count}", ChipColors.busy)
        }
        if (!model.startOpen) TextButton(onClick = onStart, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) { Text("Start", color = pal.onBar) }
        TextButton(onClick = onSettings, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) { Text(if (compact) "⚙" else "Settings", color = pal.onBar) }
    }
}

@Composable
fun Chip(text: String, color: Color) {
    val c = LocalCompact.current
    Text(text, color = Color.White, fontSize = if (c) 11.sp else 12.sp, maxLines = 1,
        modifier = Modifier.background(color, RoundedCornerShape(10.dp)).padding(horizontal = if (c) 6.dp else 8.dp, vertical = 2.dp))
}

fun radioLabel(s: RadioState) = when (s) {
    RadioState.DISCONNECTED -> "No radio"
    RadioState.CONNECTING -> "Connecting…"
    RadioState.DOWNLOADING_CONFIG -> "Syncing…"
    RadioState.READY -> "Logging"
    RadioState.FAILED -> "Radio error"
}

fun radioColor(s: RadioState) = when (s) {
    RadioState.READY -> ChipColors.good
    RadioState.FAILED -> ChipColors.bad
    RadioState.DISCONNECTED -> ChipColors.idle
    else -> ChipColors.busy
}

fun gpsLabel(fix: Fix?, engine: SurveyEngine, model: UiModel? = null): String {
    fix ?: return "No GPS"
    val age = (engine.logger.fixAgeMs(fix) ?: 0) / 1000
    if (age > 10) return "GPS stale ${age}s"
    return when (fix.source) {
        // A stationary radio's GPS: accuracy comes only with its position broadcasts, so say what is known instead of "unknown".
        FixSource.RADIO -> {
            val g = (model?.radioGpsEngine ?: engine).radioGps
            "Radio GPS " + (g.shortAccuracy ?: fix.accuracyM?.let { "±${it.toInt()} m" } ?: g.satellites?.let { "· $it sats" } ?: "· accuracy pending")
        }
        FixSource.MANUAL -> "Fixed position" + (fix.accuracyM?.let { " ±${it.toInt()} m" } ?: "")
        else -> "GPS " + (fix.accuracyM?.let { "±${it.toInt()} m" } ?: "accuracy not reported")
    }
}

/** Always visible while a temporary preset change is open, until the radio is confirmed back on its original config. */
@Composable
fun PresetBanner(model: UiModel) {
    val engine = model.engine
    val change by engine.presetChange.collectAsState()
    val radio by engine.radioStatus.collectAsState()
    val c = change ?: return
    var msg by remember { mutableStateOf<String?>(null) }
    val thisRadio = radio.info.myNodeNum == c.nodeNum
    val pal = LocalPalette.current
    Row(Modifier.fillMaxWidth().background(pal.banner).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Temporary test preset ${c.targetPreset} (normally ${c.originalPreset ?: "?"}) on ${meshsurvey.core.proto.MeshEnums.nodeId(c.nodeNum)}: " +
            c.status.name.lowercase().replace('_', ' ') + (c.note?.let { " — $it" } ?: "") + (msg?.let { " · $it" } ?: ""),
            fontSize = 13.sp, color = pal.onBanner, modifier = Modifier.weight(1f))
        if (thisRadio && radio.state == RadioState.READY && c.status != meshsurvey.core.model.PresetChangeStatus.RESTORING)
            TextButton(onClick = { msg = engine.presets.restore() ?: "restore sent" }) { Text("Restore original now") }
        else if (!thisRadio) Text("Connect that radio to restore", fontSize = 12.sp, color = pal.onBanner)
    }
}

/**
 * Three steps, no reading required: connect a radio, check GPS, choose what to do.
 * "Just listen" is the default: passive logging needs nothing else.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun StartPanel(model: UiModel, onChoose: (Tab, meshsurvey.core.model.TestRole?) -> Unit) {
    val engine = model.engine
    val radio by engine.radioStatus.collectAsState()
    val wanted by engine.connectionWanted.collectAsState()
    val fix by engine.fix.collectAsState()
    var connectOpen by remember { mutableStateOf(false) }
    val ready = radio.state == RadioState.READY
    // On a phone the panel gets out of the way once a radio is logging ("Start" in the top bar brings it back).
    val compactNow = LocalCompact.current
    LaunchedEffect(ready, compactNow) { if (ready && compactNow) model.startOpen = false }
    androidx.compose.material3.Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1  Radio", fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp))
                if (!wanted) androidx.compose.material3.Button(onClick = { connectOpen = true }) { Text("Connect radio") }
                else Text(if (ready) "Connected · ${radio.info.preset ?: ""}" else radioLabel(radio.state) + (engine.reconnectInS?.let { " (retry in $it s)" } ?: ""))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2  GPS", fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp))
                Text(if (fix != null) gpsLabel(fix, engine, model) else model.gpsStatus + " — receptions are logged without position until a fix arrives",
                    fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("3  Do", fontWeight = FontWeight.Bold, modifier = Modifier.width(80.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                androidx.compose.material3.OutlinedButton(enabled = wanted, onClick = { onChoose(Tab.MAP, null) }) { Text("Just listen") }
                androidx.compose.material3.OutlinedButton(enabled = wanted, onClick = { onChoose(Tab.TEST, meshsurvey.core.model.TestRole.A_SURVEY) }) { Text("Send tests (A)") }
                androidx.compose.material3.OutlinedButton(enabled = wanted, onClick = { onChoose(Tab.TEST, meshsurvey.core.model.TestRole.B_REFERENCE) }) { Text("Receive tests (B)") }
                }
            }
            Text("Listening never transmits. Tests are short private messages to one radio whose operator takes part.",
                fontSize = 11.sp, color = Pal.muted)
        }
    }
    if (connectOpen) ConnectDialog(model) { connectOpen = false }
}


/** Short name for a radio in chips and labels: its node id, or its connection while unknown. */
fun SurveyEngine.radioLabel(): String =
    if (radio.info.protocol == MeshProtocol.MESHCORE) (radio.info.nodeName?.take(14) ?: "MeshCore") + " (MC)"
    else radio.info.myNodeNum?.let { n -> store.nodeNames()[n]?.shortName?.takeIf { it.isNotBlank() } ?: MeshEnums.nodeId(n) } ?: "radio"

/** " · 87 %", " · ⚡" (external power), " · 4.05 V" (MeshCore reports voltage only), or "". */
fun batteryShort(m: meshsurvey.core.radio.RadioMetrics?): String = when {
    m == null -> ""
    m.batteryLevel != null -> if (m.batteryLevel!! > 100) " · ⚡" else " · ${m.batteryLevel} %"
    m.voltage != null && m.voltage!! > 0 -> " · ${fmt(m.voltage!!, 2)} V"
    else -> ""
}

/** Radios in use (connected or reconnecting). The always-present first engine is left out while it has no radio. */
@Composable
fun activeReceivers(model: UiModel): List<SurveyEngine> {
    val all by model.station.receivers.collectAsState()
    return all.filter { it.connectionWanted.collectAsState().value || it.radioStatus.collectAsState().value.state != RadioState.DISCONNECTED }
}

/** True in phone-width layouts (below 600 dp). */
val LocalCompact = staticCompositionLocalOf { false }
