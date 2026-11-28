package meshsurvey.ui

import meshsurvey.core.quality.ConfidenceClass
import meshsurvey.core.quality.ConfidenceRules
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
import meshsurvey.core.map.Heard
import meshsurvey.core.map.PredictionCompare
import meshsurvey.core.map.PredictionMap
import meshsurvey.core.model.KnownNode
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer

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
    /** Only receptions by this radio; null = all of this device's radios (and imported data). */
    val receiverNode: Long? = null,
    /** "meshtastic" / "meshcore"; null = both (cells then mix protocols: coverage only, not a like-for-like comparison). */
    val protocol: String? = null,
    /** Layer: positions nodes advertise (MeshCore contacts/adverts, Meshtastic node database). */
    val showKnown: Boolean = true,
    /** Layer: where each node was heard directly, one colour per node. */
    val showHeard: Boolean = false,
    /** Only these heard-from nodes (Heard.group keys) in tiles, points and layers; null = all. */
    val heardGroups: Set<String>? = null,
    /** Imported coverage prediction shown/compared (stored id); null = none. */
    val predictionId: Long? = null,
    /** Colour tiles by measured-vs-predicted instead of by measured signal (needs a transmitter chosen for the prediction). */
    val compare: Boolean = true,
    /** Draw the predicted coverage bands. */
    val showBands: Boolean = true,
    /** Allowance (dB) on each side of the predicted range before a cell counts as stronger/weaker. */
    val toleranceDb: Double = 6.0,
)

