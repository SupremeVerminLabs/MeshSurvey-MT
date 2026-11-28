package meshsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import meshsurvey.core.history.DayRecord
import meshsurvey.core.history.History
import meshsurvey.core.proto.MeshEnums
import kotlin.math.ln
import kotlin.math.max

/**
 * Past survey days, newest first, each named by its date and the place it mostly covered. Opening a day shows the map for
 * that day only (tracks, coverage, receptions), with the same layers, filters and prediction comparison as the Map tab.
 */
@Composable
fun HistoryScreen(model: UiModel) {
    var days by remember { mutableStateOf<List<DayRecord>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf<DayRecord?>(null) }
    var sessionsOpen by remember { mutableStateOf(false) }
    val store = model.engine.store

    LaunchedEffect(store, refresh) {
        days = null
        // reading every row and naming places takes a moment: off the UI thread
        days = withContext(Dispatchers.Default) {
            History.days(store.track(), store.historyObservations(), model.platform::utcOffsetMs, model.places())
        }
    }
    // the day window applies only inside History
    DisposableEffect(Unit) { onDispose { model.mapFilter = model.mapFilter.copy(window = null) } }

    val d = open
    if (d != null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(Pal.overlay).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { open = null; model.mapFilter = model.mapFilter.copy(window = null) }) { Text("‹ Days") }
                Column(Modifier.weight(1f)) {
                    Text(dayTitle(model, d), fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                    Text(daySummary(model, d), fontSize = 11.sp, color = Pal.muted, maxLines = 2)
                }
                if (!d.simulated) TextButton(onClick = { sessionsOpen = true }) { Text("Sessions") }
            }
            if (sessionsOpen) androidx.compose.material3.AlertDialog(onDismissRequest = { sessionsOpen = false },
                confirmButton = { TextButton(onClick = { sessionsOpen = false }) { Text("Close") } },
                title = { Text("Sessions on this day") },
                text = {
                    Column(Modifier.androidxVerticalScroll()) {
                        SessionContributions(model, store.sessions().filter { it.startedMs <= d.lastMs && (it.endedMs ?: Long.MAX_VALUE) >= d.firstMs })
                    }
                })
            MapScreen(model)
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Survey history", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { refresh++ }) { Text("Refresh") }
        }
        val list = days
        when {
            list == null -> Text("Reading the log…", color = Pal.muted)
            list.isEmpty() -> Text("Nothing logged yet. Connect a radio and survey; each day then appears here.", color = Pal.muted)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list) { day ->
                    Card(Modifier.fillMaxWidth().clickable {
                        open = day
                        model.mapFilter = model.mapFilter.copy(window = day.firstMs..day.lastMs, range = TimeRange.ALL)
                        day.bbox?.let { b -> model.map.centerOn((b[0] + b[1]) / 2, (b[2] + b[3]) / 2, zoomFor(b)) }
                        model.centeredOnce = true
                    }) {
                        Column(Modifier.padding(10.dp)) {
                            Text(dayTitle(model, day), fontWeight = FontWeight.Bold)
                            Text(daySummary(model, day), fontSize = 12.sp, color = Pal.muted)
                            if (day.places.size > 1) Text("Also: " + day.places.drop(1).take(4).joinToString(", ") { it.first }, fontSize = 11.sp, color = Pal.muted)
                        }
                    }
                }
            }
        }
        Text("Place names: GeoNames (CC BY 4.0), looked up offline.", fontSize = 10.sp, color = Pal.muted)
    }
}

private fun dayTitle(model: UiModel, d: DayRecord) =
    model.platform.formatDay(d.firstMs) + " · " + History.title(d) + if (d.simulated) " · SIMULATED" else ""

private fun daySummary(model: UiModel, d: DayRecord): String = listOfNotNull(
    "${model.platform.formatTime(d.firstMs).take(5)}–${model.platform.formatTime(d.lastMs).take(5)}",
    "${d.receptions} receptions" + if (d.receptions > 0) " (${d.withPosition} with position)" else "",
    "${d.nodesHeard} nodes",
    d.bestSnr?.let { "best SNR ${fmt(it, 1)} dB" },
    if (d.distanceKm >= 0.1) "${fmt(d.distanceKm, 1)} km travelled" else null,
    d.presets.takeIf { it.isNotEmpty() }?.joinToString("/"),
    d.receivers.takeIf { it.size > 1 }?.let { "${it.size} radios" } ?: d.receivers.singleOrNull()?.let { MeshEnums.nodeId(it) },
    if ("meshcore" in d.protocols) (if (d.protocols.size > 1) "Meshtastic + MeshCore" else "MeshCore") else null,
).joinToString(" · ")

/** Map zoom that fits a bounding box (latMin, latMax, lonMin, lonMax) in roughly 600 px. */
private fun zoomFor(b: DoubleArray): Double {
    val span = max(b[3] - b[2], (b[1] - b[0]) * 1.4).coerceAtLeast(0.002)
    return (ln(600.0 / 256 * 360 / span) / ln(2.0) - 0.3).coerceIn(3.0, 16.0)
}

@Composable
private fun Modifier.androidxVerticalScroll(): Modifier = this.heightIn(max = 480.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())
