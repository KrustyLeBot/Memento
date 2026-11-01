package app.scriptorium.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.scriptorium.activity.MapMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/** A coloured polyline on the map. */
class MapLine(val lats: DoubleArray, val lons: DoubleArray, val color: Color)

/** A dot on the map (start, end, current position). */
class MapDot(val lat: Double, val lon: Double, val color: Color)

/**
 * OpenStreetMap raster tiles: a memory cache in front of a disk cache (size-limited, oldest files
 * purged) in front of the network. Only tiles the user looks at are fetched, one at a time per
 * requester slot, with an identifying User-Agent, as the tile usage policy asks.
 */
object Tiles {
    private const val MAX_DISK = 100L * 1024 * 1024
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(4)
    private val memory = object : LruCache<String, Bitmap>(80) {}
    private val requested = HashSet<String>()
    private val failed = HashMap<String, Long>()
    private var trimmed = false

    /** Bumped whenever a tile arrives, so canvases redraw. */
    var version by mutableIntStateOf(0)
        private set

    var userAgent = "Scriptorium (https://github.com/KrustyLeBot/Griffonote)"

    fun get(context: Context, z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        memory.get(key)?.let { return it }
        synchronized(requested) {
            if (key in requested) return null
            val retryAt = failed[key]
            if (retryAt != null && System.currentTimeMillis() < retryAt) return null
            requested.add(key)
        }
        val dir = File(context.applicationContext.cacheDir, "tiles")
        scope.launch {
            gate.withPermit {
                try {
                    val file = File(dir, "$key.png")
                    var bytes: ByteArray? = if (file.isFile) file.readBytes() else null
                    if (bytes != null) file.setLastModified(System.currentTimeMillis())
                    if (bytes == null) {
                        bytes = download(z, x, y)
                        file.parentFile?.mkdirs()
                        file.writeBytes(bytes)
                    }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        memory.put(key, bmp)
                        version++
                    }
                } catch (_: Exception) {
                    synchronized(requested) { failed[key] = System.currentTimeMillis() + 30_000 }
                } finally {
                    synchronized(requested) { requested.remove(key) }
                }
            }
            trimOnce(dir)
        }
        return null
    }

    private fun download(z: Int, x: Int, y: Int): ByteArray {
        val c = URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", userAgent)
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** Keeps the disk cache under [MAX_DISK], dropping the least recently used tiles first. */
    private fun trimOnce(dir: File) {
        synchronized(this) {
            if (trimmed) return
            trimmed = true
        }
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_DISK * 0.8) break
            total -= f.length()
            f.delete()
        }
    }
}

/**
 * A slippy map with the tracks drawn on top. Offline (or while tiles load) the tracks stay on a plain
 * background. [interactive] false: it only shows; the parent decides what a tap does. The view is
 * fitted to the tracks again when [fitKey] changes; [follow] keeps a dot centred (live recording).
 */
@Composable
fun TrackMap(
    lines: List<MapLine>,
    dots: List<MapDot>,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    follow: MapDot? = null,
    fitKey: Any? = Unit,
) {
    val context = LocalContext.current
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh
    val label = MaterialTheme.colorScheme.onSurfaceVariant

    var centerLat by remember { mutableDoubleStateOf(45.0) }
    var centerLon by remember { mutableDoubleStateOf(6.0) }
    var zoom by remember { mutableDoubleStateOf(12.0) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val tilesVersion = Tiles.version

    LaunchedEffect(viewSize, fitKey) {
        if (viewSize.width == 0) return@LaunchedEffect
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        fun add(la: Double, lo: Double) {
            minLat = minOf(minLat, la); maxLat = maxOf(maxLat, la); minLon = minOf(minLon, lo); maxLon = maxOf(maxLon, lo)
        }
        lines.forEach { l -> for (i in l.lats.indices) add(l.lats[i], l.lons[i]) }
        dots.forEach { add(it.lat, it.lon) }
        if (maxLat >= minLat) {
            centerLat = (minLat + maxLat) / 2
            centerLon = (minLon + maxLon) / 2
            zoom = if (follow != null) 16.0 else MapMath.fitZoom(minLat, minLon, maxLat, maxLon, viewSize.width.toDouble(), viewSize.height.toDouble())
        }
    }
    LaunchedEffect(follow?.lat, follow?.lon) {
        follow?.let { centerLat = it.lat; centerLon = it.lon }
    }

    Box(modifier.onSizeChanged { viewSize = it }) {
        Canvas(
            Modifier.fillMaxSize().then(
                if (interactive) Modifier.pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        val tz = floor(zoom).toInt()
                        val tilePx = MapMath.TILE * 2.0.pow(zoom - tz)
                        val cx = MapMath.worldX(centerLon, tz.toDouble()) - pan.x / tilePx
                        val cy = MapMath.worldY(centerLat, tz.toDouble()) - pan.y / tilePx
                        centerLon = MapMath.lon(cx, tz.toDouble()).coerceIn(-180.0, 180.0)
                        centerLat = MapMath.lat(cy, tz.toDouble()).coerceIn(-85.0, 85.0)
                        if (zoomChange != 1f) {
                            zoom = (zoom + ln(zoomChange.toDouble()) / ln(2.0)).coerceIn(MapMath.MIN_ZOOM.toDouble(), MapMath.MAX_ZOOM.toDouble())
                        }
                    }
                } else Modifier,
            ),
        ) {
            val w = size.width.toDouble()
            val h = size.height.toDouble()
            drawRect(bg)
            drawTiles(context, w, h, centerLat, centerLon, zoom, tilesVersion)
            drawRect(Color.White.copy(alpha = 0.28f)) // calms the map so the track stands out
            drawLines(lines, w, h, centerLat, centerLon, zoom)
            for (d in dots) {
                val p = project(d.lat, d.lon, w, h, centerLat, centerLon, zoom)
                drawCircle(Color.White, 9.dp.toPx(), Offset(p.first.toFloat(), p.second.toFloat()))
                drawCircle(d.color, 6.dp.toPx(), Offset(p.first.toFloat(), p.second.toFloat()))
            }
        }
        Text(
            "© OpenStreetMap", style = MaterialTheme.typography.labelSmall, color = label,
            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
        )
    }
}