private class MapData(
    val grid: Grid,
    val cells: Map<Long, CellStats>,
    val obs: List<Observation>,
    val track: List<TrackPoint>,
    val tests: List<TestTx>,
    val presets: List<String>,
    val known: List<KnownNode>,
    val heardCounts: Map<String, Int>,
    val pred: PredictionMap? = null,
    val predTarget: String? = null,
    val cmp: PredictionCompare.Summary? = null,
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

    val textMeasurer = rememberTextMeasurer()
    var pickNodes by remember { mutableStateOf(false) }
    var predDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        FilterBar(model, data?.presets ?: emptyList(), { predDialog = true }) { pickNodes = true }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val d = data
            SlippyMap(model.map, model.tiles, Modifier.fillMaxSize(),
                onDrawError = { e -> model.reportDrawError(e) },
                onTap = { proj, off ->
                    if (d == null) return@SlippyMap
                    val (lat, lon) = proj.toLatLon(off)
                    val k = d.grid.key(lat, lon)
                    if (d.cells.containsKey(k) || d.cmp?.cells?.containsKey(k) == true) selected = k to d.grid
                },
                overlay = { proj ->
                    if (d == null) return@SlippyMap
                    val pr = d.pred
                    if (pr != null && f.showBands) for (b in pr.bands.sortedBy { it.dbm }) {
                        val col = argb(Scales.rssi(b.dbm.toInt()))
                        for (poly in b.polys) {
                            val tl = proj.toOffset(poly.maxLat, poly.minLon); val br = proj.toOffset(poly.minLat, poly.maxLon)
                            if (br.x < 0 || tl.x > size.width || br.y < 0 || tl.y > size.height) continue
                            val path = Path()
                            for (ring in poly.rings) {
                                var lx = Float.NaN; var ly = Float.NaN
                                var i = 0
                                while (i < ring.size) {
                                    val o = proj.toOffset(ring[i], ring[i + 1])
                                    if (i == 0) { path.moveTo(o.x, o.y); lx = o.x; ly = o.y }
                                    else if (kotlin.math.abs(o.x - lx) + kotlin.math.abs(o.y - ly) >= 1.5f || i == ring.size - 2) { path.lineTo(o.x, o.y); lx = o.x; ly = o.y }
                                    i += 2
                                }
                                path.close()
                            }
                            path.fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                            drawPath(path, col.copy(alpha = 0.10f))
                            drawPath(path, col.copy(alpha = 0.7f), style = Stroke(1.2f))
                        }
                    }
                    val cmp = d.cmp
                    if (f.showGrid && cmp != null && f.compare) for (c in cmp.cells.values) {
                        val b = d.grid.bounds(c.key)
                        val tl = proj.toOffset(b.lat1, b.lon0); val br = proj.toOffset(b.lat0, b.lon1)
                        if (br.x < 0 || tl.x > size.width || br.y < 0 || tl.y > size.height) continue
                        val sz = Size((br.x - tl.x).coerceAtLeast(2f), (br.y - tl.y).coerceAtLeast(2f))
                        val cc = argb(PredictionCompare.color(c.cls))
                        // no reception there: drawn as an outline only, because "not heard while passing" is weak evidence
                        if (c.receptions > 0) drawRect(cc.copy(alpha = 0.6f), tl, sz)
                        else { drawRect(cc.copy(alpha = 0.12f), tl, sz); drawRect(cc.copy(alpha = 0.8f), tl, sz, style = Stroke(1.5f)) }
                        if (c.nearNoise && sz.width > 10) drawRect(Color.Black, tl, sz, style = Stroke(1f))
                    }
                    else if (f.showGrid) for (c in d.cells.values) {
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
                    if (f.showHeard) {
                        // where each node was heard directly; with one node selected, lines to its advertised position
                        val single = f.heardGroups?.singleOrNull()?.let { g -> Heard.candidates(g, d.known).singleOrNull()?.takeIf { it.lat != null } }
                        val target = single?.let { proj.toOffset(it.lat!!, it.lon!!) }
                        for (o in d.obs.takeLast(5000)) {
                            val lat = o.lat ?: continue; val h = o.heardId ?: continue
                            val p = proj.toOffset(lat, o.lon!!)
                            if (p.x < -10 || p.y < -10 || p.x > size.width + 10 || p.y > size.height + 10) continue
                            val col = argb(Heard.color(Heard.group(h)))
                            if (target != null) drawLine(col.copy(alpha = 0.25f), p, target, 1.5f)
                            drawCircle(col, 4.5f, p); drawCircle(Color.White, 4.5f, p, style = Stroke(1f))
                        }
                    }
                    if (f.showKnown) for (n in d.known) {
                        val lat = n.lat ?: continue; val lon = n.lon ?: continue
                        val g = Heard.groupOf(n)
                        if (f.heardGroups != null && g !in f.heardGroups) continue
                        val p = proj.toOffset(lat, lon)
                        if (p.x < -20 || p.y < -20 || p.x > size.width + 20 || p.y > size.height + 20) continue
                        val heard = g != null && (d.heardCounts[g] ?: 0) > 0
                        val col = if (heard && g != null) argb(Heard.color(g)) else Color(0xFF9E9E9E)
                        val repeater = n.kind == "repeater" || n.kind == "room server" || (n.kind?.startsWith("ROUTER") == true) || n.kind == "REPEATER"
                        if (repeater) {
                            val tri = Path().apply { moveTo(p.x, p.y - 10); lineTo(p.x + 9, p.y + 7); lineTo(p.x - 9, p.y + 7); close() }
                            drawPath(tri, col); drawPath(tri, Color.Black, style = Stroke(1.5f))
                        } else {
                            drawRect(col, Offset(p.x - 5, p.y - 5), Size(10f, 10f)); drawRect(Color.Black, Offset(p.x - 5, p.y - 5), Size(10f, 10f), style = Stroke(1f))
                        }
                        val nm = n.name
                        // Measure first, then draw the laid-out text: drawText(String) derives its width limit from the space
                        // left to the canvas edge, which is negative for a node near the right edge and crashed the app.
                        if (model.map.zoom >= 12 && nm != null && p.x + 10 < size.width && p.y > 0 && p.y < size.height) {
                            val lay = textMeasurer.measure(nm.take(20), TextStyle(fontSize = 10.sp, color = Color.Black, background = Color(0xCCFFFFFF)))
                            drawText(lay, topLeft = Offset(p.x + 10, p.y - 8))
                        }
                    }
                    fix?.let { fx ->
                        val p = proj.toOffset(fx.lat, fx.lon)
                        fx.accuracyM?.let { drawCircle(Color(0x332196F3), (it / proj.metersPerPx(fx.lat)).toFloat(), p) }
                        drawCircle(Color.White, 9f, p); drawCircle(Color(0xFF1976D2), 7f, p)
                    }
                })
            Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val zp = androidx.compose.foundation.layout.PaddingValues(0.dp)
                val zm = Modifier.size(44.dp)
                Button(onClick = { model.map.zoom = (model.map.zoom + 1).coerceAtMost(19.0) }, zm, contentPadding = zp) { Text("+", fontSize = 18.sp) }
                Button(onClick = { model.map.zoom = (model.map.zoom - 1).coerceAtLeast(2.0) }, zm, contentPadding = zp) { Text("−", fontSize = 18.sp) }
                Button(onClick = { fix?.let { model.map.centerOn(it.lat, it.lon, maxOf(model.map.zoom, 15.0)) } }, zm, contentPadding = zp) { Text("◎", fontSize = 16.sp) }
            }
            val compact = LocalCompact.current
            var legend by remember(compact, f.predictionId) { mutableStateOf(!compact && f.predictionId == null) }
            Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                if (legend) Legend(f, data, Modifier.padding(bottom = 4.dp))
                androidx.compose.material3.FilledTonalButton(onClick = { legend = !legend },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
                    Text(if (legend) "Hide legend" else "Legend", fontSize = 12.sp)
                }
            }
            data?.let { d -> if (d.pred != null) PredictionCard(model, d, Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp, end = 60.dp)) }
            Text(model.tiles.attribution, fontSize = 10.sp, color = Color(0xFF222222), modifier = Modifier.align(Alignment.BottomEnd)
                .background(Color(0xCCFFFFFF)).padding(horizontal = 4.dp))
        }
    }
    selected?.let { (key, grid) -> CellDialog(model, grid, key, f, data?.cmp?.cells?.get(key)) { selected = null } }
    if (pickNodes) data?.let { d -> NodePicker(model, d) { pickNodes = false } }
    if (predDialog) PredictionDialog(model) { predDialog = false }
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
        maxAccuracyM = f.maxAccuracyM, fromNodes = mine, withPositionOnly = true, limit = 50_000, receiverNode = f.receiverNode,
        protocol = f.protocol, heardGroups = f.heardGroups)
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
    val trackForCells = if (f.minSnr != null || f.onlyMyDevices || f.preset != null || f.heardGroups != null) emptyList() else track
    val cells = Coverage.aggregate(grid, obs, trackForCells, if (f.heardGroups != null || f.protocol == "meshcore") emptyList() else tests)
    val presets = store.sessions().filter { f.protocol == null || it.protocol == f.protocol }.mapNotNull { it.preset }.distinct()
    val known = store.knownNodes(f.protocol)
    val heard = store.heardGroupCounts(f.protocol)
    val pid = f.predictionId ?: return MapData(grid, cells, obs, track, tests, presets, known, heard)
    val pred = model.prediction(pid) ?: return MapData(grid, cells, obs, track, tests, presets, known, heard)
    val target = store.predictions().firstOrNull { it.id == pid }?.target
    val cmp = target?.let { t ->
        val tf = obsFilter(model, f).copy(heardGroups = if (t.startsWith("mc:")) setOf(t) else null, heardIds = if (t.startsWith("mt:")) setOf(t) else null,
            protocol = null, receiverNode = f.receiverNode)
        PredictionCompare.compare(pred, grid, store.observations(tf), track.filter { it.radioReady }, f.toleranceDb)
    }
    return MapData(grid, cells, obs, track, tests, presets, known, heard, pred, target, cmp)
}

