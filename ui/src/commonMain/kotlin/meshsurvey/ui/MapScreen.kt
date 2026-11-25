package meshsurvey.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import meshsurvey.core.map.CellClass
import meshsurvey.core.map.CellStats
import meshsurvey.core.map.Coverage
import meshsurvey.core.map.Grid
import meshsurvey.core.map.Scales
import meshsurvey.core.model.Observation
import meshsurvey.core.model.TestTx
import meshsurvey.core.model.TrackPoint
import meshsurvey.core.proto.MeshEnums
import meshsurvey.core.store.ObsFilter

enum class Metric { SNR, RSSI }
enum class TimeRange(val label: String, val ms: Long?) { HOUR("1 h", 3_600_000), DAY("24 h", 86_400_000), WEEK("7 d", 7 * 86_400_000L), ALL("All", null) }

data class MapFilter(
    val metric: Metric = Metric.SNR,
    val showGrid: Boolean = true,
    val showPoints: Boolean = false,
    val showTrack: Boolean = true,
    val showTests: Boolean = true,
    val showAccuracy: Boolean = false,
    val cellM: Int = 250,
    val range: TimeRange = TimeRange.ALL,
    val preset: String? = null,
    val minSnr: Double? = null,
    val maxAccuracyM: Double? = null,
    val onlyMyDevices: Boolean = false,
)

private class MapData(
    val grid: Grid,
    val cells: Map<Long, CellStats>,
    val obs: List<Observation>,
    val track: List<TrackPoint>,
    val tests: List<TestTx>,
    val presets: List<String>,
)

fun argb(v: Long) = Color(v.toInt())

