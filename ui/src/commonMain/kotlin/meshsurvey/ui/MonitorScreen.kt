package meshsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
    val info = radio.info

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text("${radioLabel(radio.state)} · ${radio.detail}", fontWeight = FontWeight.Bold)
                Text("Node ${info.myNodeNum?.let { MeshEnums.nodeId(it) } ?: "?"} · ${info.linkKind?.label ?: "no link"} · firmware ${info.firmware ?: "?"}")
                val fq = remember(info) { FrequencyCalc.from(info) }
                Text("Preset ${info.preset ?: "?"} · region ${info.region ?: "?"} · ${fq.params?.label ?: "LoRa parameters unknown"}")
                Text("Frequency: ${fq.label}", fontSize = 13.sp)
                if (fq.source == FreqSource.CALCULATED) Text("Calculated by this app from region, preset, slot and channel name using the firmware formula; " +
                    "the radio does not report its frequency.", fontSize = 11.sp, color = Color.DarkGray)
                Text("Position: ${fix?.let { "${fmt(it.lat, 5)}, ${fmt(it.lon, 5)} · ${gpsLabel(it, engine)} · ${it.source}" } ?: "no fix (receptions are logged without position)"} · ${model.gpsStatus}")
                Text("Logged ${stats.logged} · with position ${stats.withPosition} · encrypted ${stats.encrypted} · queued/late ${stats.queuedLate} · " +
                    "skipped: own ${stats.skippedOwn}, via MQTT ${stats.skippedMqtt}", fontSize = 13.sp)
                val m by engine.radioMetrics.collectAsState()
                val timing by engine.timing.collectAsState()
                Text("Reported by radio: " + (m?.let { "transmitting ${it.airUtilTxPct?.let { v -> fmt(v, 1) } ?: "?"} %, channel busy ${it.channelUtilPct?.let { v -> fmt(v, 1) } ?: "?"} %" }
                    ?: "no airtime report yet") + " · rejected GPS fixes ${stats.rejectedFixes}", fontSize = 13.sp)
                if (timing.tickGaps > 0 || timing.clockJumps > 0) Text("Timing: app suspended ${timing.tickGaps}× (longest ${timing.longestGapMs / 1000} s), " +
                    "clock changed ${timing.clockJumps}× — recorded with the data", fontSize = 12.sp, color = Color(0xFFE65100))
                Text("Stock firmware drops duplicate copies before they reach the app: each row is the first copy your node received. " +
                    "Duplicate receptions are not observed and are not estimated.", fontSize = 11.sp, color = Color.DarkGray)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Live receptions (${recent.size})", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { showEvents = !showEvents }) { Text(if (showEvents) "Show packets" else "Show event log (${events.size})") }
        }
        if (showEvents) {
            LazyColumn(Modifier.weight(1f)) { items(events) { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace) } }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                listOf("time" to 72, "from" to 120, "type" to 104, "RSSI" to 48, "SNR" to 52, "hops" to 44, "relay" to 46, "pos" to 34).forEach { (h, w) ->
                    Text(h, fontSize = 11.sp, color = Color.Gray, modifier = Modifier.width(w.dp))
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(recent) { o ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        val name = nodes[o.fromNode]?.shortName ?: MeshEnums.nodeId(o.fromNode)
                        Cell(model.platform.formatTime(o.rxMs), 72)
                        Cell(name, 120)
                        Cell(o.type.name.lowercase() + if (o.status == RxStatus.QUEUED_LATE) " (late)" else "", 104)
                        Cell(o.rssi?.toString() ?: "?", 48)
                        Text(o.snr?.let { fmt(it, 1) } ?: "?", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.White,
                            modifier = Modifier.width(52.dp).background(o.snr?.let { argb(Scales.snr(it)) } ?: Color.Gray, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp))
                        Cell(o.hopsAway?.toString() ?: "?", 44)
                        Cell(o.relayByte?.let { it.toString(16).padStart(2, '0') } ?: "–", 46)
                        Cell(if (o.lat != null) "✓" else "–", 34)
                    }
                    HorizontalDivider(color = Color(0x11000000))
                }
            }
        }
    }
}

@Composable
private fun Cell(s: String, w: Int) = Text(s, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.width(w.dp))