@Composable
private fun FilterBar(model: UiModel, presets: List<String>, onPrediction: () -> Unit, onPickNodes: () -> Unit) {
    val f = model.mapFilter
    fun set(n: MapFilter) { model.mapFilter = n }
    var filters by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(f.metric == Metric.SNR, { set(f.copy(metric = Metric.SNR)) }, { Text("SNR") })
        FilterChip(f.metric == Metric.RSSI, { set(f.copy(metric = Metric.RSSI)) }, { Text("RSSI") })
        CheckMenu("Layers", listOf(
            Triple("Tile colours", f.showGrid) { set(model.mapFilter.copy(showGrid = !model.mapFilter.showGrid)) },
            Triple("Points", f.showPoints) { set(model.mapFilter.copy(showPoints = !model.mapFilter.showPoints)) },
            Triple("Track", f.showTrack) { set(model.mapFilter.copy(showTrack = !model.mapFilter.showTrack)) },
            Triple("Known nodes", f.showKnown) { set(model.mapFilter.copy(showKnown = !model.mapFilter.showKnown)) },
            Triple("Heard from (per node)", f.showHeard) { set(model.mapFilter.copy(showHeard = !model.mapFilter.showHeard)) },
            Triple("Tests", f.showTests) { set(model.mapFilter.copy(showTests = !model.mapFilter.showTests)) },
            Triple("GPS accuracy", f.showAccuracy) { set(model.mapFilter.copy(showAccuracy = !model.mapFilter.showAccuracy)) },
            Triple("Prediction bands", f.showBands) { set(model.mapFilter.copy(showBands = !model.mapFilter.showBands)) },
        ))
        val active = listOfNotNull(f.protocol, f.preset, f.minSnr, f.maxAccuracyM, f.receiverNode, f.onlyMyDevices.takeIf { it },
            f.range.takeIf { it != TimeRange.ALL }).size
        OutlinedButton(onClick = { filters = true }) { Text("Filters" + if (active > 0) " ($active)" else "", fontSize = 13.sp) }
        OutlinedButton(onClick = onPickNodes) { Text(f.heardGroups?.let { "Nodes: ${it.size}" } ?: "Nodes: all", fontSize = 13.sp) }
        OutlinedButton(onClick = onPrediction) { Text(if (f.predictionId != null) "Prediction ✓" else "Prediction…", fontSize = 13.sp) }
        Menu("Cell ${f.cellM} m", listOf(100, 250, 500, 1000, 2000).map { "$it m" to { set(f.copy(cellM = it)) } })
    }
    if (filters) AlertDialog(onDismissRequest = { filters = false },
        confirmButton = { TextButton(onClick = { filters = false }) { Text("Done") } },
        dismissButton = { TextButton(onClick = { set(MapFilter(metric = f.metric, showGrid = f.showGrid, showPoints = f.showPoints, showTrack = f.showTrack,
            showTests = f.showTests, showAccuracy = f.showAccuracy, cellM = f.cellM, showKnown = f.showKnown, showHeard = f.showHeard,
            predictionId = f.predictionId, compare = f.compare, showBands = f.showBands, toleranceDb = f.toleranceDb)) }) { Text("Reset filters") } },
        title = { Text("Map filters") },
        text = {
            val g = model.mapFilter
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterRow("Protocol") { Menu(when (g.protocol) { "meshtastic" -> "Meshtastic"; "meshcore" -> "MeshCore"; else -> "Both" },
                    listOf("Both" to { set(model.mapFilter.copy(protocol = null, heardGroups = null, preset = null)) },
                        "Meshtastic" to { set(model.mapFilter.copy(protocol = "meshtastic", heardGroups = null, preset = null)) },
                        "MeshCore" to { set(model.mapFilter.copy(protocol = "meshcore", heardGroups = null, preset = null)) })) }
                FilterRow("Time") { Menu(g.range.label, TimeRange.entries.map { it.label to { set(model.mapFilter.copy(range = it)) } }) }
                FilterRow("Preset") { Menu(g.preset ?: "All", listOf("All" to { set(model.mapFilter.copy(preset = null)) }) +
                    presets.map { p -> p to { set(model.mapFilter.copy(preset = p)) } }) }
                FilterRow("Signal") { Menu(g.minSnr?.let { "SNR ≥ ${it.toInt()} dB" } ?: "Any",
                    listOf("Any" to { set(model.mapFilter.copy(minSnr = null)) }) + listOf(-10.0, -5.0, 0.0, 5.0).map { v -> "SNR ≥ ${v.toInt()} dB" to { set(model.mapFilter.copy(minSnr = v)) } }) }
                FilterRow("GPS accuracy") { Menu(g.maxAccuracyM?.let { "≤ ${it.toInt()} m" } ?: "Any",
                    listOf("Any" to { set(model.mapFilter.copy(maxAccuracyM = null)) }) + listOf(10.0, 25.0, 50.0).map { v -> "≤ ${v.toInt()} m" to { set(model.mapFilter.copy(maxAccuracyM = v)) } }) }
                val receivers = remember { model.engine.store.sessions().mapNotNull { it.receiverNode }.distinct() }
                if (receivers.size > 1) FilterRow("Receiver") { Menu(g.receiverNode?.let { MeshEnums.nodeId(it) } ?: "All",
                    listOf("All" to { set(model.mapFilter.copy(receiverNode = null)) }) + receivers.map { n -> MeshEnums.nodeId(n) to { set(model.mapFilter.copy(receiverNode = n)) } }) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(g.onlyMyDevices, { set(model.mapFilter.copy(onlyMyDevices = it)) })
                    Text("Only remembered test targets", fontSize = 13.sp)
                }
                Text("Filters that hide receptions also hide \"surveyed, nothing heard\" cells, which would otherwise mislead.", fontSize = 11.sp, color = Pal.muted)
            }
        })
}

