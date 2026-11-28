package meshsurvey.ui

import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.launch
import meshsurvey.core.export.Bundle
import meshsurvey.core.export.Csv
import meshsurvey.core.export.GeoJson
import meshsurvey.core.radio.LinkKind
import meshsurvey.core.radio.RadioLink
import meshsurvey.core.radio.RadioState

@Composable
fun SettingsDialog(model: UiModel, onClose: () -> Unit) {
    val engine = model.engine
    val platform = model.platform
    val scope = rememberCoroutineScope()
    val radio by engine.radioStatus.collectAsState()
    var msg by remember { mutableStateOf<String?>(null) }
    var manualLat by remember { mutableStateOf("") }
    var manualLon by remember { mutableStateOf("") }
    var manualAcc by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var summaries by remember { mutableStateOf(engine.logger.config.storeSummaries) }
    var open by remember { mutableStateOf<String?>(null) }


    AlertDialog(onDismissRequest = onClose, confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
        title = { Text("Settings") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                msg?.let { Text(it, color = Pal.warn, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp)) }
                Fold("Appearance", model.theme.label, open, { open = it }) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.entries.forEach { t -> androidx.compose.material3.FilterChip(model.theme == t, { model.updateTheme(t) }, { Text(t.label) }) }
                }
                }
                Fold("Radios", activeReceivers(model).joinToString { it.radioLabel() }.ifEmpty { "no radio connected" }, open, { open = it }) {
                ConnectionSection(model)
                }
                Fold("Position source", (model.gpsOption?.label ?: "off") + " · " + gpsLabel(engine.fix.collectAsState().value, engine, model), open, { open = it }) {
                Text(model.gpsStatus, fontSize = 13.sp)
                (listOf(RADIO_GPS_OPTION) + platform.gpsOptions()).forEach { g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.label); if (model.gpsOption?.id == g.id) g.note?.let { Text(it, fontSize = 11.sp, color = Pal.muted) }
                        }
                        Button(onClick = {
                            val manual = if (g.needsManual) {
                                val la = manualLat.toDoubleOrNull(); val lo = manualLon.toDoubleOrNull()
                                if (la == null || lo == null) { msg = "Enter latitude and longitude"; return@Button }
                                Triple(la, lo, manualAcc.toDoubleOrNull())
                            } else null
                            model.startGps(g, manual)
                        }) { Text(if (model.gpsOption?.id == g.id) "Active" else "Use") }
                    }
                    if (g.needsManual) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(manualLat, { manualLat = it }, label = { Text("Lat") }, singleLine = true, modifier = Modifier.width(120.dp))
                        OutlinedTextField(manualLon, { manualLon = it }, label = { Text("Lon") }, singleLine = true, modifier = Modifier.width(120.dp))
                        OutlinedTextField(manualAcc, { manualAcc = it }, label = { Text("± m (optional)") }, singleLine = true, modifier = Modifier.width(130.dp))
                    }
                }
                if (model.gpsOption?.id == RADIO_GPS_ID) (model.radioGpsEngine ?: engine).radioGps.heldPosition()?.let { h ->
                    OutlinedButton(onClick = {
                        manualLat = fmt(h.lat, 6); manualLon = fmt(h.lon, 6)
                        platform.gpsOptions().firstOrNull { it.needsManual }?.let { g -> model.startGps(g, Triple(h.lat, h.lon, null)) }
                        engine.event("Fixed reference position confirmed by the user from the radio's reported position ${fmt(h.lat, 6)}, ${fmt(h.lon, 6)}")
                    }) { Text("Confirm as fixed reference position") }
                    Text("Copies the radio's current position into a fixed position (source MANUAL) that you have confirmed; " +
                        "the confirmation is recorded in the event log.", fontSize = 11.sp, color = Pal.muted)
                }
                if (model.gpsOption != null) OutlinedButton(onClick = { model.stopGps() }) { Text("Stop GPS") }
                }
                Fold("Equipment profile", engine.profiles.collectAsState().value.firstOrNull { it.id == engine.activeProfileId.collectAsState().value }?.name ?: "none", open, { open = it }) {
                EquipmentSection(model)
                }
                Fold("Transmissions (RX-only)", (if (model.station.shared.rxOnly.collectAsState().value) "RX-only" else "tests allowed") + " · ${engine.appTx.collectAsState().value.count} sent", open, { open = it }) {
                val rxOnly by model.station.shared.rxOnly.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("RX-only mode", modifier = Modifier.weight(1f))
                    Switch(rxOnly, { model.station.setRxOnly(it) })
                }
                Text("On: this app sends nothing on any connected radio (no tests, no temporary preset changes). " +
                    "Restoring a radio's original settings stays possible. Radios still transmit by themselves as configured " +
                    "(node info, telemetry, position, relaying others' packets): that is firmware behaviour, not this app.", fontSize = 11.sp, color = Pal.muted)
                val tx by engine.appTx.collectAsState()
                Text("This session: ${tx.count} transmission(s) requested by the app on ${engine.radioLabel()}, " +
                    "estimated ${fmt(tx.estAirtimeMs / 1000, 1)} s airtime (first transmissions only; ACK retries can add up to 2 more each).", fontSize = 12.sp)
                }
                Fold("Background logging", "screen-off logging, battery", open, { open = it }) {
                Text("While a radio is connected, logging continues with the screen locked (Android shows a notification). " +
                    "Automated test sessions stop when the app leaves the screen.", fontSize = 12.sp)
                OutlinedButton(onClick = { if (!platform.openBatterySettings()) msg = "Not needed on this platform" }) {
                    Text("Battery settings (allow unrestricted background use)")
                }
                }
                Fold("Logging", if (summaries) "packet summaries on" else "packet summaries off", open, { open = it }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(summaries, { summaries = it; engine.setLoggerConfig(engine.logger.config.copy(storeSummaries = it)) })
                    Text("  Store decoded summaries of non-text packets (telemetry, node info). Off by default. Text message contents are never stored.",
                        fontSize = 12.sp)
                }
                }
                Fold("Map tiles", if (model.tiles.offlineOnly) "offline only" else model.tiles.attribution, open, { open = it }) {
                OutlinedTextField(model.tiles.urlTemplate, { model.tiles.urlTemplate = it; model.tiles.clearMemory() },
                    label = { Text("Tile URL template ({z}/{x}/{y}); file:// for a local tile folder") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(model.tiles.offlineOnly, { model.tiles.offlineOnly = it; model.tiles.clearMemory() })
                    Text("  Offline only (use cached / local tiles, no network)", fontSize = 12.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        platform.pickMbtiles { path ->
                            if (path == null) return@pickMbtiles
                            val m = platform.openMbtiles(path)
                            when {
                                m == null -> msg = "Could not open $path as MBTiles"
                                !m.isRaster -> { msg = m.describe(); m.close() }
                                else -> {
                                    model.tiles.urlTemplate = "mbtiles:$path#{z}/{x}/{y}"
                                    model.tiles.attribution = m.attribution ?: m.name
                                    model.tiles.clearMemory()
                                    msg = "Using offline map: ${m.describe()}"
                                    m.bounds?.let { b -> model.map.centerOn((b[1] + b[3]) / 2, (b[0] + b[2]) / 2, (m.minZoom ?: 10).toDouble().coerceAtLeast(10.0)) }
                                    m.close()
                                }
                            }
                        }
                    }) { Text("Use MBTiles file…") }
                    OutlinedButton(onClick = {
                        model.tiles.urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
                        model.tiles.attribution = "© OpenStreetMap contributors"; model.tiles.clearMemory()
                    }) { Text("Online OSM tiles") }
                }
                Text("Viewed online tiles are cached for offline use. Bulk downloading from tile.openstreetmap.org is not allowed by its usage policy. " +
                    "For offline areas use a raster MBTiles file (PNG/JPEG/WebP; vector .pbf MBTiles are not supported), your own tile server, " +
                    "or a local file:// {z}/{x}/{y} folder.", fontSize = 11.sp, color = Pal.muted)
                }
                Fold("Community", model.contributions.settings.let { c -> if (c.destination == meshsurvey.core.community.Destination.OFF) "off"
                    else c.destinationLabel + if (c.autoUpload) " · auto upload" else " · upload when you choose" }, open, { open = it }) {
                    CommunitySettings(model)
                }
                Fold("Data", "export, import, sessions, delete", open, { open = it }) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { platform.saveText("meshsurvey-observations.csv", "text/csv", Csv.observations(engine.store.observations())) { msg = it?.let { p -> "Saved $p" } } }) { Text("CSV") }
                    OutlinedButton(onClick = { platform.saveText("meshsurvey-tests.csv", "text/csv", Csv.tests(engine.store.testTx())) { msg = it?.let { p -> "Saved $p" } } }) { Text("Tests CSV") }
                    OutlinedButton(onClick = { platform.saveText("meshsurvey.geojson", "application/geo+json",
                        GeoJson.export(engine.store.observations(), engine.store.track(), engine.store.testTx())) { msg = it?.let { p -> "Saved $p" } } }) { Text("GeoJSON") }
                    OutlinedButton(onClick = { platform.saveText("meshsurvey-${engine.deviceId}.bundle.json", "application/json",
                        Bundle.export(engine.store, engine.deviceId, engine.appVersion, engine.clock.wallMs())) { msg = it?.let { p -> "Saved $p" } } }) { Text("JSON bundle") }
                }
                OutlinedButton(onClick = { platform.openText { t -> if (t != null) {
                    val r = Bundle.import(t, engine.store, engine.deviceId, engine.clock.wallMs())
                    msg = r.error ?: if (r.duplicateFile) "Already imported" else "Imported ${r.observations} observations, ${r.track} track points, ${r.testTx} tests, ${r.testRx} test receptions"
                } } }) { Text("Import JSON bundle…") }
                SessionList(model)
                var contrib by remember { mutableStateOf(false) }
                TextButton(onClick = { contrib = !contrib }) { Text(if (contrib) "Hide community status" else "Community upload status…") }
                if (contrib) SessionContributions(model, model.station.store.sessions().take(30))
                if (!confirmDelete) TextButton(onClick = { confirmDelete = true }) { Text("Delete all logged data…", color = Pal.bad) }
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Delete everything except registered devices?", modifier = Modifier.weight(1f), fontSize = 12.sp)
                    TextButton(onClick = { engine.store.clearAll(); confirmDelete = false; msg = "All logged data deleted" }) { Text("Delete", color = Pal.bad) }
                    TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                }
                }
                Fold("About", "${engine.appVersion} · schema v${engine.store.schemaVersion()}", open, { open = it }) {
                Text("MeshSurvey ${engine.edition} ${engine.appVersion} on ${platform.platformName} · device id ${engine.deviceId} · database schema v${engine.store.schemaVersion()}", fontSize = 12.sp)
                Text("Passive by default: this app only transmits when you start a controlled test to a device you registered. " +
                    "Map colours show measurements only; nothing is interpolated.", fontSize = 12.sp, color = Pal.muted)
                }
            }
        })
}

