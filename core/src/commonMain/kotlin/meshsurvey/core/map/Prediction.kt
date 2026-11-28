package meshsurvey.core.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import meshsurvey.core.model.Observation
import meshsurvey.core.model.TrackPoint
import kotlin.math.abs

/**
 * A predicted coverage map for ONE transmitter, imported from a propagation tool.
 *
 * Supported: GeoJSON iso-bands as exported by the Meshtastic Site Planner (site.meshtastic.org, "Export → GeoJSON"): each feature
 * is a (Multi)Polygon of the area where the predicted received signal is ≥ its `dbm` property; bands are nested. Other GeoJSON
 * with a numeric `dbm`, `rssi`, `signal_dbm` or `value` property on polygons is read the same way.
 * The PNG/KML exports carry colours only, not dBm values, so they cannot be compared and are not accepted.
 */
class PredictionMap(val name: String, val bands: List<Band>) {
    /** Polygons with holes: rings[0] outer, the rest holes. Points are (lat, lon). */
    class Poly(val rings: List<DoubleArray>) {
        // rings stored as interleaved lat,lon
        val minLat: Double; val maxLat: Double; val minLon: Double; val maxLon: Double
        init {
            val o = rings[0]
            var a = Double.MAX_VALUE; var b = -Double.MAX_VALUE; var c = Double.MAX_VALUE; var d = -Double.MAX_VALUE
            var i = 0
            while (i < o.size) { a = minOf(a, o[i]); b = maxOf(b, o[i]); c = minOf(c, o[i + 1]); d = maxOf(d, o[i + 1]); i += 2 }
            minLat = a; maxLat = b; minLon = c; maxLon = d
        }
        fun contains(lat: Double, lon: Double): Boolean {
            if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) return false
            if (!inRing(rings[0], lat, lon)) return false
            for (h in 1 until rings.size) if (inRing(rings[h], lat, lon)) return false
            return true
        }
        private fun inRing(r: DoubleArray, lat: Double, lon: Double): Boolean {
            var inside = false
            val n = r.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val yi = r[2 * i]; val xi = r[2 * i + 1]; val yj = r[2 * j]; val xj = r[2 * j + 1]
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            return inside
        }
    }

    /** Area where the predicted signal is at least [dbm]. */
    class Band(val dbm: Double, val polys: List<Poly>)

    val levels: List<Double> = bands.map { it.dbm }.distinct().sorted()
    /** Typical spacing between band levels: the prediction's resolution in dB. */
    val stepDb: Double = levels.zipWithNext { a, b -> b - a }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
    val floorDbm: Double? = levels.firstOrNull()
    val minLat = bands.flatMap { it.polys }.minOfOrNull { it.minLat } ?: 0.0
    val maxLat = bands.flatMap { it.polys }.maxOfOrNull { it.maxLat } ?: 0.0
    val minLon = bands.flatMap { it.polys }.minOfOrNull { it.minLon } ?: 0.0
    val maxLon = bands.flatMap { it.polys }.maxOfOrNull { it.maxLon } ?: 0.0
    private val byLevelDesc = bands.sortedByDescending { it.dbm }

    sealed class At {
        /** Beyond the area the prediction covers: no statement. */
        object Outside : At()
        /** Inside the predicted area's extent but in no band: predicted below the tool's coverage floor. */
        data class Below(val floorDbm: Double) : At()
        /** Predicted signal in [lo, hi) dBm (hi null = top band). */
        data class InBand(val lo: Double, val hi: Double?) : At()
    }

    fun at(lat: Double, lon: Double): At {
        if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) return At.Outside
        for (b in byLevelDesc) if (b.polys.any { it.contains(lat, lon) }) {
            val hi = levels.firstOrNull { it > b.dbm }
            return At.InBand(b.dbm, hi)
        }
        return At.Below(floorDbm ?: 0.0)
    }

    companion object {
        private val VALUE_KEYS = listOf("dbm", "rssi", "signal_dbm", "value")

        /** Returns the prediction, or an error message the user can act on. */
        fun parse(text: String, fileName: String): Pair<PredictionMap?, String?> {
            val root = try { Json.parseToJsonElement(text).jsonObject } catch (e: Exception) {
                return null to "Not a GeoJSON file (${e.message?.take(80)}). Export GeoJSON from the Site Planner; its PNG and KML exports hold colours, not signal values."
            }
            val feats = (root["features"] as? JsonArray) ?: return null to "No features in this GeoJSON."
            val bands = ArrayList<Band>()
            var skipped = 0
            for (fe in feats) {
                val f = fe as? JsonObject ?: continue
                val props = f["properties"] as? JsonObject
                val v = VALUE_KEYS.firstNotNullOfOrNull { k -> (props?.get(k) as? JsonPrimitive)?.doubleOrNull }
                val geom = f["geometry"] as? JsonObject
                if (v == null || geom == null) { skipped++; continue }
                val polys = when ((geom["type"] as? JsonPrimitive)?.content) {
                    "Polygon" -> listOfNotNull(poly(geom["coordinates"]))
                    "MultiPolygon" -> (geom["coordinates"] as? JsonArray)?.mapNotNull { poly(it) } ?: emptyList()
                    else -> { skipped++; continue }
                }
                if (polys.isNotEmpty()) bands += Band(v, polys)
            }
            if (bands.isEmpty()) return null to "This GeoJSON has no polygons with a signal value (dbm). Use the Site Planner's GeoJSON export."
            val name = ((root["properties"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: fileName.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".geojson").removeSuffix(".json")
            return PredictionMap(name, bands) to null
        }

        private fun poly(e: JsonElement?): Poly? {
            val rings = (e as? JsonArray)?.mapNotNull { r ->
                val pts = (r as? JsonArray) ?: return@mapNotNull null
                val a = DoubleArray(pts.size * 2)
                for ((i, p) in pts.withIndex()) {
                    val c = p.jsonArray
                    a[2 * i] = c[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null   // GeoJSON is [lon, lat]
                    a[2 * i + 1] = c[0].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
                }
                a.takeIf { it.size >= 6 }
            } ?: return null
            return if (rings.isEmpty()) null else Poly(rings)
        }
    }
}

/**
 * Measured vs predicted, per grid cell, for receptions heard DIRECTLY from the predicted transmitter.
 *
 * What it can say: where the transmitter was heard and how its measured RSSI compares with the predicted signal; where it was
 * heard although the tool predicted nothing; where a radio passed (connected, logging) inside predicted coverage and heard nothing
 * from it. That last one is weak evidence: the transmitter may simply not have sent anything while you were there.
 */
object PredictionCompare {
    enum class CellClass(val label: String) {
        AGREES("Measured within the predicted range"),
        STRONGER("Stronger than predicted"),
        WEAKER("Weaker than predicted"),
        HEARD_NOT_PREDICTED("Heard where nothing was predicted"),
        PREDICTED_NOT_HEARD("Passed through, not heard (weak evidence)"),
        BOTH_NONE("Passed through, nothing predicted or heard"),
    }

    class Cell(
        val key: Long,
        val cls: CellClass,
        val receptions: Int,
        val medianRssi: Double?,
        val medianSnr: Double?,
        /** Predicted range at the receptions (or the cell centre when nothing was heard). */
        val predLo: Double?, val predHi: Double?,
        /** Median of (measured − nearest edge of the predicted range); 0 inside the range. Null without receptions or prediction. */
        val residualDb: Double?,
        val trackPoints: Int,
        /** Median SNR below −5 dB: RSSI there is dominated by noise and reads higher than the actual signal. */
        val nearNoise: Boolean,
    )

    class Summary(
        val cells: Map<Long, Cell>,
        val heardCells: Int,
        val heardPredictedCells: Int,
        val heardNotPredictedCells: Int,
        val agrees: Int, val stronger: Int, val weaker: Int,
        val predictedNotHeard: Int,
        val bothNone: Int,
        /** Median residual over cells heard inside predicted coverage; negative = weaker than predicted. */
        val medianResidualDb: Double?,
        val nearNoiseCells: Int,
        val receptionsUsed: Int,
        val outsideReceptions: Int,
        val toleranceDb: Double,
    ) {
        fun lines(): List<String> {
            val l = ArrayList<String>()
            l += "Heard in $heardCells cells: $heardPredictedCells inside predicted coverage, $heardNotPredictedCells where nothing was predicted."
            if (heardPredictedCells > 0) l += "Of the $heardPredictedCells: $agrees within ±${toleranceDb.toInt()} dB of the predicted range, $stronger stronger, $weaker weaker."
            medianResidualDb?.let { r -> l += "Typical difference: " + when {
                abs(r) < 0.5 -> "none (median within the predicted range)."
                r < 0 -> "measured ${fmt1(-r)} dB weaker than predicted (median over cells; cable, antenna, clutter and receiver height are usual causes)."
                else -> "measured ${fmt1(r)} dB stronger than predicted (median over cells)."
            } }
            l += "Passed through predicted coverage without hearing it: $predictedNotHeard cells (weak evidence: it may not have transmitted then)."
            if (nearNoiseCells > 0) l += "$nearNoiseCells heard cells have median SNR below −5 dB; RSSI there reads high (noise), so 'stronger' may be overstated."
            l += "Based on $receptionsUsed receptions heard directly from the transmitter" + (if (outsideReceptions > 0) "; $outsideReceptions outside the predicted area were left out." else ".")
            return l
        }
        private fun fmt1(v: Double) = (kotlin.math.round(v * 10) / 10).toString()
    }

    /**
     * [obs]: receptions heard directly from the predicted transmitter, with positions. [track]: radio-connected track points (for
     * "passed, not heard"); pass an empty list to skip that. [toleranceDb]: allowance on each side of the predicted range.
     */
    fun compare(pred: PredictionMap, grid: Grid, obs: List<Observation>, track: List<TrackPoint>, toleranceDb: Double = 6.0): Summary {
        class Acc { val rssi = ArrayList<Double>(); val snr = ArrayList<Double>(); val res = ArrayList<Double>(); val lo = ArrayList<Double>(); val hi = ArrayList<Double>(); var below = 0; var inBand = 0; var track = 0 }
        val acc = HashMap<Long, Acc>()
        var used = 0; var outside = 0
        for (o in obs) {
            val lat = o.lat ?: continue; val lon = o.lon ?: continue; val rssi = o.rssi ?: continue
            val at = pred.at(lat, lon)
            if (at is PredictionMap.At.Outside) { outside++; continue }
            used++
            val a = acc.getOrPut(grid.key(lat, lon)) { Acc() }
            a.rssi += rssi.toDouble(); o.snr?.let { a.snr += it }
            when (at) {
                is PredictionMap.At.InBand -> {
                    a.inBand++; a.lo += at.lo; at.hi?.let { a.hi += it }
                    a.res += when { rssi < at.lo -> rssi - at.lo; at.hi != null && rssi > at.hi -> rssi - at.hi; else -> 0.0 }
                }
                is PredictionMap.At.Below -> a.below++
                else -> {}
            }
        }
        val cells = HashMap<Long, Cell>()
        for ((k, a) in acc) {
            val res = CellStats.median(a.res)
            val cls = when {
                a.inBand == 0 -> CellClass.HEARD_NOT_PREDICTED
                res == null || abs(res) <= toleranceDb -> CellClass.AGREES
                res > 0 -> CellClass.STRONGER
                else -> CellClass.WEAKER
            }
            val snr = CellStats.median(a.snr)
            cells[k] = Cell(k, cls, a.rssi.size, CellStats.median(a.rssi), snr, CellStats.median(a.lo), CellStats.median(a.hi),
                if (a.inBand > 0) res else null, 0, snr != null && snr < -5)
        }
        // cells passed through while logging, where nothing was heard from this transmitter
        val passed = HashMap<Long, Int>()
        for (t in track) if (t.radioReady) grid.key(t.lat, t.lon).let { passed[it] = (passed[it] ?: 0) + 1 }
        for ((k, n) in passed) {
            val c = cells[k]
            if (c != null) { cells[k] = Cell(c.key, c.cls, c.receptions, c.medianRssi, c.medianSnr, c.predLo, c.predHi, c.residualDb, n, c.nearNoise); continue }
            val b = grid.bounds(k)
            when (val at = pred.at(b.centerLat, b.centerLon)) {
                is PredictionMap.At.InBand -> cells[k] = Cell(k, CellClass.PREDICTED_NOT_HEARD, 0, null, null, at.lo, at.hi, null, n, false)
                is PredictionMap.At.Below -> cells[k] = Cell(k, CellClass.BOTH_NONE, 0, null, null, null, null, null, n, false)
                else -> {}
            }
        }
        val v = cells.values
        val heard = v.filter { it.receptions > 0 }
        return Summary(cells, heard.size, heard.count { it.cls != CellClass.HEARD_NOT_PREDICTED }, heard.count { it.cls == CellClass.HEARD_NOT_PREDICTED },
            v.count { it.cls == CellClass.AGREES }, v.count { it.cls == CellClass.STRONGER }, v.count { it.cls == CellClass.WEAKER },
            v.count { it.cls == CellClass.PREDICTED_NOT_HEARD }, v.count { it.cls == CellClass.BOTH_NONE },
            CellStats.median(heard.mapNotNull { it.residualDb }), heard.count { it.nearNoise }, used, outside, toleranceDb)
    }

    fun color(c: CellClass): Long = when (c) {
        CellClass.AGREES -> 0xFF2E7D32L
        CellClass.STRONGER -> 0xFF1565C0L
        CellClass.WEAKER -> 0xFFE65100L
        CellClass.HEARD_NOT_PREDICTED -> 0xFF6A1B9AL
        CellClass.PREDICTED_NOT_HEARD -> 0xFFC62828L
        CellClass.BOTH_NONE -> 0xFF9E9E9EL
    }
}
