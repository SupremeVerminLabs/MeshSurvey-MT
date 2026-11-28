package meshsurvey.core.history

import meshsurvey.core.model.Source
import meshsurvey.core.model.TrackPoint
import meshsurvey.core.util.Geo

/** The few columns of a reception the history list needs. */
data class HistObs(val rxMs: Long, val lat: Double?, val lon: Double?, val fromNode: Long, val snr: Double?, val receiverNode: Long?,
                   val preset: String?, val protocol: String, val source: Source)

/** One calendar day (local time) of survey activity. */
data class DayRecord(
    val dayKey: Long,
    val firstMs: Long,
    val lastMs: Long,
    val trackPoints: Int,
    val distanceKm: Double,
    val receptions: Int,
    val withPosition: Int,
    val nodesHeard: Int,
    val bestSnr: Double?,
    val receivers: Set<Long>,
    val presets: Set<String>,
    val protocols: Set<String>,
    /** Places covered, most-visited first, with the number of sampled positions near each. */
    val places: List<Pair<String, Int>>,
    /** latMin, latMax, lonMin, lonMax of everything with a position, or null. */
    val bbox: DoubleArray?,
    val simulated: Boolean,
) {
    val primaryPlace: String? get() = places.firstOrNull()?.first
}

object History {
    const val DAY_MS = 86_400_000L

    /** Local calendar day number of [ms], given the local UTC offset at that moment. */
    fun dayKey(ms: Long, utcOffsetMs: (Long) -> Long): Long = (ms + utcOffsetMs(ms)).floorDiv(DAY_MS)

    /**
     * Groups track points and receptions into local days, newest first. Place names come from [places] (nearest named place
     * within 25 km of sampled positions); a day with no positions has no place.
     */
    fun days(track: List<TrackPoint>, obs: List<HistObs>, utcOffsetMs: (Long) -> Long, places: Places?): List<DayRecord> {
        class Acc {
            var first = Long.MAX_VALUE; var last = Long.MIN_VALUE; var tp = 0; var dist = 0.0; var rx = 0; var withPos = 0
            val nodes = HashSet<Long>(); var best: Double? = null; val receivers = HashSet<Long>(); val presets = HashSet<String>()
            val protocols = HashSet<String>(); val pts = ArrayList<Pair<Double, Double>>(); var sim = true
            var la0 = 90.0; var la1 = -90.0; var lo0 = 180.0; var lo1 = -180.0; var any = false
            var prev: TrackPoint? = null
            fun t(ms: Long) { if (ms < first) first = ms; if (ms > last) last = ms }
            fun pos(lat: Double, lon: Double) { any = true; la0 = minOf(la0, lat); la1 = maxOf(la1, lat); lo0 = minOf(lo0, lon); lo1 = maxOf(lo1, lon); pts += lat to lon }
        }
        val days = HashMap<Long, Acc>()
        for (p in track.sortedBy { it.ms }) {
            val a = days.getOrPut(dayKey(p.ms, utcOffsetMs)) { Acc() }
            a.t(p.ms); a.tp++; a.pos(p.lat, p.lon)
            if (p.source != Source.SIMULATED) a.sim = false
            a.prev?.let { q -> if (p.ms - q.ms < 10 * 60_000) a.dist += Geo.distanceM(q.lat, q.lon, p.lat, p.lon) / 1000 }
            a.prev = p
        }
        for (o in obs) {
            val a = days.getOrPut(dayKey(o.rxMs, utcOffsetMs)) { Acc() }
            a.t(o.rxMs); a.rx++; a.nodes += o.fromNode
            if (o.source != Source.SIMULATED) a.sim = false
            o.snr?.let { s -> a.best = maxOf(a.best ?: s, s) }
            o.receiverNode?.let { a.receivers += it }; o.preset?.let { a.presets += it }; a.protocols += o.protocol
            if (o.lat != null && o.lon != null) { a.withPos++; a.pos(o.lat, o.lon) }
        }
        return days.entries.sortedByDescending { it.key }.map { (k, a) ->
            val counts = HashMap<String, Int>()
            if (places != null && a.pts.isNotEmpty()) {
                val step = maxOf(1, a.pts.size / 400)
                for (i in a.pts.indices step step) places.nearest(a.pts[i].first, a.pts[i].second)?.let { counts[it.label] = (counts[it.label] ?: 0) + 1 }
            }
            DayRecord(k, a.first, a.last, a.tp, a.dist, a.rx, a.withPos, a.nodes.size, a.best, a.receivers, a.presets, a.protocols,
                counts.entries.sortedByDescending { it.value }.map { it.key to it.value },
                if (a.any) doubleArrayOf(a.la0, a.la1, a.lo0, a.lo1) else null, a.sim)
        }
    }

    /** "Concord, CA" or "Concord, CA & Walnut Creek, CA" when a second place has at least a quarter of the positions. */
    fun title(d: DayRecord): String {
        val p = d.places
        if (p.isEmpty()) return if (d.bbox == null) "no positions" else "away from named places"
        val second = p.getOrNull(1)?.takeIf { it.second * 4 >= p[0].second }
        return if (second != null) "${p[0].first} & ${second.first}" else p[0].first
    }
}