@Composable
fun MapScreen(model: UiModel) {
    val engine = model.engine
    val f = model.mapFilter
    var data by remember { mutableStateOf<MapData?>(null) }
    var selected by remember { mutableStateOf<Pair<Long, Grid>?>(null) }
    val fix by engine.fix.collectAsState()

    // Reload from the database when filters change, and at most every 3 s while data changes.
    LaunchedEffect(f) {
        var loaded = -1L
        while (true) {
            if (loaded != engine.dataVersion.value) {
                loaded = engine.dataVersion.value
                data = load(model, f)
                if (!model.centeredOnce) {
                    val d = data
                    val first = d?.obs?.lastOrNull { it.lat != null }
                    val tp = d?.track?.lastOrNull()
                    when {
                        first != null -> { model.map.centerOn(first.lat!!, first.lon!!, 14.0); model.centeredOnce = true }
                        tp != null -> { model.map.centerOn(tp.lat, tp.lon, 14.0); model.centeredOnce = true }
                    }
                }
            }
            delay(3000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        FilterBar(model, data?.presets ?: emptyList())
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val d = data
            SlippyMap(model.map, model.tiles, Modifier.fillMaxSize(),
                onTap = { proj, off ->
                    if (d == null) return@SlippyMap
                    val (lat, lon) = proj.toLatLon(off)
                    val k = d.grid.key(lat, lon)
                    if (d.cells.containsKey(k)) selected = k to d.grid
                },
                overlay = { proj ->
                    if (d == null) return@SlippyMap
                    if (f.showGrid) for (c in d.cells.values) {
                        val b = d.grid.bounds(c.key)
                        val tl = proj.toOffset(b.lat1, b.lon0); val br = proj.toOffset(b.lat0, b.lon1)
                        if (br.x < 0 || tl.x > size.width || br.y < 0 || tl.y > size.height) continue
                        val col = cellColor(c, f.metric)
                        val sz = Size((br.x - tl.x).coerceAtLeast(2f), (br.y - tl.y).coerceAtLeast(2f))
                        drawRect(col.copy(alpha = 0.45f), tl, sz)
                        if (c.direct > 0 && sz.width > 10) drawRect(Color(0xFF0B3D0B), tl, sz, style = Stroke(2f))
                    }
                    if (f.showTrack && d.track.size > 1) {
                        val path = Path()
                        var started = false
                        var prev: TrackPoint? = null
                        for (t in d.track) {
                            val o = proj.toOffset(t.lat, t.lon)
                            if (!started || (prev != null && t.ms - prev.ms > 300_000)) { path.moveTo(o.x, o.y); started = true } else path.lineTo(o.x, o.y)
                            prev = t
                        }
                        drawPath(path, Color(0xCC37474F), style = Stroke(3f))
                    }
                    if (f.showAccuracy) for (o in d.obs) {
                        val lat = o.lat ?: continue; val acc = o.accuracyM ?: continue
                        val p = proj.toOffset(lat, o.lon!!)
                        drawCircle(Color(0x553949AB), (acc / proj.metersPerPx(lat)).toFloat().coerceAtLeast(2f), p, style = Stroke(1.5f))
                    }
                    if (f.showPoints) for (o in d.obs) {
                        val lat = o.lat ?: continue
                        val p = proj.toOffset(lat, o.lon!!)
                        if (p.x < -10 || p.y < -10 || p.x > size.width + 10 || p.y > size.height + 10) continue
                        val col = when (f.metric) { Metric.SNR -> o.snr?.let { argb(Scales.snr(it)) }; Metric.RSSI -> o.rssi?.let { argb(Scales.rssi(it)) } } ?: Color.Gray
                        drawCircle(col, 5f, p); drawCircle(Color.Black, 5f, p, style = Stroke(1f))
                    }
                    if (f.showTests) for (t in d.tests) {
                        val lat = t.lat ?: continue
                        val p = proj.toOffset(lat, t.lon!!)
                        val col = when (Coverage.classify(t, null)) {
                            Coverage.TestClass.REACHED_B -> argb(Scales.TEST_OK)
                            Coverage.TestClass.CONFIRMED_NOT_RECEIVED -> argb(Scales.TEST_NOT_RECEIVED)
                            Coverage.TestClass.NO_ACK -> argb(Scales.TEST_NO_ACK)
                            Coverage.TestClass.UNKNOWN -> argb(Scales.TEST_UNKNOWN)
                            Coverage.TestClass.NOT_TRANSMITTED -> continue
                        }
                        val path = Path().apply { moveTo(p.x, p.y - 8); lineTo(p.x + 8, p.y); lineTo(p.x, p.y + 8); lineTo(p.x - 8, p.y); close() }
                        drawPath(path, col); drawPath(path, Color.Black, style = Stroke(1.5f))
                    }
                    fix?.let { fx ->
                        val p = proj.toOffset(fx.lat, fx.lon)
                        fx.accuracyM?.let { drawCircle(Color(0x332196F3), (it / proj.metersPerPx(fx.lat)).toFloat(), p) }
                        drawCircle(Color.White, 9f, p); drawCircle(Color(0xFF1976D2), 7f, p)
                    }
                })
            Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { model.map.zoom = (model.map.zoom + 1).coerceAtMost(19.0) }) { Text("+") }
                Button(onClick = { model.map.zoom = (model.map.zoom - 1).coerceAtLeast(2.0) }) { Text("−") }
                Button(onClick = { fix?.let { model.map.centerOn(it.lat, it.lon, maxOf(model.map.zoom, 15.0)) } }) { Text("◎") }
            }
            Legend(f, Modifier.align(Alignment.BottomStart).padding(8.dp))
            Text(model.tiles.attribution, fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomEnd)
                .background(Color(0xAAFFFFFF)).padding(horizontal = 4.dp))
        }
    }
    selected?.let { (key, grid) -> CellDialog(model, grid, key, f) { selected = null } }
}

private fun cellColor(c: CellStats, m: Metric): Color = when (c.cls) {
    CellClass.MEASURED -> when (m) {
        Metric.SNR -> c.bestSnr?.let { argb(Scales.snr(it)) }
        Metric.RSSI -> c.bestRssi?.let { argb(Scales.rssi(it)) }
    } ?: Color.Gray
    CellClass.TEST_NOT_RECEIVED -> argb(Scales.TEST_NOT_RECEIVED)
    CellClass.TEST_NO_ACK -> argb(Scales.TEST_NO_ACK)
    CellClass.TEST_REACHED_B -> argb(Scales.TEST_OK)
    CellClass.SURVEYED_NOTHING_HEARD -> argb(Scales.NOTHING_HEARD)
}

private fun obsFilter(model: UiModel, f: MapFilter): ObsFilter {
    val now = model.engine.clock.wallMs()
    val mine = if (f.onlyMyDevices) model.engine.store.testDevices().map { it.nodeNum }.toSet() else null
    return ObsFilter(fromMs = f.range.ms?.let { now - it }, preset = f.preset, minSnr = f.minSnr,
        maxAccuracyM = f.maxAccuracyM, fromNodes = mine, withPositionOnly = true, limit = 50_000)
}