@Composable
private fun FilterRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.widthIn(min = 110.dp).padding(end = 8.dp))
        content()
    }
}

/** A dropdown of on/off items that stays open while toggling. */
@Composable
fun CheckMenu(label: String, items: List<Triple<String, Boolean, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$label ▾", fontSize = 13.sp) }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            items.forEach { (l, on, toggle) ->
                DropdownMenuItem(text = { Text(l) }, onClick = toggle, leadingIcon = { Checkbox(on, { toggle() }) })
            }
        }
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
private fun Legend(f: MapFilter, d: MapData?, modifier: Modifier) {
    Column(modifier.widthIn(max = 300.dp).background(Pal.overlay, RoundedCornerShape(6.dp)).padding(6.dp)) {
        if (f.protocol == null && d != null && d.heardCounts.keys.any { it.startsWith("mc:") } && d.heardCounts.keys.any { it.startsWith("mt:") })
            Text("Both protocols shown: coverage only, not a like-for-like comparison", fontSize = 10.sp, color = Pal.warn)
        if (f.showHeard && d != null) {
            Text("Heard directly from", fontSize = 11.sp)
            val groups = (f.heardGroups?.toList() ?: d.heardCounts.keys.toList()).take(8)
            groups.forEach { g -> LegendRow(argb(Heard.color(g)), Heard.label(g, d.known) + " · ${d.heardCounts[g] ?: 0}") }
            if ((f.heardGroups?.size ?: d.heardCounts.size) > 8) Text("…", fontSize = 10.sp)
        }
        if (f.showKnown) Text("▲ repeater  ■ other node, at its advertised position (grey = not heard directly yet)", fontSize = 10.sp, color = Pal.muted)
        if (!f.showGrid) return@Column
        if (d?.cmp != null && f.compare) {
            Text("Measured vs predicted (cells)", fontSize = 11.sp)
            PredictionCompare.CellClass.entries.forEach { LegendRow(argb(PredictionCompare.color(it)), it.label) }
            Text("Outlined: SNR below −5 dB, RSSI reads high there", fontSize = 10.sp, color = Pal.muted)
            return@Column
        }
        val entries = if (f.metric == Metric.SNR)
            listOf(5.0 to "≥ 5 dB", 0.0 to "≥ 0", -7.0 to "≥ −7", -12.0 to "≥ −12", -99.0 to "< −12").map { argb(Scales.snr(it.first)) to it.second }
        else listOf(-80 to "≥ −90 dBm", -100 to "≥ −105", -110 to "≥ −115", -120 to "≥ −122", -130 to "< −122").map { argb(Scales.rssi(it.first)) to it.second }
        Text("Best ${f.metric.name} in cell (measured)", fontSize = 11.sp)
        entries.forEach { (c, l) -> LegendRow(c, l) }
        LegendRow(argb(Scales.NOTHING_HEARD), "Surveyed, nothing heard")
        LegendRow(argb(Scales.TEST_OK), "Test reached B (ACK / NAK / B's log)")
        LegendRow(argb(Scales.TEST_NOT_RECEIVED), "Test not received (B was logging)")
        LegendRow(argb(Scales.TEST_NO_ACK), "No ACK after retries (delivery unknown)")
        Text("No colour = no data collected", fontSize = 10.sp, color = Pal.muted)
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
private fun CellDialog(model: UiModel, grid: Grid, key: Long, f: MapFilter, cmp: PredictionCompare.Cell?, onClose: () -> Unit) {
    val engine = model.engine
    val b = grid.bounds(key)
    val names = remember { engine.store.nodeNames() }
    val known = remember { engine.store.knownNodes() }
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
                if (cmp != null) {
                    Text("Prediction: ${cmp.cls.label}", color = argb(PredictionCompare.color(cmp.cls)), fontSize = 13.sp)
                    Text("Predicted ${cmp.predLo?.let { lo -> "${lo.toInt()}" + (cmp.predHi?.let { " to ${it.toInt()}" } ?: " or more") + " dBm" } ?: "nothing (below the planner's floor)"}" +
                        (cmp.medianRssi?.let { " · measured median ${fmt(it, 0)} dBm from ${cmp.receptions} direct receptions" } ?: " · not heard") +
                        (cmp.residualDb?.let { " · difference ${if (it > 0) "+" else ""}${fmt(it, 1)} dB" } ?: "") +
                        (if (cmp.trackPoints > 0) " · ${cmp.trackPoints} track points while logging" else ""), fontSize = 12.sp)
                    if (cmp.nearNoise) Text("Median SNR ${fmt(cmp.medianSnr!!, 1)} dB: near the noise floor, RSSI reads higher than the signal.", fontSize = 11.sp, color = Pal.warn)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                }
                if (cell != null) {
                    Text("${cell.observations} receptions · ${cell.direct} heard straight from the sender (0 hops)")
                    Text("SNR best ${cell.bestSnr?.let { fmt(it, 1) } ?: "–"} / median ${cell.medianSnr?.let { fmt(it, 1) } ?: "–"} dB · " +
                        "RSSI best ${cell.bestRssi ?: "–"} / median ${cell.medianRssi?.let { fmt(it, 0) } ?: "–"} dBm")
                    Text("Track points while radio connected: ${cell.trackReady} · tests: ${cell.testsSent} sent, ${cell.testsReachedB} reached B, " +
                        "${cell.testsNotReceived} not received (B logging), ${cell.testsNoAck} no ACK, ${cell.testsUnknown} unknown")
                    Text("GPS accuracy ${cell.bestAccuracyM?.let { fmt(it, 0) } ?: "?"}–${cell.worstAccuracyM?.let { fmt(it, 0) } ?: "?"} m", fontSize = 12.sp)
                }
                Text("Relay byte = last byte of the relaying node's number (Meshtastic) or of the last repeater's key (MeshCore); " +
                    "it alone does not identify a node.",
                    fontSize = 11.sp, color = Pal.muted)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(obs) { o ->
                        Column(Modifier.padding(vertical = 3.dp)) {
                            Text("${model.platform.formatDateTime(o.rxMs)}  ${o.type}  ${o.status}", fontSize = 12.sp)
                            Text("from ${names[o.fromNode]?.label ?: MeshEnums.nodeId(o.fromNode)} · RSSI ${o.rssi ?: "?"} · SNR ${o.snr?.let { fmt(it, 2) } ?: "?"} · " +
                                "hops ${o.hopsAway ?: "?"} (${o.hopLimit ?: "?"}/${o.hopStart ?: "?"}) · relay ${o.relayByte?.let { "0x" + it.toString(16).padStart(2, '0') } ?: "?"}" +
                                    (if (o.protocol == "meshcore") " · MeshCore" else "") + (o.heardId?.let { " · heard from ${Heard.label(Heard.group(it), known)}" } ?: ""),
                                fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            val conf = ConfidenceRules.classify(o)
                            Text(conf.cls.label + (if (conf.reasons.isEmpty()) "" else ": " + conf.reasons.joinToString("; ")), fontSize = 11.sp,
                                color = when (conf.cls) { ConfidenceClass.VALID -> Pal.ok; ConfidenceClass.LIMITED -> Pal.warn; else -> Pal.bad })
                            Text("id ${o.packetId.toString(16)} · position ${o.fixSource?.lowercase() ?: "none"} ±${o.accuracyM?.let { fmt(it, 0) } ?: "?"} m, " +
                                "age ${(o.positionAgeMs ?: o.fixAgeMs)?.let { a -> if (a >= 120_000) "${a / 60_000} min" else "${a / 1000} s" } ?: "?"} · ${o.preset ?: "preset ?"} · " +
                                "${o.frequencyMhz?.let { fmt(it, 3) + " MHz " + (if (o.frequencySource == "CALCULATED") "calc." else "cfg.") } ?: "freq ?"} · ${o.source}" +
                                (o.originDevice?.let { " · imported from $it" } ?: "") + (o.summary?.let { " · $it" } ?: ""), fontSize = 11.sp, color = Pal.muted)
                        }
                    }
                }
            }
        })
}

