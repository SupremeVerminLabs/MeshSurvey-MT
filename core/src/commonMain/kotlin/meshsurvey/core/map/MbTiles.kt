package meshsurvey.core.map

import meshsurvey.core.store.SqlDriver

/**
 * Read-only MBTiles 1.3 raster source (SQLite: `metadata(name,value)`, `tiles(zoom_level, tile_column,
 * tile_row, tile_data)` with TMS row numbering). Vector tiles (format "pbf") are detected and refused:
 * rendering them would need a vector style engine, which Lite deliberately does not include.
 */
class MbTiles(private val db: SqlDriver) {
    val metadata: Map<String, String> = try {
        db.query("SELECT name, value FROM metadata") { (it.string(0) ?: "") to (it.string(1) ?: "") }.toMap()
    } catch (_: Exception) { emptyMap() }

    val format: String = metadata["format"]?.lowercase() ?: "png"
    val isRaster: Boolean get() = format in setOf("png", "jpg", "jpeg", "webp")
    val name: String get() = metadata["name"] ?: "MBTiles"
    val attribution: String? get() = metadata["attribution"]
    val minZoom: Int? get() = metadata["minzoom"]?.toIntOrNull()
    val maxZoom: Int? get() = metadata["maxzoom"]?.toIntOrNull()
    /** west, south, east, north */
    val bounds: List<Double>? get() = metadata["bounds"]?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 4 }

    /** XYZ (slippy) coordinates in, TMS lookup inside. Null if absent or vector. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (!isRaster || z < 0 || z > 24) return null
        val tmsY = (1 shl z) - 1 - y
        return db.query("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            listOf(z.toLong(), x.toLong(), tmsY.toLong())) { it.blob(0) }.firstOrNull()
    }

    fun describe(): String = buildString {
        append(name); append(" · "); append(format.uppercase())
        if (minZoom != null || maxZoom != null) append(" · zoom ${minZoom ?: "?"}–${maxZoom ?: "?"}")
        if (!isRaster) append(" · vector tiles are not supported (raster PNG/JPEG/WebP only)")
    }

    fun close() = db.close()
}
