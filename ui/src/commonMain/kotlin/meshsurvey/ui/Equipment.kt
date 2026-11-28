package meshsurvey.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import meshsurvey.core.model.AntennaType
import meshsurvey.core.model.EquipmentProfile
import meshsurvey.core.model.Mounting
import meshsurvey.core.model.Placement
import meshsurvey.core.proto.MeshEnums

/** Settings section: pick the active profile, create or edit one. Entirely optional. */
@Composable
fun EquipmentSection(model: UiModel) {
    val engine = model.engine
    val all by engine.profiles.collectAsState()
    val own = all.filter { it.originDevice == null }
    val activeId by engine.activeProfileId.collectAsState()
    val radio by engine.radioStatus.collectAsState()
    var editing by remember { mutableStateOf<EquipmentProfile?>(null) }
    val active = own.firstOrNull { it.id == activeId }

    Text(active?.let { "${it.name}: ${it.summary()}" } ?: "None. Surveys work without one; a profile lets you compare equipment later.", fontSize = 13.sp)
    Text("Reported by the radio (recorded with each session, not part of the profile): " +
        listOfNotNull(radio.info.hwModel?.let { "model ${MeshEnums.hwModelName(it)}" }, radio.info.firmware?.let { "firmware $it" },
            radio.info.region, radio.info.preset, radio.info.txPowerDbm?.let { if (it == 0) "TX power: firmware default" else "TX power $it dBm (configured)" })
            .joinToString(" · ").ifEmpty { "connect a radio" }, fontSize = 11.sp, color = Pal.muted)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(activeId == null, { engine.setActiveProfile(null) }, { Text("None") })
        own.forEach { p -> FilterChip(activeId == p.id, { engine.setActiveProfile(p.id) }, { Text(p.name) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { editing = EquipmentProfile(id = newProfileId(), name = "") }) { Text("New profile") }
        if (active != null) OutlinedButton(onClick = { editing = active }) { Text("Edit") }
    }
    Text("Changing the profile while logging starts a new survey session, so every session belongs to one profile.", fontSize = 11.sp, color = Pal.muted)
    editing?.let { p -> ProfileDialog(p, onSave = { engine.saveProfile(it); if (activeId == null) engine.setActiveProfile(it.id); editing = null },
        onDelete = if (own.any { it.id == p.id }) ({ engine.deleteProfile(p.id); editing = null }) else null, onClose = { editing = null }) }
}

private fun newProfileId(): String = (1..16).map { "0123456789abcdef"[kotlin.random.Random.nextInt(16)] }.joinToString("")

@Composable
private fun ProfileDialog(start: EquipmentProfile, onSave: (EquipmentProfile) -> Unit, onDelete: (() -> Unit)?, onClose: () -> Unit) {
    var p by remember { mutableStateOf(start) }
    var gain by remember { mutableStateOf(start.antennaGainDbi?.toString() ?: "") }
    var height by remember { mutableStateOf(start.antennaHeightM?.toString() ?: "") }
    var feedLen by remember { mutableStateOf(start.feedlineLengthM?.toString() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    fun num(s: String): Double? = s.trim().replace(',', '.').ifEmpty { null }?.toDoubleOrNull()

    AlertDialog(onDismissRequest = onClose,
        title = { Text(if (start.name.isEmpty()) "New equipment profile" else "Edit ${start.name}") },
        confirmButton = { TextButton(onClick = {
            if (p.name.isBlank()) { error = "Give the profile a name"; return@TextButton }
            if (gain.isNotBlank() && num(gain) == null || height.isNotBlank() && num(height) == null || feedLen.isNotBlank() && num(feedLen) == null) {
                error = "Numbers only in gain, height and length (leave empty if unknown)"; return@TextButton
            }
            onSave(p.copy(name = p.name.trim(), antennaGainDbi = num(gain), antennaHeightM = num(height), feedlineLengthM = num(feedLen)))
        }) { Text("Save") } },
        dismissButton = { Row {
            onDelete?.let { TextButton(onClick = it) { Text("Delete", color = Pal.bad) } }
            TextButton(onClick = onClose) { Text("Cancel") }
        } },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Everything is optional; leave unknown fields empty. Radio model, firmware, region, preset and transmit power " +
                    "are read from the radio for each session instead.", fontSize = 11.sp, color = Pal.muted)
                @Composable fun field(label: String, v: String?, set: (String?) -> Unit) =
                    OutlinedTextField(v ?: "", { set(it.ifEmpty { null }) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                field("Name (e.g. Rooftop base, Car)", p.name) { p = p.copy(name = it ?: "") }
                field("Radio manufacturer", p.radioMake) { p = p.copy(radioMake = it) }
                field("Radio model", p.radioModel) { p = p.copy(radioModel = it) }
                field("Radio chipset (e.g. SX1262)", p.chipset) { p = p.copy(chipset = it) }
                field("Antenna manufacturer", p.antennaMake) { p = p.copy(antennaMake = it) }
                field("Antenna model", p.antennaModel) { p = p.copy(antennaModel = it) }
                OutlinedTextField(gain, { gain = it }, label = { Text("Antenna gain, dBi (if known)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Antenna", fontSize = 12.sp)
                Choice(p.antennaType, AntennaType.entries) { p = p.copy(antennaType = it) }
                Text("Installation", fontSize = 12.sp)
                Choice(p.placement, Placement.entries) { p = p.copy(placement = it) }
                Choice(p.mounting, Mounting.entries) { p = p.copy(mounting = it) }
                OutlinedTextField(height, { height = it }, label = { Text("Antenna height above ground, m (approx.)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                field("Feedline type (e.g. LMR-400)", p.feedlineType) { p = p.copy(feedlineType = it) }
                OutlinedTextField(feedLen, { feedLen = it }, label = { Text("Feedline length, m (approx.)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                field("Enclosure", p.enclosure) { p = p.copy(enclosure = it) }
                field("Notes (surroundings, obstructions…)", p.notes) { p = p.copy(notes = it) }
                error?.let { Text(it, color = Pal.bad, fontSize = 12.sp) }
            }
        })
}

@Composable
private fun <T : Enum<T>> Choice(value: T?, options: List<T>, set: (T?) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(value == null, { set(null) }, { Text("Unknown") })
        options.forEach { o -> FilterChip(value == o, { set(o) }, { Text(o.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
    }
}