/** Label for a transmitter key: "mt:!xxxxxxxx" (Meshtastic node) or "mc:xx" (MeshCore key-prefix group). */
fun transmitterLabel(model: UiModel, t: String, known: List<KnownNode>): String =
    if (t.startsWith("mt:!")) {
        val num = t.removePrefix("mt:!").toLongOrNull(16)
        val nm = num?.let { model.engine.store.nodeNames()[it]?.label }
        if (nm != null) "$nm (${t.removePrefix("mt:")})" else t.removePrefix("mt:")
    } else Heard.label(t, known) + " · MeshCore"

/** Summary of the active prediction and, once a transmitter is chosen, the measured-vs-predicted comparison. */
@Composable
private fun PredictionCard(model: UiModel, d: MapData, modifier: Modifier) {
    val f = model.mapFilter
    val compact = LocalCompact.current
    var open by remember(compact) { mutableStateOf(!compact) }
    Column(modifier.widthIn(max = 420.dp).heightIn(max = if (compact) 360.dp else 520.dp).background(Pal.overlay, RoundedCornerShape(6.dp))
        .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Prediction: ${d.pred!!.name}", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
        }
        if (!open) {
            d.cmp?.let { c -> Text("Heard in ${c.heardCells} cells · ${c.agrees} agree, ${c.stronger} stronger, ${c.weaker} weaker" +
                (c.medianResidualDb?.let { r -> " · median ${if (r > 0) "+" else ""}${fmt(r, 1)} dB" } ?: ""), fontSize = 11.sp) }
            return@Column
        }
        val p = d.pred ?: return@Column
        Text("Bands ${p.levels.firstOrNull()?.toInt()} to ${p.levels.lastOrNull()?.toInt()} dBm, ${fmt(p.stepDb, 1)} dB apart · predicted received signal for one transmitter",
            fontSize = 11.sp, color = Pal.muted)
        val t = d.predTarget
        if (t == null) { Text("Choose which transmitter this prediction is for (Prediction… button) to compare it with your measurements.", fontSize = 12.sp); return@Column }
        Text("Transmitter: ${transmitterLabel(model, t, d.known)}", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(f.compare, { model.mapFilter = f.copy(compare = !f.compare) }, { Text("Colour tiles by comparison", fontSize = 11.sp) })
            FilterChip(f.showBands, { model.mapFilter = f.copy(showBands = !f.showBands) }, { Text("Bands", fontSize = 11.sp) })
        }
        d.cmp?.lines()?.forEach { Text("• $it", fontSize = 11.sp) }
        Text("RSSI measured by your radio vs the planner's predicted received signal. The planner's settings (antenna heights, gains, " +
            "cable loss, clutter) and your receiver's antenna both shift the result.", fontSize = 10.sp, color = Pal.muted)
    }
}

