package meshsurvey.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import meshsurvey.core.community.ContributionStatus
import meshsurvey.core.community.Destination
import meshsurvey.core.community.PrivacyZone
import meshsurvey.core.community.SessionSummary
import meshsurvey.core.model.SurveySession

/**
 * Contribute one session: shown after a session ends (if the user wants to be asked) and from the session lists.
 * Shows what would be shared, lets the user set privacy zones first, and uploads only when they press Upload.
 */
@Composable
fun ContributionDialog(model: UiModel, sessionId: String, afterSession: Boolean, onClose: () -> Unit) {
    val c = model.contributions
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(c.settings) }
    var sum by remember { mutableStateOf<SessionSummary?>(null) }
    var showRows by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings) {
        sum = withContext(Dispatchers.Default) { c.summary(sessionId, model::localDay, model.ownNodes(), model.station.clock.wallMs()) }
    }
    val s = sum
    AlertDialog(onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (afterSession) "Session finished" else "Contribute session") },
        confirmButton = {
            if (result == null && s != null && s.ineligible == null && settings.zonesConfirmed)
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        result = withContext(Dispatchers.Default) { model.uploadSession(sessionId) }
                        busy = false
                    }
                }) { Text(if (busy) "Uploading…" else "Upload") }
            else TextButton(onClick = onClose) { Text("Close") }
        },
        dismissButton = {
            if (result == null && !busy) Row {
                TextButton(onClick = { c.keepLocal(sessionId, model.station.clock.wallMs()); onClose() }) { Text("Keep local only") }
                if (afterSession) TextButton(onClick = {
                    c.update(c.settings.copy(promptAfterSession = false)); c.keepLocal(sessionId, model.station.clock.wallMs()); onClose()
                }) { Text("Don't ask again") }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (s == null) { Text("Preparing…", color = Pal.muted); return@Column }
                val mins = s.durationMs / 60_000
                Info2("Session", "${model.platform.formatDateTime(s.session.startedMs)} · ${if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"} · ${s.session.preset ?: "?"}")
                Info2("Receptions", "${s.observations} logged (all stay saved on this device)")
                Info2("Would share", "${s.rows.size} squares (${s.cells} map cells)" + if (s.flaggedRows > 0) " · ${s.flaggedRows} with quality flags (kept out of trusted community statistics until reviewed)" else "")
                Info2("Left out", "${s.excluded.total} receptions: ${s.excluded.homeZone} in privacy zones, ${s.excluded.noPosition} without position, " +
                    "${s.excluded.late} late, ${s.excluded.mock} mock GPS, ${s.excluded.simulatedOrImported} simulated/unknown" +
                    if (s.flaggedLeftOut > 0) " · ${s.flaggedLeftOut} flagged squares (sharing of flagged squares is off)" else "")
                Info2("Equipment", s.equipment.entries.joinToString(", ") { "${it.key.replace('_', ' ')} ${it.value}" }.ifEmpty { "none (choose in Settings → Community)" })
                Info2("Destination", s.destination)
                s.ineligible?.let { Text("Cannot be shared: $it", color = Pal.warn, fontSize = 13.sp) }
                if (!settings.zonesConfirmed) {
                    Text("Before your first contribution: set a privacy zone around places you don't want on the map (home, work). " +
                        "Receptions inside a zone are never shared.", fontSize = 12.sp, color = Pal.warn)
                    ZoneEditor(model) { settings = it }
                }
                TextButton(onClick = { showRows = !showRows }) { Text(if (showRows) "Hide preview" else "Preview the data") }
                if (showRows) {
                    Text("Each row: square centre, date, preset, heard from, packets (direct), SNR median/best, RSSI median, flags. " +
                        "Not sent: times, exact positions, device or own radio IDs, names, message contents.", fontSize = 11.sp, color = Pal.muted)
                    s.rows.take(40).forEach { r ->
                        Text("${r.lat},${r.lon} ${r.day} ${r.preset} ${r.heardFrom} n=${r.n}(${r.nDirect}) ${r.snrMedian ?: "?"}/${r.snrBest ?: "?"} ${r.rssiMedian ?: "?"} ${r.flags.joinToString(";")}",
                            fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    if (s.rows.size > 40) Text("… ${s.rows.size - 40} more", fontSize = 11.sp, color = Pal.muted)
                }
                result?.let { Text(it, fontWeight = FontWeight.Bold) }
            }
        })
}

