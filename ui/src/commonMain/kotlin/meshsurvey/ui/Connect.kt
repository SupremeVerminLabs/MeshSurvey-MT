package meshsurvey.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import meshsurvey.core.radio.LinkKind
import meshsurvey.core.radio.RadioLink
import meshsurvey.core.radio.RadioState

/** Radio connection chooser, used by the start panel and Settings. */
@Composable
fun ConnectionSection(model: UiModel, onConnected: () -> Unit = {}) {
    val engine = model.engine
    val platform = model.platform
    val scope = rememberCoroutineScope()
    val radio by engine.radioStatus.collectAsState()
    val wanted by engine.connectionWanted.collectAsState()
    var options by remember { mutableStateOf<List<ConnectionOption>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("meshtastic.local") }
    var msg by remember { mutableStateOf<String?>(null) }

    fun scan() {
        scanning = true
        scope.launch {
            options = try { platform.discoverConnections() } catch (e: Exception) { msg = e.message; emptyList() }
            scanning = false
        }
    }
    LaunchedEffect(Unit) { scan() }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${radioLabel(radio.state)} ${radio.detail}" + (engine.reconnectInS?.let { " · reconnecting in $it s" } ?: ""), fontSize = 13.sp)
        if (wanted) Button(onClick = { engine.disconnect() }) { Text("Disconnect") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { scan() }, enabled = !scanning) { Text(if (scanning) "Searching…" else "Search again") }
            if (options.any { it.needsHost }) OutlinedTextField(host, { host = it }, label = { Text("Network node host (TCP)") }, singleLine = true,
                modifier = Modifier.width(220.dp))
        }
        options.forEach { o ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${o.kind.label}: ${o.label}", color = if (o.available) Color.Unspecified else Color.Gray)
                    o.note?.let { Text(it, fontSize = 11.sp, color = Color.DarkGray) }
                }
                Button(enabled = o.available && !wanted, onClick = {
                    try {
                        val h = host
                        val peer = model.peerSendsTests
                        val simFactory: () -> RadioLink = {
                            SimSupport.link(engine.clock, { engine.fix.value }, peer, { engine.referenceState.value.session?.refCode })
                        }
                        val realFactory: () -> RadioLink = { platform.openLink(o, h) }
                        if (o.kind != LinkKind.SIMULATED) realFactory() // validate now so errors show immediately
                        engine.connect(if (o.kind == LinkKind.SIMULATED) simFactory else realFactory); msg = null
                        if (model.gpsOption == null || model.gpsStatus.startsWith("Location permission")) {
                            if (o.kind == LinkKind.SIMULATED) model.gpsOptionsSim() else platform.defaultGps()?.let { model.startGps(it) }
                        }
                        onConnected()
                    } catch (e: Exception) { msg = e.message ?: e.toString() }
                }) { Text("Connect") }
            }
        }
        if (radio.state == RadioState.DISCONNECTED && options.isEmpty() && !scanning) Text("No radios found.", fontSize = 12.sp)
        msg?.let { Text(it, color = Color(0xFFC62828), fontSize = 12.sp) }
    }
}

@Composable
fun ConnectDialog(model: UiModel, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("Connect a radio") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) { ConnectionSection(model, onConnected = onClose) } })
}