/** Import, choose and configure coverage predictions. */
@Composable
private fun PredictionDialog(model: UiModel, onClose: () -> Unit) {
    val store = model.engine.store
    var list by remember { mutableStateOf(store.predictions()) }
    var msg by remember { mutableStateOf<String?>(null) }
    val f = model.mapFilter
    val transmitters = remember { store.directTransmitters() }
    val known = remember { store.knownNodes() }
    AlertDialog(onDismissRequest = onClose, confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text("Coverage predictions") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Import a prediction from the Meshtastic Site Planner (site.meshtastic.org): run your site, then Export → GeoJSON. " +
                    "PNG and KML exports hold colours only and cannot be compared.", fontSize = 12.sp)
                Button(onClick = {
                    model.platform.openText { text ->
                        if (text == null) return@openText
                        val (p, err) = PredictionMap.parse(text, "prediction")
                        if (p == null) { msg = err; return@openText }
                        val id = store.insertPrediction(p.name, null, text, model.engine.clock.wallMs())
                        list = store.predictions()
                        model.mapFilter = model.mapFilter.copy(predictionId = id)
                        msg = "Imported \"${p.name}\" (${p.bands.size} bands). Now choose its transmitter below."
                    }
                }) { Text("Import GeoJSON…") }
                msg?.let { Text(it, fontSize = 12.sp, color = Pal.warn) }
                HorizontalDivider()
                if (list.isEmpty()) Text("No predictions imported yet.", fontSize = 12.sp)
                list.forEach { sp ->
                    val active = model.mapFilter.predictionId == sp.id
                    Column(Modifier.fillMaxWidth().background(if (active) Pal.highlight else Color.Transparent, RoundedCornerShape(4.dp)).padding(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(sp.name + " · " + model.platform.formatDateTime(sp.importedMs), fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { model.mapFilter = model.mapFilter.copy(predictionId = if (active) null else sp.id) }) { Text(if (active) "Hide" else "Show") }
                            TextButton(onClick = {
                                store.deletePrediction(sp.id); model.forgetPrediction(sp.id); list = store.predictions()
                                if (active) model.mapFilter = model.mapFilter.copy(predictionId = null)
                            }) { Text("Delete") }
                        }
                        val suggestions = transmitters.keys.sortedByDescending { k -> transmitterLabel(model, k, known).contains(sp.name, ignoreCase = true) }
                        Menu("Transmitter: " + (sp.target?.let { transmitterLabel(model, it, known) } ?: "choose…"),
                            suggestions.map { k -> "${transmitterLabel(model, k, known)} · ${transmitters[k]} direct receptions" to {
                                store.setPredictionTarget(sp.id, k); list = store.predictions()
                                model.mapFilter = model.mapFilter.copy(predictionId = sp.id)
                            } })
                    }
                }
                if (transmitters.isEmpty()) Text("No transmitter heard directly yet: comparisons use only receptions heard straight from the " +
                    "predicted node (zero hops), because relayed copies say nothing about its own signal.", fontSize = 11.sp, color = Pal.muted)
                HorizontalDivider()
                Menu("Tolerance ±${f.toleranceDb.toInt()} dB", listOf(3.0, 6.0, 10.0).map { v -> "±${v.toInt()} dB" to { model.mapFilter = model.mapFilter.copy(toleranceDb = v) } })
                Text("Measured RSSI inside the predicted range ± tolerance counts as agreeing. Use a small cell size (100–250 m).", fontSize = 11.sp, color = Pal.muted)
            }
        })
}