private fun load(model: UiModel, f: MapFilter): MapData {
    val store = model.engine.store
    val now = model.engine.clock.wallMs()
    val obs = store.observations(obsFilter(model, f))
    val from = f.range.ms?.let { now - it }
    val track = store.track(fromMs = from).filter { t -> f.maxAccuracyM == null || (t.accuracyM != null && t.accuracyM!! <= f.maxAccuracyM) }
    val tests = store.testTx().filter { (from == null || it.sentMs >= from) && (f.maxAccuracyM == null || (it.accuracyM ?: 1e9) <= f.maxAccuracyM) }
    val grid = Grid(f.cellM.toDouble())
    // When filtering by signal quality or device, "nothing heard" cells would be misleading; omit track then.
    val trackForCells = if (f.minSnr != null || f.onlyMyDevices || f.preset != null) emptyList() else track
    val cells = Coverage.aggregate(grid, obs, trackForCells, tests)
    val presets = store.sessions().mapNotNull { it.preset }.distinct()
    return MapData(grid, cells, obs, track, tests, presets)
}

@Composable
private fun FilterBar(model: UiModel, presets: List<String>) {
    val f = model.mapFilter
    fun set(n: MapFilter) { model.mapFilter = n }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(f.metric == Metric.SNR, { set(f.copy(metric = Metric.SNR)) }, { Text("SNR") })
        FilterChip(f.metric == Metric.RSSI, { set(f.copy(metric = Metric.RSSI)) }, { Text("RSSI") })
        Text("|", color = Color.Gray)
        FilterChip(f.showGrid, { set(f.copy(showGrid = !f.showGrid)) }, { Text("Grid") })
        FilterChip(f.showPoints, { set(f.copy(showPoints = !f.showPoints)) }, { Text("Points") })
        FilterChip(f.showTrack, { set(f.copy(showTrack = !f.showTrack)) }, { Text("Track") })
        FilterChip(f.showTests, { set(f.copy(showTests = !f.showTests)) }, { Text("Tests") })
        FilterChip(f.showAccuracy, { set(f.copy(showAccuracy = !f.showAccuracy)) }, { Text("GPS accuracy") })
        Text("|", color = Color.Gray)
        Menu("Cell ${f.cellM} m", listOf(100, 250, 500, 1000, 2000).map { "$it m" to { set(f.copy(cellM = it)) } })
        Menu(f.range.label, TimeRange.entries.map { it.label to { set(f.copy(range = it)) } })
        Menu(f.preset ?: "All presets", listOf("All presets" to { set(f.copy(preset = null)) }) + presets.map { p -> p to { set(f.copy(preset = p)) } })
        Menu(f.minSnr?.let { "SNR ≥ ${it.toInt()}" } ?: "Any signal",
            listOf("Any signal" to { set(f.copy(minSnr = null)) }) + listOf(-10.0, -5.0, 0.0, 5.0).map { v -> "SNR ≥ ${v.toInt()} dB" to { set(f.copy(minSnr = v)) } })
        Menu(f.maxAccuracyM?.let { "GPS ≤ ${it.toInt()} m" } ?: "Any GPS accuracy",
            listOf("Any GPS accuracy" to { set(f.copy(maxAccuracyM = null)) }) + listOf(10.0, 25.0, 50.0).map { v -> "GPS ≤ ${v.toInt()} m" to { set(f.copy(maxAccuracyM = v)) } })
        FilterChip(f.onlyMyDevices, { set(f.copy(onlyMyDevices = !f.onlyMyDevices)) }, { Text("Remembered targets only") })
    }
}

@Composable
fun Menu(label: String, items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(label, fontSize = 13.sp) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            items.forEach { (l, a) -> DropdownMenuItem(text = { Text(l) }, onClick = { open = false; a() }) }
        }
    }
}

@Composable
private fun Legend(f: MapFilter, modifier: Modifier) {
    Column(modifier.background(Color(0xE6FFFFFF), RoundedCornerShape(6.dp)).padding(6.dp)) {
        val entries = if (f.metric == Metric.SNR)
            listOf(5.0 to "≥ 5 dB", 0.0 to "≥ 0", -7.0 to "≥ −7", -12.0 to "≥ −12", -99.0 to "< −12").map { argb(Scales.snr(it.first)) to it.second }
        else listOf(-80 to "≥ −90 dBm", -100 to "≥ −105", -110 to "≥ −115", -120 to "≥ −122", -130 to "< −122").map { argb(Scales.rssi(it.first)) to it.second }
        Text("Best ${f.metric.name} in cell (measured)", fontSize = 11.sp)
        entries.forEach { (c, l) -> LegendRow(c, l) }
        LegendRow(argb(Scales.NOTHING_HEARD), "Surveyed, nothing heard")
        LegendRow(argb(Scales.TEST_OK), "Test reached B (ACK / NAK / B's log)")
        LegendRow(argb(Scales.TEST_NOT_RECEIVED), "Test not received (B was logging)")
        LegendRow(argb(Scales.TEST_NO_ACK), "No ACK after retries (delivery unknown)")
        Text("No colour = no data collected", fontSize = 10.sp, color = Color.DarkGray)
    }
}