private fun project(lat: Double, lon: Double, w: Double, h: Double, cLat: Double, cLon: Double, zoom: Double): Pair<Double, Double> {
    val tz = floor(zoom)
    val tilePx = MapMath.TILE * 2.0.pow(zoom - tz)
    val x = w / 2 + (MapMath.worldX(lon, tz) - MapMath.worldX(cLon, tz)) * tilePx
    val y = h / 2 + (MapMath.worldY(lat, tz) - MapMath.worldY(cLat, tz)) * tilePx
    return x to y
}

private fun DrawScope.drawTiles(context: Context, w: Double, h: Double, cLat: Double, cLon: Double, zoom: Double, @Suppress("UNUSED_PARAMETER") version: Int) {
    val tz = floor(zoom).toInt().coerceIn(MapMath.MIN_ZOOM, MapMath.MAX_ZOOM)
    val tilePx = MapMath.TILE * 2.0.pow(zoom - tz)
    val cx = MapMath.worldX(cLon, tz.toDouble())
    val cy = MapMath.worldY(cLat, tz.toDouble())
    val x0 = floor(cx - w / 2 / tilePx).toInt()
    val x1 = floor(cx + w / 2 / tilePx).toInt()
    val y0 = floor(cy - h / 2 / tilePx).toInt()
    val y1 = floor(cy + h / 2 / tilePx).toInt()
    val count = MapMath.tileCount(tz)
    for (ty in y0..y1) {
        if (ty < 0 || ty >= count) continue
        for (tx in x0..x1) {
            val bmp = Tiles.get(context, tz, MapMath.wrapX(tx, tz), ty) ?: continue
            val left = floor(w / 2 + (tx - cx) * tilePx).toInt()
            val top = floor(h / 2 + (ty - cy) * tilePx).toInt()
            val right = floor(w / 2 + (tx + 1 - cx) * tilePx).toInt()
            val bottom = floor(h / 2 + (ty + 1 - cy) * tilePx).toInt()
            drawImage(bmp.asImageBitmap(), dstOffset = IntOffset(left, top), dstSize = IntSize(right - left, bottom - top))
        }
    }
}

private fun DrawScope.drawLines(lines: List<MapLine>, w: Double, h: Double, cLat: Double, cLon: Double, zoom: Double) {
    val tz = floor(zoom)
    val tilePx = MapMath.TILE * 2.0.pow(zoom - tz)
    val cx = MapMath.worldX(cLon, tz)
    val cy = MapMath.worldY(cLat, tz)
    for (l in lines) {
        if (l.lats.size < 2) continue
        val path = Path()
        var lastX = Double.NaN
        var lastY = Double.NaN
        for (i in l.lats.indices) {
            val x = w / 2 + (MapMath.worldX(l.lons[i], tz) - cx) * tilePx
            val y = h / 2 + (MapMath.worldY(l.lats[i], tz) - cy) * tilePx
            if (!lastX.isNaN() && i != l.lats.lastIndex && (x - lastX) * (x - lastX) + (y - lastY) * (y - lastY) < 4.0) continue
            if (lastX.isNaN()) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
            lastX = x; lastY = y
        }
        drawPath(path, Color.Black.copy(alpha = 0.6f), style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, l.color, style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
