package meshsurvey.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import meshsurvey.core.abtest.CompareResult
import meshsurvey.core.abtest.Outcome
import meshsurvey.core.abtest.SessionCompare
import meshsurvey.core.export.Bundle
import meshsurvey.core.model.DeliveryMode
import meshsurvey.core.model.TestDevice
import meshsurvey.core.model.TestRole
import meshsurvey.core.preset.PresetPlan
import meshsurvey.core.proto.MeshEnums
import meshsurvey.core.radio.FrequencyCalc
import meshsurvey.core.radio.Presets
import meshsurvey.core.radio.RadioState
import meshsurvey.core.radio.TestBudget

@Composable
fun TestScreen(model: UiModel) {
    val engine = model.engine
    val ab by engine.abState.collectAsState()
    val radio by engine.radioStatus.collectAsState()

    if (radio.info.protocol == meshsurvey.core.radio.MeshProtocol.MESHCORE) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${engine.radioLabel()} is a MeshCore radio", fontWeight = FontWeight.Bold)
            Text("MeshCore radios are listen-only in this version: the app logs what the radio hears and maps it, but sends nothing " +
                "and never changes the radio's settings. A/B tests and preset changes are Meshtastic only. MeshCore trace tests may come later.")
            Text("Select a Meshtastic radio in the top bar to run tests.", fontSize = 12.sp, color = Color.DarkGray)
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Passive logging is always on. Nothing below transmits until you press a send/start button.", fontSize = 12.sp, color = Color.DarkGray)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Role:", fontWeight = FontWeight.Bold)
            FilterChip(ab.role == TestRole.A_SURVEY, { engine.ab.setRole(TestRole.A_SURVEY) }, { Text("Device A · survey (sends)") })
            FilterChip(ab.role == TestRole.B_REFERENCE, { engine.ab.setRole(TestRole.B_REFERENCE) }, { Text("Device B · reference (listens)") })
        }
        if (ab.role == TestRole.A_SURVEY) {
            TargetCard(model)
            SurveyRole(model, radio.state == RadioState.READY)
        } else ReferenceRole(model)
        var more by remember { mutableStateOf(false) }
        TextButton(onClick = { more = !more }) { Text(if (more) "Hide more options" else "More options (test on another preset, compare logs)") }
        if (more) { PresetCard(model); CompareCard(model) }
    }
}

/**
 * Choose Device B and establish its participation: either B's operator started a reference session and
 * read out its 6-digit code, or B is a stock radio the operator of A controls (ACK mode only).
 */