/** One settings group: a header line with the current state; tapping opens it (one group open at a time). */
@Composable
private fun Fold(title: String, summary: String, open: String?, setOpen: (String?) -> Unit, content: @Composable () -> Unit) {
    val isOpen = open == title
    HorizontalDivider(color = Pal.divider)
    Row(Modifier.fillMaxWidth().clickable { setOpen(if (isOpen) null else title) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold)
            if (!isOpen) Text(summary, fontSize = 12.sp, color = Pal.muted, maxLines = 1)
        }
        Text(if (isOpen) "▾" else "▸", color = Pal.muted)
    }
    if (isOpen) Column(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

/** Selects the simulated GPS when the simulated radio is connected and no GPS is active. */
fun UiModel.gpsOptionsSim() { platform.gpsOptions().firstOrNull { it.id == SIM_GPS_ID }?.let { startGps(it) } }

const val SIM_GPS_ID = "sim"

/** Recent survey sessions of the shown radio's database, each deletable on its own (with confirmation). */
@Composable
private fun SessionList(model: UiModel) {
    val store = model.engine.store
    var open by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }
    var confirm by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide sessions" else "Sessions…") }
    if (!open) return
    val sessions = remember(version) { store.sessions().take(30) }
    val counts = remember(version) { store.observationCountsBySession() }
    if (sessions.isEmpty()) Text("No sessions yet.", fontSize = 12.sp, color = Pal.muted)
    sessions.forEach { s ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${model.platform.formatDateTime(s.startedMs)} · ${s.preset ?: "?"} · ${counts[s.id] ?: 0} receptions", fontSize = 12.sp)
                Text(listOfNotNull(s.receiverNode?.let { meshsurvey.core.proto.MeshEnums.nodeId(it) }, s.protocol.takeIf { it != "meshtastic" },
                    s.status.name.lowercase(), s.originDevice?.let { "imported from $it" }, if (s.source == meshsurvey.core.model.Source.SIMULATED) "SIMULATED" else null)
                    .joinToString(" · "), fontSize = 11.sp, color = Pal.muted)
            }
            if (s.status == meshsurvey.core.model.SessionStatus.ACTIVE && s.originDevice == null) Text("logging", fontSize = 11.sp, color = Pal.ok)
            else if (confirm == s.id) {
                TextButton(onClick = { store.deleteSession(s.id); confirm = null; version++ }) { Text("Delete", color = Pal.bad) }
                TextButton(onClick = { confirm = null }) { Text("Keep") }
            } else TextButton(onClick = { confirm = s.id }) { Text("Delete…", color = Pal.bad) }
        }
    }
}