@Composable
private fun Info2(label: String, value: String) {
    Row { Text(label, fontSize = 12.sp, color = Pal.muted, modifier = Modifier.padding(end = 6.dp).fillMaxWidth(0.28f)); Text(value, fontSize = 13.sp, modifier = Modifier.weight(1f)) }
}

/** Privacy zones: add at the current position, choose a radius, remove; or explicitly continue without one. */
@Composable
fun ZoneEditor(model: UiModel, onChange: (meshsurvey.core.community.ShareSettings) -> Unit = {}) {
    val c = model.contributions
    var s by remember { mutableStateOf(c.settings) }
    fun set(n: meshsurvey.core.community.ShareSettings) { s = n; c.update(n); onChange(n) }
    var radius by remember { mutableStateOf(250.0) }
    var label by remember { mutableStateOf("Home") }
    s.zones.forEachIndexed { i, z ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${z.label}: ${z.radiusM.toInt()} m around ${fmt(z.lat, 3)}, ${fmt(z.lon, 3)}", fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { set(s.copy(zones = s.zones.filterIndexed { j, _ -> j != i })) }) { Text("Remove") }
        }
    }
    val fix = model.engine.fix.collectAsState().value
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(label, { label = it.take(20) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
        listOf(250.0, 500.0, 1000.0).forEach { r -> FilterChip(radius == r, { radius = r }, { Text("${r.toInt()} m", fontSize = 11.sp) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(enabled = fix != null, onClick = { fix?.let { set(s.copy(zones = s.zones + PrivacyZone(it.lat, it.lon, radius, label.ifBlank { "Zone" }), zonesConfirmed = true)) } }) {
            Text(if (fix != null) "Add zone here" else "Add zone (no position yet)")
        }
        if (!s.zonesConfirmed) TextButton(onClick = { set(s.copy(zonesConfirmed = true)) }) { Text("Continue without a zone") }
    }
}

/** Sessions with their contribution status for the current destination, with Upload / Withdraw. */
@Composable
fun SessionContributions(model: UiModel, sessions: List<SurveySession>) {
    val c = model.contributions
    var version by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val statuses = remember(version, sessions) { c.statuses() }
    val counts = remember(version, sessions) { model.station.store.observationCountsBySession() }
    sessions.forEach { s ->
        val st = statuses[s.id]
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${model.platform.formatDateTime(s.startedMs)} · ${s.preset ?: "?"} · ${counts[s.id] ?: 0} receptions", fontSize = 12.sp)
                val simulated = s.source != meshsurvey.core.model.Source.LOCAL_RF
                Text(when {
                    simulated -> "Excluded: not real-radio data"
                    st == null -> "Not uploaded"
                    else -> st.status.label + listOfNotNull(st.uploadedMs?.let { " ${model.platform.formatDateTime(it)}" },
                        if (st.status == ContributionStatus.UPLOADED || st.status == ContributionStatus.PARTIAL) " · ${st.acceptedCells} squares accepted" else null,
                        st.error?.let { " · $it" }).joinToString("")
                } + " · ${c.settings.destinationLabel}", fontSize = 11.sp, color = if (st?.status == ContributionStatus.FAILED) Pal.warn else Pal.muted, maxLines = 2)
            }
            val active = s.status == meshsurvey.core.model.SessionStatus.ACTIVE && s.originDevice == null
            when {
                active -> Text("recording", fontSize = 11.sp, color = Pal.ok)
                s.source != meshsurvey.core.model.Source.LOCAL_RF || c.settings.destination == Destination.OFF -> {}
                st?.status == ContributionStatus.UPLOADED -> TextButton(onClick = {
                    scope.launch {
                        val r = withContext(Dispatchers.Default) { c.withdraw(s.id, model.station.clock.wallMs()) { u, b -> model.platform.httpPost(u, b) } }
                        model.communityStatus = r; version++
                    }
                }) { Text("Withdraw", color = Pal.bad) }
                st?.status == ContributionStatus.WITHDRAWN -> {}
                else -> TextButton(onClick = { open = s.id }) { Text(if (st?.status == ContributionStatus.PARTIAL || st?.status == ContributionStatus.FAILED) "Resume…" else "Upload…") }
            }
        }
    }
    open?.let { id -> ContributionDialog(model, id, afterSession = false) { open = null; version++ } }
}

/** Settings → Community. */
@Composable
fun CommunitySettings(model: UiModel) {
    val c = model.contributions
    var s by remember { mutableStateOf(c.settings) }
    fun set(n: meshsurvey.core.community.ShareSettings) { s = n; c.update(n) }
    val scope = rememberCoroutineScope()
    var odd by remember { mutableStateOf("") }
    Text("Your data is always saved on this device. Contributing shares anonymous summaries (≈37 m squares, by day) only when you " +
        "choose: after a session, or from a session's Upload button. Never: times, exact positions, device or radio IDs, names, messages, simulated data.",
        fontSize = 11.sp, color = Pal.muted)
    Text("Destination", fontWeight = FontWeight.Bold, fontSize = 13.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(s.destination == Destination.DEFAULT, { set(s.copy(destination = Destination.DEFAULT)) }, { Text("MeshSurvey community") })
        FilterChip(s.destination == Destination.CUSTOM, { set(s.copy(destination = Destination.CUSTOM)) }, { Text("Custom") })
        FilterChip(s.destination == Destination.OFF, { set(s.copy(destination = Destination.OFF)) }, { Text("Off") })
    }
    if (s.destination == Destination.CUSTOM) OutlinedTextField(s.customEndpoint, { set(s.copy(customEndpoint = it.trim())) },
        label = { Text("Compatible sheet address (Apps Script web-app URL)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (s.destination != Destination.OFF) {
        SwitchLine("Show community coverage on the map (download only)", s.downloadMap) { set(s.copy(downloadMap = it)) }
        SwitchLine("Ask after each finished session whether to contribute it", s.promptAfterSession) { set(s.copy(promptAfterSession = it)) }
        SwitchLine("Upload finished sessions automatically (standing permission)", s.autoUpload) { set(s.copy(autoUpload = it)) }
        if (s.autoUpload) Text("Automatic upload sends each finished real session without asking. Failed uploads wait 30 min to 24 h before trying again.",
            fontSize = 11.sp, color = Pal.warn)
        Text("Privacy zones", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        ZoneEditor(model) { s = it }
        Text("Optional details (general categories only)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        SwitchLine("Radio model (as reported by the radio)", s.shareRadioModel) { set(s.copy(shareRadioModel = it)) }
        SwitchLine("Antenna gain category (from your equipment profile)", s.shareAntennaCategory) { set(s.copy(shareAntennaCategory = it)) }
        SwitchLine("Internal or external antenna", s.shareAntennaInternalExternal) { set(s.copy(shareAntennaInternalExternal = it)) }
        SwitchLine("Installation (indoor/outdoor, vehicle/fixed…)", s.shareInstallation) { set(s.copy(shareInstallation = it)) }
        SwitchLine("Configured transmit power", s.shareTxPower) { set(s.copy(shareTxPower = it)) }
        SwitchLine("Include squares with quality flags (reviewed before they count)", s.includeFlagged) { set(s.copy(includeFlagged = it)) }
        if (model.communityStatus.isNotBlank()) Text("Last: ${model.communityStatus}", fontSize = 12.sp, color = Pal.muted)
        OutlinedTextField(odd, { odd = it }, label = { Text("Report something odd (sent with today's date and your rough position)") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(enabled = odd.isNotBlank(), onClick = {
            val fx = model.engine.fix.value; val text = odd
            scope.launch {
                val r = withContext(Dispatchers.Default) {
                    c.reportOddity(text, fx?.lat, fx?.lon, model.localDay(model.station.clock.wallMs())) { u, b -> model.platform.httpPost(u, b) }
                }
                model.communityStatus = r; if (r == "Report sent") odd = ""
            }
        }) { Text("Send report") }
    }
}

@Composable
private fun SwitchLine(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f)); Switch(on, set) }
}