@Composable
private fun TargetCard(model: UiModel) {
    val engine = model.engine
    val ab by engine.abState.collectAsState()
    val nodes by engine.nodes.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    val remembered = remember(refresh) { engine.store.testDevices() }
    var idText by remember { mutableStateOf("") }
    var codeText by remember { mutableStateOf("") }
    var stockMode by remember { mutableStateOf(false) }
    var ownsB by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Device B", fontWeight = FontWeight.Bold)
            ab.peer?.let { p ->
                Text("Testing ${nodes[p]?.longName ?: remembered.firstOrNull { it.nodeNum == p }?.label ?: ""} ${MeshEnums.nodeId(p)} · " + when (ab.participation) {
                    meshsurvey.core.model.Participation.CODE -> "participation code ${ab.refCode}"
                    meshsurvey.core.model.Participation.OPERATOR_CONTROLS_B -> "stock radio you control (participation not verifiable)"
                    null -> "participation not set"
                }, color = if (ab.peerConfirmed) Color(0xFF2E7D32) else Color(0xFFC62828))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(idText, { idText = it }, label = { Text("B's node ID, e.g. !a1b2c3d4") }, singleLine = true, modifier = Modifier.width(220.dp))
                Menu("Pick heard node", nodes.values.filter { it.num != engine.radio.info.myNodeNum }.take(40).map { n ->
                    "${n.shortName ?: "?"} ${n.longName ?: ""} ${MeshEnums.nodeId(n.num)}" to { idText = MeshEnums.nodeId(n.num) }
                })
                if (remembered.isNotEmpty()) Menu("Remembered", remembered.map { d -> "${d.label} ${MeshEnums.nodeId(d.nodeNum)}" to { idText = MeshEnums.nodeId(d.nodeNum) } })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!stockMode, { stockMode = false }, { Text("B runs MeshSurvey") })
                FilterChip(stockMode, { stockMode = true }, { Text("B is a stock radio I control") })
            }
            if (!stockMode) {
                OutlinedTextField(codeText, { codeText = it.filter(Char::isDigit).take(6) }, label = { Text("B's 6-digit participation code") },
                    singleLine = true, modifier = Modifier.width(220.dp))
                Text("On Device B: Test → Receive tests → Start. B shows the code; its operator tells you (in person, phone, text).",
                    fontSize = 11.sp, color = Color.DarkGray)
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(ownsB, { ownsB = it })
                Text("I own or control this radio. (ACK mode only; B's firmware acknowledges, so its participation can't be verified.)", fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = if (stockMode) ownsB else codeText.length == 6, onClick = {
                    val n = MeshEnums.parseNodeId(idText)
                    err = when {
                        n == null -> "Not a valid node ID"
                        n == engine.radio.info.myNodeNum -> "That is the radio connected to this app"
                        stockMode -> { engine.ab.setPeer(n, confirmed = true); null }
                        else -> engine.ab.setTargetWithCode(n, codeText.toInt())
                    }
                }) { Text("Use as Device B") }
                OutlinedButton(enabled = ab.peer != null, onClick = {
                    val p = ab.peer!!
                    engine.store.upsertTestDevice(TestDevice(p, nodes[p]?.longName ?: MeshEnums.nodeId(p), engine.clock.wallMs())); refresh++
                }) { Text("Remember") }
                err?.let { Text(it, color = Color(0xFFC62828), fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun SurveyRole(model: UiModel, ready: Boolean) {
    val engine = model.engine
    val ab by engine.abState.collectAsState()
    val radio by engine.radioStatus.collectAsState()
    var interval by remember { mutableStateOf(engine.ab.config.defaultIntervalS.toString()) }
    var duration by remember { mutableStateOf((engine.ab.config.defaultDurationS / 60).toString()) }
    var err by remember { mutableStateOf<String?>(null) }
    val budget = remember(radio.info, ab.mode) { engine.ab.budget(ab.mode) }
    val fq = remember(radio.info) { FrequencyCalc.from(radio.info) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Device A — controlled tests", fontWeight = FontWeight.Bold)
            val rxOnly by model.station.shared.rxOnly.collectAsState()
            if (rxOnly) Text("RX-only mode is on: tests are blocked. Turn it off in Settings (or tap the RX-only chip) to send tests.",
                fontSize = 12.sp, color = Color(0xFFC62828))
            Text("Every test is a real transmission on a shared channel that others rely on. Use the longest spacing that answers your " +
                "question, stop when you have enough, and avoid busy presets. With ACK requested the radio may send up to 3 times.",
                fontSize = 12.sp, color = Color(0xFF8D6E00))
            Text("Tests run on ${radio.info.preset ?: "preset ?"} · ${fq.label}", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(ab.mode == DeliveryMode.ACKED, { err = engine.ab.setMode(DeliveryMode.ACKED) }, { Text("ACK requested") })
                FilterChip(ab.mode == DeliveryMode.DIRECT_ONLY, { err = engine.ab.setMode(DeliveryMode.DIRECT_ONLY) }, { Text("Direct only (no relay, no ACK)") })
                if (ab.mode == DeliveryMode.ACKED) Menu("Hop limit: ${ab.hopLimit ?: "radio default (${radio.info.hopLimit ?: "?"})"}",
                    listOf<Pair<String, () -> Unit>>("Radio default" to { err = engine.ab.setMode(DeliveryMode.ACKED, null) }) +
                        (1..7).map { h -> "$h" to { err = engine.ab.setMode(DeliveryMode.ACKED, h) } })
            }
            Text(if (ab.mode == DeliveryMode.ACKED)
                "One direct message with an ACK request. The firmware makes up to 3 transmissions (initial + 2 retries, the last one flooded) " +
                    "and relays may rebroadcast it. Works with a Device B that is not running this app."
            else "One direct message with hop limit 0: exactly one transmission, never relayed, no ACK. Only Device B's own MeshSurvey log " +
                    "can confirm reception — use this when B runs MeshSurvey for the least airtime.", fontSize = 12.sp, color = Color.DarkGray)
            budget?.let { b ->
                Text("Estimated airtime of these tests (this radio's test packets only, calculated, not measured): ~${b.airtimePerTransmissionMs.toInt()} ms per " +
                    "transmission, up to ${b.worstCaseMsPerTest.toInt()} ms per test. Spacing ≥ ${b.minIntervalS} s; ≤ ${b.maxTestsPerSession} tests per session. " +
                    "Relays rebroadcasting tests add airtime that cannot be estimated here.", fontSize = 12.sp)
            } ?: Text("LoRa parameters unknown — connect the radio to compute airtime limits.", fontSize = 12.sp, color = Color(0xFFC62828))
            val m by engine.radioMetrics.collectAsState()
            Text(m?.let { "Reported by your radio (all its traffic, its own averaging window): transmitting ${it.airUtilTxPct?.let { v -> fmt(v, 1) } ?: "?"} % · " +
                "channel busy ${it.channelUtilPct?.let { v -> fmt(v, 1) } ?: "?"} % (all nodes it hears), as of ${model.platform.formatTime(it.wallMs)}" }
                ?: "Your radio has not reported its airtime yet (it does so with its device telemetry).", fontSize = 12.sp)
            Button(enabled = ready && ab.peer != null && ab.peerConfirmed && !ab.autoRunning && ab.cooldownRemainingS == 0,
                onClick = { err = engine.ab.sendSingle() }) {
                Text(if (ab.cooldownRemainingS > 0) "Wait ${ab.cooldownRemainingS} s" else "Send one test")
            }
            HorizontalDivider()
            Text("Timed session — finite; stops on its own, on disconnect, when the app is backgrounded, or with STOP", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(interval, { interval = it.filter(Char::isDigit) }, label = { Text("Interval s (≥ ${budget?.minIntervalS ?: 30})") },
                    singleLine = true, modifier = Modifier.width(170.dp), enabled = !ab.autoRunning)
                OutlinedTextField(duration, { duration = it.filter(Char::isDigit) }, label = { Text("Duration min (≤ ${engine.ab.config.maxDurationS / 60})") },
                    singleLine = true, modifier = Modifier.width(170.dp), enabled = !ab.autoRunning)
                if (!ab.autoRunning) Button(enabled = ready && ab.peer != null && ab.peerConfirmed, onClick = {
                    err = engine.ab.startAuto(interval.toIntOrNull() ?: 0, (duration.toIntOrNull() ?: 0) * 60)
                }) { Text("Start session") }
                else Button(onClick = { engine.ab.stop() }) { Text("STOP NOW") }
            }
            if (ab.autoRunning) Text("Running every ${ab.effectiveIntervalS} s · next in ${ab.nextSendInS ?: "?"} s · ends in " +
                (ab.remainingS?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" } ?: "?"), color = Color(0xFF2E7D32))
            (err ?: ab.message)?.let { Text(it, fontSize = 12.sp) }
            Text("This session: sent ${ab.sent} · delivered (destination ACK) ${ab.delivered}" + (if (ab.lateAcks > 0) " (${ab.lateAcks} late)" else "") +
                " · relay observed only ${ab.relayObserved} · rejected by B ${ab.rejected} · no ACK after retries ${ab.noAck} · " +
                "not transmitted ${ab.notTransmitted} · unknown ${ab.unknown}", fontSize = 13.sp)
            Text("“Sent” = handed to your radio; if the channel is busy it may transmit later. ACKs that arrive late are still matched.",
                fontSize = 11.sp, color = Color.DarkGray)
            Text("Only “delivered” (or B's own log) proves reception. “Relay observed” means a retransmission was heard; “no ACK” means delivery is unknown.",
                fontSize = 11.sp, color = Color.DarkGray)
            ab.session?.let { s ->
                val txs = remember(ab) { engine.store.testTx(s.id).takeLast(20).reversed() }
                txs.forEach { t ->
                    Text("#${t.seq} ${model.platform.formatTime(t.sentMs)} ${t.preset ?: ""} ${t.status}${t.routingError?.let { " ($it)" } ?: ""} · " +
                        (if (t.lat != null) "GPS ±${t.accuracyM?.let { fmt(it, 0) } ?: "?"} m" else "no fresh GPS fix"),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

/** Manual preset selection for testing. Nothing is changed without reviewing the full plan and confirming. */
@Composable
private fun PresetCard(model: UiModel) {
    val engine = model.engine
    val radio by engine.radioStatus.collectAsState()
    val change by engine.presetChange.collectAsState()
    var plan by remember { mutableStateOf<PresetPlan?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Test preset", fontWeight = FontWeight.Bold)
            Text("Radio is on ${radio.info.preset ?: "?"} · ${FrequencyCalc.from(radio.info).label}. Every measurement is tagged with the preset and frequency it was taken on.",
                fontSize = 12.sp)
            if (change == null) {
                Menu("Test on another preset…", Presets.selectable.map { p -> MeshEnums.preset(p) to { plan = engine.presets.plan(p) } })
                Text("Or change the preset yourself in the official app — MeshSurvey reads and records whatever the radio reports.",
                    fontSize = 11.sp, color = Color.DarkGray)
            } else Text("A temporary preset is open (see banner). Restore it before choosing another.", fontSize = 12.sp)
        }
    }
    plan?.let { p -> PresetPlanDialog(model, p) { plan = null } }
}

@Composable
private fun PresetPlanDialog(model: UiModel, plan: PresetPlan, onClose: () -> Unit) {
    val engine = model.engine
    var autoRestore by remember { mutableStateOf(true) }
    var understood by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onClose,
        title = { Text("Temporarily switch ${plan.currentName ?: "?"} → ${plan.targetName}?") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Before: ${plan.currentName} · ${plan.frequencyBefore.label}", fontSize = 13.sp)
                Text("After:  ${plan.targetName} · ${plan.frequencyAfter.label}", fontSize = 13.sp)
                plan.frequencyAfter.params?.let { Text(it.label, fontSize = 12.sp) }
                plan.budgetAcked?.let { Text("Test airtime on ${plan.targetName}: ~${it.airtimePerTransmissionMs.toInt()} ms per transmission; min spacing ${it.minIntervalS} s with ACK, " +
                    "${plan.budgetDirect?.minIntervalS ?: "?"} s direct-only", fontSize = 12.sp) }
                Text("Only use_preset and modem_preset are changed. Every other LoRa setting is preserved byte-for-byte, and the exact original " +
                    "configuration is saved on this device before anything is sent.", fontSize = 12.sp)
                plan.warnings.forEach { Text("• $it", fontSize = 12.sp) }
                plan.blockers.forEach { Text("✗ $it", fontSize = 12.sp, color = Color(0xFFC62828)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(autoRestore, { autoRestore = it })
                    Text("Restore the original configuration automatically when the timed test session ends", fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(understood, { understood = it })
                    Text("I understand my radio will reboot and leave my normal mesh until I restore it", fontSize = 12.sp)
                }
                err?.let { Text(it, color = Color(0xFFC62828), fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(enabled = plan.canApply && understood, onClick = {
                err = engine.presets.apply(plan, autoRestore)
                if (err == null) onClose()
            }) { Text("Apply temporary preset") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

@Composable
private fun ReferenceRole(model: UiModel) {
    val engine = model.engine
    val rx by engine.testRxRecent.collectAsState()
    val ref by engine.referenceState.collectAsState()
    var msg by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Device B — receive tests", fontWeight = FontWeight.Bold)
            val s = ref.session
            if (s == null) {
                Text("Start a session to take part. You'll get a 6-digit code to give to Device A's operator. Only tests with your code are " +
                    "recorded, and only while the session runs. This radio never transmits test traffic.", fontSize = 12.sp, color = Color.DarkGray)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30 to "30 min", 120 to "2 h", 480 to "8 h").forEach { (min, label) ->
                        Button(onClick = { msg = engine.reference.start(min * 60) }) { Text("Start · $label") }
                    }
                }
            } else {
                Text("Participation code", fontSize = 12.sp)
                Text(s.refCode.toString().chunked(3).joinToString(" "), fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("Tell Device A's operator this code and your node ID ${engine.radio.info.myNodeNum?.let { MeshEnums.nodeId(it) } ?: "?"}. " +
                    "Ends in ${ref.remainingS?.let { "${it / 3600}:${((it % 3600) / 60).toString().padStart(2, '0')}" } ?: "?"}. " +
                    "Same preset and channel as Device A required (now ${engine.radio.info.preset ?: "?"}).", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { engine.reference.stop() }) { Text("Stop session") }
                    OutlinedButton(onClick = {
                        val text = Bundle.export(engine.store, engine.deviceId, engine.appVersion, engine.clock.wallMs())
                        model.platform.saveText("meshsurvey-${engine.deviceId}.bundle.json", "application/json", text) { msg = it?.let { p -> "Saved $p — give this file to Device A" } }
                    }) { Text("Export my log for Device A") }
                }
            }
            if (engine.source().name == "SIMULATED") {
                FilterChip(model.peerSendsTests, { model.peerSendsTests = !model.peerSendsTests }, { Text("Simulator: peer sends tests (reconnect)") })
            }
            msg?.let { Text(it, fontSize = 12.sp) }
            Text("Recorded ${ref.received} · ignored: ${ref.ignoredNoSession} outside a session, ${ref.ignoredWrongCode} with another code", fontSize = 13.sp)
            rx.take(20).forEach { r ->
                Text("${model.platform.formatTime(r.rxMs)} from ${MeshEnums.nodeId(r.fromNode)} #${r.seq} · ${r.preset ?: "?"} · " +
                    "RSSI ${r.rssi ?: "?"} · SNR ${r.snr?.let { fmt(it, 1) } ?: "?"} · hops ${r.hopStart?.let { hs -> r.hopLimit?.let { hs - it } } ?: "?"}",
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun CompareCard(model: UiModel) {
    val engine = model.engine
    var status by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val sessions = remember(refresh) { engine.store.testSessions().filter { it.role == TestRole.A_SURVEY && it.originDevice == null } }
    var chosen by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<CompareResult?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Compare with Device B's log (after the survey, no over-the-air reporting)", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    model.platform.openText { text ->
                        if (text == null) return@openText
                        val r = Bundle.import(text, engine.store, engine.deviceId, engine.clock.wallMs())
                        status = when {
                            r.error != null -> r.error
                            r.duplicateFile -> "This file was already imported"
                            else -> "Imported from ${r.originDevice}: ${r.testRx} test receptions, ${r.observations} observations, ${r.windows} logging windows" +
                                (if (r.skippedRows > 0) " (${r.skippedRows} duplicate/invalid rows skipped)" else "")
                        }
                        refresh++
                    }
                }) { Text("Import Device B log…") }
                Menu(chosen?.let { id -> sessions.firstOrNull { it.id == id }?.let { "${model.platform.formatDateTime(it.startedMs)} ${it.mode}" } } ?: "Choose session",
                    sessions.map { s -> "${model.platform.formatDateTime(s.startedMs)} · ${s.mode} · ${s.preset ?: "?"} · ${s.deliveryMode ?: ""} · to ${MeshEnums.nodeId(s.peerNode)}" to { chosen = s.id } })
                Button(enabled = chosen != null, onClick = {
                    val sid = chosen!!
                    val tx = engine.store.testTx(sid)
                    val rx = engine.store.testRx(sid).filter { it.originDevice != null }
                    val origins = rx.mapNotNull { it.originDevice }.toSet()
                    val windows = origins.flatMap { engine.store.windows(it) }
                    val refIds = rx.mapNotNull { it.refSessionId }.toSet()
                    val refs = engine.store.testSessions().filter { it.role == TestRole.B_REFERENCE && it.originDevice in origins && it.id in refIds }
                    result = SessionCompare.compare(tx, rx, windows, engine.ab.config.ackTimeoutS * 1000L, refs.ifEmpty { null })
                }) { Text("Compare") }
            }
            status?.let { Text(it, fontSize = 12.sp) }
            result?.let { r ->
                Text("Transmitted ${r.sent} · measured delivery ratio ${r.measuredDeliveryRatio?.let { fmt(it * 100, 0) + " %" } ?: "n/a (no B log coverage)"}",
                    fontWeight = FontWeight.Bold)
                Outcome.entries.forEach { o -> if (r.count(o) > 0) Text("${o.label}: ${r.count(o)}", fontSize = 12.sp) }
                r.rows.forEach { row ->
                    Text("#${row.tx.seq} ${model.platform.formatTime(row.tx.sentMs)} ${row.tx.preset ?: ""} A:${row.tx.status} → ${row.outcome.name}" +
                        (row.rx?.let { " · at B RSSI ${it.rssi ?: "?"} SNR ${it.snr?.let { s -> fmt(s, 1) } ?: "?"}" } ?: ""),
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
