package meshsurvey.core.history

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/** A named place near a position. */
data class Place(val name: String, val region: String, val country: String, val distanceKm: Double) {
    /** "Concord, CA" (state/region code where GeoNames has a letter code) or "Ottawa (CA)" (country code otherwise). */
    val label: String get() = if (region.isNotEmpty()) "$name, $region" else if (country.isNotEmpty()) "$name ($country)" else name
}

/**
 * Offline place names for naming survey days. Data: GeoNames places with more than 1000 inhabitants or administrative seats
 * (CC BY 4.0, see data/resources/places/README.md), one line per place: name, lat, lon, country code, region code.
 * Nothing is looked up online.
 */
class Places private constructor(
    private val names: Array<String>, private val regions: Array<String>, private val countries: Array<String>, private val lats: FloatArray, private val lons: FloatArray,
) {
    private val grid = HashMap<Int, IntArray>()

    init {
        val buckets = HashMap<Int, ArrayList<Int>>()
        for (i in lats.indices) buckets.getOrPut(cell(lats[i].toDouble(), lons[i].toDouble())) { ArrayList() } += i
        for ((k, v) in buckets) grid[k] = v.toIntArray()
    }

    val size get() = names.size

    private fun cell(lat: Double, lon: Double) = (floor(lat).toInt() + 90) * 400 + (floor(lon).toInt() + 180)

    /** Nearest place within [maxKm], or null (open sea, wilderness). */
    fun nearest(lat: Double, lon: Double, maxKm: Double = 25.0): Place? {
        var best = -1; var bestD = Double.MAX_VALUE
        val kx = cos(lat * PI / 180).coerceAtLeast(0.01)
        val la = floor(lat).toInt(); val lo = floor(lon).toInt()
        for (dy in -1..1) for (dx in -1..1) {
            val idx = grid[(la + dy + 90) * 400 + (lo + dx + 180)] ?: continue
            for (i in idx) {
                val y = (lats[i] - lat) * 111.32; val x = (lons[i] - lon) * 111.32 * kx
                val d = x * x + y * y
                if (d < bestD) { bestD = d; best = i }
            }
        }
        if (best < 0) return null
        val km = sqrt(bestD)
        return if (km <= maxKm) Place(names[best], regions[best], countries[best], km) else null
    }

    companion object {
        /** Parses tab-separated lines "name, lat, lon, country, region" (region may be empty). Bad lines are skipped. */
        fun parse(lines: Sequence<String>): Places {
            val n = ArrayList<String>(); val r = ArrayList<String>(); val c = ArrayList<String>(); val la = ArrayList<Float>(); val lo = ArrayList<Float>()
            for (line in lines) {
                val p = line.split('\t')
                if (p.size < 4) continue
                val lat = p[1].toFloatOrNull() ?: continue; val lon = p[2].toFloatOrNull() ?: continue
                if (lat < -90 || lat > 90 || lon < -180 || lon > 180) continue
                n += p[0]; r += p.getOrNull(4).orEmpty(); c += p[3]; la += lat; lo += lon
            }
            return Places(n.toTypedArray(), r.toTypedArray(), c.toTypedArray(), la.toFloatArray(), lo.toFloatArray())
        }
    }
}