@Composable
private fun LegendRow(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(end = 4.dp).background(c, RoundedCornerShape(2.dp)).padding(6.dp))
        Text(label, fontSize = 10.sp)
    }
}

@Composable
private fun CellDialog(model: UiModel, grid: Grid, key: Long, f: MapFilter, onClose: () -> Unit) {
    val engine = model.engine
    val b = grid.bounds(key)
    val names = remember { engine.store.nodeNames() }
    val obs = remember(key, f) {
        engine.store.observations(obsFilter(model, f).copy(bbox = doubleArrayOf(b.lat0, b.lat1, b.lon0, b.lon1), limit = 2000))
            .filter { grid.key(it.lat!!, it.lon!!) == key }.sortedByDescending { it.rxMs }
    }
    val tests = remember(key) { engine.store.testTx().filter { it.lat != null && grid.key(it.lat!!, it.lon!!) == key } }
    val cell = remember(key, obs) { Coverage.aggregate(grid, obs, engine.store.track().filter { grid.key(it.lat, it.lon) == key }, tests)[key] }
    AlertDialog(onDismissRequest = onClose, confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("Cell ${f.cellM} m · ${fmt(b.centerLat, 5)}, ${fmt(b.centerLon, 5)}") },
        text = {
            Column {
                if (cell != null) {
                    Text("${cell.observations} receptions · ${cell.direct} heard straight from the sender (0 hops)")
                    Text("SNR best ${cell.bestSnr?.let { fmt(it, 1) } ?: "–"} / median ${cell.medianSnr?.let { fmt(it, 1) } ?: "–"} dB · " +
                        "RSSI best ${cell.bestRssi ?: "–"} / median ${cell.medianRssi?.let { fmt(it, 0) } ?: "–"} dBm")
                    Text("Track points while radio connected: ${cell.trackReady} · tests: ${cell.testsSent} sent, ${cell.testsReachedB} reached B, " +
                        "${cell.testsNotReceived} not received (B logging), ${cell.testsNoAck} no ACK, ${cell.testsUnknown} unknown")
                    Text("GPS accuracy ${cell.bestAccuracyM?.let { fmt(it, 0) } ?: "?"}–${cell.worstAccuracyM?.let { fmt(it, 0) } ?: "?"} m", fontSize = 12.sp)
                }
                Text("Relay byte = last byte of the relaying node's number as reported by firmware; it does not identify a node.",
                    fontSize = 11.sp, color = Color.DarkGray)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(obs) { o ->
                        Column(Modifier.padding(vertical = 3.dp)) {
                            Text("${model.platform.formatDateTime(o.rxMs)}  ${o.type}  ${o.status}", fontSize = 12.sp)
                            Text("from ${names[o.fromNode]?.label ?: MeshEnums.nodeId(o.fromNode)} · RSSI ${o.rssi ?: "?"} · SNR ${o.snr?.let { fmt(it, 2) } ?: "?"} · " +
                                "hops ${o.hopsAway ?: "?"} (${o.hopLimit ?: "?"}/${o.hopStart ?: "?"}) · relay ${o.relayByte?.let { "0x" + it.toString(16).padStart(2, '0') } ?: "?"}",
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            Text("id ${o.packetId.toString(16)} · GPS ±${o.accuracyM?.let { fmt(it, 0) } ?: "?"} m, age ${o.fixAgeMs?.let { it / 1000 } ?: "?"} s · ${o.preset ?: "preset ?"} · " +
                                "${o.frequencyMhz?.let { fmt(it, 3) + " MHz " + (if (o.frequencySource == "CALCULATED") "calc." else "cfg.") } ?: "freq ?"} · ${o.source}" +
                                (o.originDevice?.let { " · imported from $it" } ?: "") + (o.summary?.let { " · $it" } ?: ""), fontSize = 11.sp, color = Color.DarkGray)
                        }
                    }
                }
            }
        })
}

fun fmt(v: Double, decimals: Int): String {
    var m = 1.0; repeat(decimals) { m *= 10 }
    val r = kotlin.math.round(v * m) / m
    val s = r.toString()
    if (decimals == 0) return r.toLong().toString()
    val dot = s.indexOf('.')
    return if (dot < 0) s + "." + "0".repeat(decimals) else s.padEnd(dot + 1 + decimals, '0').take(dot + 1 + decimals)
}