/** Choose which heard-from nodes the map shows (tiles, points, "Heard from" and "Known nodes" layers). */
@Composable
private fun NodePicker(model: UiModel, d: MapData, onClose: () -> Unit) {
    val f = model.mapFilter
    var chosen by remember { mutableStateOf(f.heardGroups ?: d.heardCounts.keys) }
    AlertDialog(onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = {
            model.mapFilter = f.copy(heardGroups = if (chosen.containsAll(d.heardCounts.keys)) null else chosen); onClose()
        }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        title = { Text("Nodes heard directly") },
        text = {
            Column {
                Text("Receptions are grouped by the short ID the packet carries (MeshCore: first key byte; Meshtastic relays: last node-number " +
                    "byte). Two nodes can share an ID; those are shown as \"possibly\".", fontSize = 11.sp, color = Pal.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { chosen = d.heardCounts.keys }) { Text("All") }
                    TextButton(onClick = { chosen = emptySet() }) { Text("None") }
                }
                if (d.heardCounts.isEmpty()) Text("Nothing heard yet with an identifiable sender.")
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(d.heardCounts.entries.toList()) { (g, n) ->
                        Row(Modifier.fillMaxWidth().clickable { chosen = if (g in chosen) chosen - g else chosen + g },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(g in chosen, { chosen = if (g in chosen) chosen - g else chosen + g })
                            Box(Modifier.padding(end = 6.dp).background(argb(Heard.color(g)), RoundedCornerShape(2.dp)).padding(6.dp))
                            Text("${Heard.label(g, d.known)} · $n", fontSize = 13.sp)
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

