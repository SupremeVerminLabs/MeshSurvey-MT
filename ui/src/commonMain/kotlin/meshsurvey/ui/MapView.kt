package meshsurvey.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/** Web Mercator in normalised world units (0..1). */
object Mercator {
    fun x(lon: Double) = (lon + 180.0) / 360.0
    fun y(lat: Double): Double {
        val r = lat.coerceIn(-85.05, 85.05) * PI / 180
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2
    }
    fun lon(x: Double) = x * 360.0 - 180.0
    fun lat(y: Double) = atan(sinh(PI * (1 - 2 * y))) * 180 / PI
    private fun sinh(v: Double) = (exp(v) - exp(-v)) / 2
}

class MapState(lat: Double = 20.0, lon: Double = 0.0, zoom: Double = 2.0) {
    var cx by mutableStateOf(Mercator.x(lon))
    var cy by mutableStateOf(Mercator.y(lat))
    var zoom by mutableStateOf(zoom)
    val worldPx get() = 256.0 * 2.0.pow(zoom)

    fun centerOn(lat: Double, lon: Double, z: Double? = null) { cx = Mercator.x(lon); cy = Mercator.y(lat); z?.let { zoom = it } }
    fun pan(dx: Float, dy: Float) {
        cx = (cx - dx / worldPx).let { it - floor(it) }
        cy = (cy - dy / worldPx).coerceIn(0.0, 1.0)
    }
    fun zoomBy(f: Float, focus: Offset, size: Size) {
        val fx = cx + (focus.x - size.width / 2) / worldPx
        val fy = cy + (focus.y - size.height / 2) / worldPx
        zoom = (zoom + ln(f.toDouble()) / ln(2.0)).coerceIn(2.0, 19.0)
        cx = fx - (focus.x - size.width / 2) / worldPx
        cy = (fy - (focus.y - size.height / 2) / worldPx).coerceIn(0.0, 1.0)
    }
}

/** Converts between lat/lon and screen pixels for one frame. */
class Projector(private val s: MapState, val size: Size) {
    private val w = s.worldPx
    fun toOffset(lat: Double, lon: Double): Offset {
        var dx = Mercator.x(lon) - s.cx
        if (dx > 0.5) dx -= 1.0 else if (dx < -0.5) dx += 1.0
        return Offset((size.width / 2 + dx * w).toFloat(), (size.height / 2 + (Mercator.y(lat) - s.cy) * w).toFloat())
    }
    fun toLatLon(o: Offset): Pair<Double, Double> {
        val x = s.cx + (o.x - size.width / 2) / w
        val y = s.cy + (o.y - size.height / 2) / w
        return Mercator.lat(y) to Mercator.lon(x - floor(x))
    }
    /** Ground metres represented by one screen pixel at latitude [lat]. */
    fun metersPerPx(lat: Double) = 40_075_016.686 * cos(lat * PI / 180) / w
}

/**
 * Tile loader with the OpenStreetMap tile usage policy in mind: at most 2 concurrent
 * downloads, disk caching (platform side), no bulk/prefetch downloading.
 */
class TileLoader(private val platform: PlatformServices, private val scope: CoroutineScope) {
    var urlTemplate by mutableStateOf("https://tile.openstreetmap.org/{z}/{x}/{y}.png")
    var offlineOnly by mutableStateOf(false)
    var attribution by mutableStateOf("© OpenStreetMap contributors")
    private val images = mutableStateMapOf<String, ImageBitmap>()
    private val order = ArrayDeque<String>()
    private val inflight = HashSet<String>()
    private val missing = HashMap<String, Int>()

    fun clearMemory() { images.clear(); order.clear(); missing.clear() }

    fun get(z: Int, x: Int, y: Int): ImageBitmap? {
        val url = urlTemplate.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")
        val key = (if (offlineOnly) "o:" else "n:") + url
        images[key]?.let { return it }
        if (key in inflight || (missing[key] ?: 0) >= 2 || inflight.size >= 2) return null
        inflight += key
        scope.launch {
            val img = try { platform.tileBytes(url, offlineOnly)?.let { decodeImage(it) } } catch (_: Exception) { null }
            inflight -= key
            if (img != null) {
                images[key] = img; order.addLast(key)
                while (order.size > 400) images.remove(order.removeFirst())
            } else missing[key] = (missing[key] ?: 0) + 1
        }
        return null
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SlippyMap(
    state: MapState,
    tiles: TileLoader,
    modifier: Modifier = Modifier,
    onTap: (Projector, Offset) -> Unit = { _, _ -> },
    overlay: DrawScope.(Projector) -> Unit = {},
) {
    // plain holder (not snapshot state): written during draw, read by gesture handlers
    val sizeRef = remember { arrayOf(Size(1f, 1f)) }
    val tapHandler by rememberUpdatedState(onTap)
    Box(modifier.clipToBounds()) {  // tiles and overlays must not draw over the filter bar or panels
        Canvas(
            Modifier.matchParentSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        state.pan(pan.x, pan.y)
                        if (zoom != 1f) state.zoomBy(zoom, centroid, sizeRef[0])
                    }
                }
                .pointerInput(Unit) { detectTapGestures { off -> tapHandler(Projector(state, sizeRef[0]), off) } }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Scroll) {
                                val ch = e.changes.firstOrNull() ?: continue
                                val d = ch.scrollDelta.y
                                if (d != 0f) state.zoomBy(if (d > 0) 0.8f else 1.25f, ch.position, sizeRef[0])
                                ch.consume()
                            }
                        }
                    }
                }
        ) {
            sizeRef[0] = size
            drawRect(Color(0xFFE8E4DC))
            val z = floor(state.zoom).toInt().coerceIn(0, 19)
            val n = 1 shl z
            val tilePx = 256.0 * 2.0.pow(state.zoom - z)
            val left = state.cx * state.worldPx - size.width / 2
            val top = state.cy * state.worldPx - size.height / 2
            val x0 = floor(left / tilePx).toInt(); val x1 = floor((left + size.width) / tilePx).toInt()
            val y0 = floor(top / tilePx).toInt().coerceAtLeast(0); val y1 = floor((top + size.height) / tilePx).toInt().coerceAtMost(n - 1)
            val sz = tilePx.roundToInt() + 1
            for (ty in y0..y1) for (tx in x0..x1) {
                val wx = ((tx % n) + n) % n
                val img = tiles.get(z, wx, ty) ?: continue
                drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset((tx * tilePx - left).roundToInt(), (ty * tilePx - top).roundToInt()), dstSize = IntSize(sz, sz))
            }
            overlay(Projector(state, size))
        }
    }
}

