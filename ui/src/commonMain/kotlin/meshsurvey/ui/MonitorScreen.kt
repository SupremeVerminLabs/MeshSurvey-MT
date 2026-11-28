package meshsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import meshsurvey.core.map.Scales
import meshsurvey.core.model.RxStatus
import meshsurvey.core.proto.MeshEnums
import meshsurvey.core.radio.FreqSource
import meshsurvey.core.radio.FrequencyCalc

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MonitorScreen(model: UiModel) {
    val engine = model.engine
    val radio by engine.radioStatus.collectAsState()
    val stats by engine.stats.collectAsState()
    val recent by engine.recent.collectAsState()
    val fix by engine.fix.collectAsState()
    val nodes by engine.nodes.collectAsState()
    val events by engine.events.collectAsState()
    var showEvents by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val receivers by model.station.receivers.collectAsState()
    var allRadios by remember { mutableStateOf(true) }
    // With several radios, show every radio's receptions merged (newest first), each row tagged with radio and preset.
    val merged = if (receivers.size > 1 && allRadios) {
        val lists = receivers.map { e -> e to e.recent.collectAsState().value }
        lists.flatMap { (e, l) -> l.map { e to it } }.sortedByDescending { it.second.rxMs }.take(300)
    } else recent.map { engine to it }
    val info = radio.info

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                // Always shown: one status line and one summary line. Everything else behind "Details".
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${radioLabel(radio.state)} · ${radio.detail}", fontWeight = FontWeight.Bold, maxLines = 2, modifier = Modifier.weight(1f))
                    TextButton(onClick = { details = !details }) { Text(if (details) "Less" else "Details") }
                }
                val m0 by engine.radioMetrics.collectAsState()
                Text(listOfNotNull(info.preset, info.myNodeNum?.let { MeshEnums.nodeId(it) }, "logged ${stats.logged}",
                    "with position ${stats.withPosition}", m0?.batteryText?.let { "power $it" }).joinToString(" · "), fontSize = 13.sp)
                if (details) {
                Text("Node ${info.myNodeNum?.let { MeshEnums.nodeId(it) } ?: "?"} · ${info.linkKind?.label ?: "no link"} · firmware ${info.firmware ?: "?"}")
                val fq = remember(info) { FrequencyCalc.from(info) }
                Text("Preset ${info.preset ?: "?"} · region ${info.region ?: "?"} · ${fq.params?.label ?: "LoRa parameters unknown"}")
                Text("Frequency: ${fq.label}", fontSize = 13.sp)
                if (fq.source == FreqSource.CALCULATED) Text("Calculated by this app from region, preset, slot and channel name using the firmware formula; " +
                    "the radio does not report its frequency.", fontSize = 11.sp, color = Pal.muted)
                Text("Position: ${fix?.let { "${fmt(it.lat, 5)}, ${fmt(it.lon, 5)} · ${gpsLabel(it, engine)} · ${it.source}" } ?: "no fix (receptions are logged without position)"} · ${model.gpsStatus}")
                Text("Logged ${stats.logged} · with position ${stats.withPosition} · encrypted ${stats.encrypted} · queued/late ${stats.queuedLate} · " +
                    "skipped: own ${stats.skippedOwn}, via MQTT ${stats.skippedMqtt}", fontSize = 13.sp)
                val m by engine.radioMetrics.collectAsState()
                val timing by engine.timing.collectAsState()
                Text("Reported by radio: " + (m?.let { "transmitting ${it.airUtilTxPct?.let { v -> fmt(v, 1) } ?: "?"} %, channel busy ${it.channelUtilPct?.let { v -> fmt(v, 1) } ?: "?"} %" }
                    ?: "no airtime report yet") + " · rejected GPS fixes ${stats.rejectedFixes}", fontSize = 13.sp)
                Text("Power (reported by radio): " + (m?.batteryText?.let { "$it · as of ${model.platform.formatTime(m!!.wallMs)}" }
                    ?: "not reported yet (sent with the radio's device telemetry, typically every 30–60 min)"), fontSize = 13.sp)
                if (timing.tickGaps > 0 || timing.clockJumps > 0) Text("Timing: app suspended ${timing.tickGaps}× (longest ${timing.longestGapMs / 1000} s), " +
                    "clock changed ${timing.clockJumps}× — recorded with the data", fontSize = 12.sp, color = Pal.warn)
                Text("Stock firmware drops duplicate copies before they reach the app: each row is the first copy your node received. " +
                    "Duplicate receptions are not observed and are not estimated.", fontSize = 11.sp, color = Pal.muted)
                }
            }
        }
        FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text((if (LocalCompact.current) "Live" else "Live receptions") + " (${merged.size})", fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterVertically).padding(end = 8.dp))
            if (activeReceivers(model).size > 1) TextButton(onClick = { allRadios = !allRadios }) {
                Text(if (allRadios) "Only ${engine.radioLabel()}" else "All radios")
            }
            TextButton(onClick = { showEvents = !showEvents }) { Text(if (showEvents) "Packets" else "Event log (${events.size})") }
        }
        if (showEvents) {
            LazyColumn(Modifier.weight(1f)) { items(events) { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace) } }
        } else {
          // The table keeps its column widths and scrolls sideways in narrow windows instead of squeezing or overflowing.
          val multi = activeReceivers(model).size > 1
          val compact = LocalCompact.current
          // Phone width: the columns that matter (who, what, how strong) fit without scrolling; preset/relay/position move to Details.
          val cols = if (compact) listOfNotNull("time" to 68, if (multi) "radio" to 50 else null, "from" to 74, "type" to 80, "RSSI" to 40, "SNR" to 52, "hops" to 34)
              else listOfNotNull("time" to 72, if (multi) "radio" to 70 else null, "preset" to 100, "from" to 120, "type" to 104, "RSSI" to 48,
              "SNR" to 52, "hops" to 44, "relay" to 46, "pos" to 34)
          Box(Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState())) {
           Column(Modifier.width((cols.sumOf { it.second } + 8).dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                cols.forEach { (h, w) -> Text(h, fontSize = 11.sp, color = Pal.muted, modifier = Modifier.width(w.dp)) }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(merged) { (rxEngine, o) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        val name = nodes[o.fromNode]?.shortName ?: rxEngine.nodes.value[o.fromNode]?.shortName ?: MeshEnums.nodeId(o.fromNode)
                        Cell(model.platform.formatTime(o.rxMs), if (compact) 68 else 72)
                        if (multi) Cell(rxEngine.radioLabel(), if (compact) 50 else 70)
                        if (!compact) Cell(o.preset ?: "?", 100)
                        Cell(name, if (compact) 74 else 120)
                        Cell(o.type.name.lowercase().let { if (compact) it.take(9) else it } + if (o.status == RxStatus.QUEUED_LATE) if (compact) "*" else " (late)" else "", if (compact) 80 else 104)
                        Cell(o.rssi?.toString() ?: "?", if (compact) 40 else 48)
                        val snrBg = o.snr?.let { argb(Scales.snr(it)) } ?: Color.Gray
                        Text(o.snr?.let { fmt(it, 1) } ?: "?", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = readableOn(snrBg),
                            modifier = Modifier.width(48.dp).background(snrBg, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp))
                        Box(Modifier.width(4.dp))
                        Cell(o.hopsAway?.toString() ?: "?", if (compact) 34 else 44)
                        if (!compact) {
                            Cell(o.relayByte?.let { it.toString(16).padStart(2, '0') } ?: "–", 46)
                            Cell(if (o.lat != null) "✓" else "–", 34)
                        }
                    }
                    HorizontalDivider(color = Pal.divider)
                }
            }
           }
          }
        }
    }
}

@Composable
private fun Cell(s: String, w: Int) = Text(s, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.width(w.dp))
