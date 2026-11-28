@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package meshsurvey.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import meshsurvey.core.model.TestRole
import meshsurvey.core.radio.RadioState

/**
 * Setup tab: 1 radios, 2 position, 3 what to do. Everything about connecting lives here so the other tabs stay uncluttered.
 */
@Composable
fun SetupScreen(model: UiModel, onSettings: () -> Unit, onChoose: (Tab, TestRole?) -> Unit) {
    var connectOpen by remember { mutableStateOf(false) }
    val radios = activeReceivers(model)
    val fix by model.engine.fix.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Step("1", "Radio") {
            if (radios.isEmpty()) Text("No radio connected.", fontSize = 13.sp, color = Pal.muted)
            radios.forEach { e ->
                val st by e.radioStatus.collectAsState()
                val m by e.radioMetrics.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { model.engine = e }) {
                        Text((if (e === model.engine) "▸ " else "") + e.radioLabel() + " · " + radioLabel(st.state) + batteryShort(m),
                            fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(st.info.preset, st.info.linkKind?.label, st.detail.takeIf { st.state != RadioState.READY })
                            .joinToString(" · "), fontSize = 12.sp, color = Pal.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { model.engine = e; model.disconnectSelected() }) { Text("Disconnect") }
                }
            }
            Button(onClick = { connectOpen = true }) { Text(if (radios.isEmpty()) "Connect radio" else "Connect another radio") }
        }
        Step("2", "Position") {
            Text(fix?.let { gpsLabel(it, model.engine, model) } ?: "No position yet: receptions are logged without one until a fix arrives.", fontSize = 13.sp)
            if (model.gpsStatus.isNotBlank() && model.gpsStatus != "GPS off") Text(model.gpsStatus, fontSize = 12.sp, color = Pal.muted, maxLines = 3,
                overflow = TextOverflow.Ellipsis)
            (listOf(RADIO_GPS_OPTION) + model.platform.gpsOptions()).filter { !it.needsManual }.forEach { g ->
                Row(Modifier.fillMaxWidth().clickable { model.startGps(g) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(model.gpsOption?.id == g.id, { model.startGps(g) })
                    Text(g.label, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = onSettings) { Text("Fixed position and more in Settings") }
        }
        Step("3", "What to do") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { onChoose(Tab.MAP, null) }) { Text("Just listen") }
                OutlinedButton(onClick = { onChoose(Tab.TEST, TestRole.A_SURVEY) }) { Text("Send tests (A)") }
                OutlinedButton(onClick = { onChoose(Tab.TEST, TestRole.B_REFERENCE) }) { Text("Receive tests (B)") }
            }
            Text("Listening never transmits. Tests are short private messages to one radio whose operator takes part.", fontSize = 12.sp, color = Pal.muted)
        }
        OutlinedButton(onClick = onSettings) { Text("Settings") }
    }
    if (connectOpen) ConnectDialog(model) { connectOpen = false }
}

@Composable
private fun Step(n: String, title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$n  $title", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            content()
        }
    }
}
