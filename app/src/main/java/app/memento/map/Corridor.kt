package app.memento.map

import app.memento.activity.MapMath
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The high-definition band of a route pack: the tiles at [zooms] whose rectangle comes within [bufferM] metres of
 * the polyline ([lons]/[lats], already simplified), narrowed to [midM] at [Corridors.MID_Z] and to [fineM] from
 * [Corridors.FINE_Z] (each zoom weighs 4 times the one before for the same band). Stored in the pack manifest, so the
 * tile list can be computed again identically on resume.
 */
data class Corridor(
    val lons: List<Double>,
    val lats: List<Double>,
    val bufferM: Double,
    val zooms: IntRange,
    val fineM: Double = bufferM,
    val midM: Double = bufferM,
) {
    init {
        require(lons.size == lats.size) { "lons/lats" }
    }

    fun bufferAt(z: Int): Double = when {
        z >= Corridors.FINE_Z -> minOf(bufferM, midM, fineM)
        z == Corridors.MID_Z -> minOf(bufferM, midM)
        else -> bufferM
    }

    private val cache = HashMap<Int, LongArray>()

    /** The tiles of zoom [z], packed as `x shl 32 or y`, sorted; empty outside [zooms]. */
    fun tiles(z: Int): LongArray {
        if (z !in zooms || lons.isEmpty()) return LongArray(0)
        synchronized(cache) {
            return cache.getOrPut(z) { Corridors.select(lons, lats, bufferAt(z), z) }
        }
    }

    val tileCount: Long get() = zooms.sumOf { tiles(it).size.toLong() }

    fun contains(z: Int, x: Int, y: Int): Boolean = tiles(z).binarySearch(Corridors.pack(x, y)) >= 0
}

object Corridors {
    /** Earth circumference at the equator in Web Mercator metres. */
    private const val EQUATOR_M = 40_075_016.686

    /** The zoom held to a corridor's [Corridor.midM]. */
    const val MID_Z = 17

    /** The first zoom held to a corridor's [Corridor.fineM]. */
    const val FINE_Z = 18

    /** Simplification tolerance: far below any buffer, so the band moves by a few metres at most. */
    const val SIMPLIFY_M = 15.0

    fun pack(x: Int, y: Int): Long = (x.toLong() shl 32) or y.toLong()

    fun x(t: Long): Int = (t ushr 32).toInt()

    fun y(t: Long): Int = t.toInt()

    /** Ground size of one tile side at zoom [z] and latitude [lat]. */
    fun tileMetres(z: Int, lat: Double): Double = EQUATOR_M * cos(Math.toRadians(lat)) / 2.0.pow(z)

    /** The corridor of [route] at [zooms]: the route simplified with [SIMPLIFY_M] (a 10 000-point track shrinks a lot). */
    fun of(route: Route, bufferM: Double, zooms: IntRange, fineM: Double = bufferM): Corridor {
        val keep = simplify(route.lons, route.lats, SIMPLIFY_M)
        // rounded as the manifest writes them, so a resumed pack computes the very same tiles
        return Corridor(keep.map { round6(route.lons[it]) }, keep.map { round6(route.lats[it]) }, bufferM, zooms, fineM)
    }

    private fun round6(v: Double) = Math.round(v * 1e6) / 1e6

    /** Douglas–Peucker on a local metric plane, iterative (no stack overflow on long tracks). Indices kept, in order. */
    fun simplify(lons: DoubleArray, lats: DoubleArray, tolM: Double): IntArray {
        val n = lons.size
        if (n <= 2) return IntArray(n) { it }
        val lat0 = lats.average()
        val kx = MapSquare.M_PER_DEG_LON * cos(Math.toRadians(lat0))
        val xs = DoubleArray(n) { lons[it] * kx }
        val ys = DoubleArray(n) { lats[it] * MapSquare.M_PER_DEG_LAT }
        val keep = BooleanArray(n)
        keep[0] = true; keep[n - 1] = true
        val stack = ArrayDeque<Int>()
        stack.addLast(0); stack.addLast(n - 1)
        while (stack.isNotEmpty()) {
            val b = stack.removeLast()
            val a = stack.removeLast()
            var worst = -1.0
            var at = -1
            for (i in a + 1 until b) {
                val d = pointSegment(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
                if (d > worst) { worst = d; at = i }
            }
            if (at >= 0 && worst > tolM) {
                keep[at] = true
                stack.addLast(a); stack.addLast(at)
                stack.addLast(at); stack.addLast(b)
            }
        }
        return (0 until n).filter { keep[it] }.toIntArray()
    }

    /**
     * Tiles of zoom [z] whose rectangle lies within [bufferM] of a segment of the polyline. Each segment is cut into
     * pieces a few buffers long, then only the tiles of each piece's padded box are tested.
     */
    fun select(lons: List<Double>, lats: List<Double>, bufferM: Double, z: Int): LongArray {
        val zd = z.toDouble()
        val max = MapMath.tileCount(z) - 1
        val out = HashSet<Long>()
        val n = lons.size
        val xs = DoubleArray(n) { MapMath.worldX(lons[it], zd) }
        val ys = DoubleArray(n) { MapMath.worldY(lats[it], zd) }
        for (i in 0 until max(1, n - 1)) {
            val j = min(i + 1, n - 1)
            // Mercator is conformal: the buffer is a circle in tile units at the segment's latitude
            val b = bufferM / tileMetres(z, (lats[i] + lats[j]) / 2)
            val len = hypot(xs[j] - xs[i], ys[j] - ys[i])
            val pieces = max(1, ceil(len / max(4 * b, 4.0)).toInt())
            for (k in 0 until pieces) {
                val ax = xs[i] + (xs[j] - xs[i]) * k / pieces
                val ay = ys[i] + (ys[j] - ys[i]) * k / pieces
                val bx = xs[i] + (xs[j] - xs[i]) * (k + 1) / pieces
                val by = ys[i] + (ys[j] - ys[i]) * (k + 1) / pieces
                val tx0 = floor(min(ax, bx) - b).toInt().coerceIn(0, max)
                val tx1 = floor(max(ax, bx) + b).toInt().coerceIn(0, max)
                val ty0 = floor(min(ay, by) - b).toInt().coerceIn(0, max)
                val ty1 = floor(max(ay, by) + b).toInt().coerceIn(0, max)
                for (tx in tx0..tx1) for (ty in ty0..ty1) {
                    if (rectSegment(tx.toDouble(), ty.toDouble(), tx + 1.0, ty + 1.0, ax, ay, bx, by) <= b) out.add(pack(tx, ty))
                }
            }
        }
        return out.toLongArray().also { it.sort() }
    }

    fun pointSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun pointRect(px: Double, py: Double, x0: Double, y0: Double, x1: Double, y1: Double): Double =
        hypot(max(max(x0 - px, 0.0), px - x1), max(max(y0 - py, 0.0), py - y1))

    /** Distance between the rectangle and the segment: 0 when they touch, else reached at an endpoint or a corner. */
    fun rectSegment(x0: Double, y0: Double, x1: Double, y1: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        if (pointRect(ax, ay, x0, y0, x1, y1) == 0.0 || pointRect(bx, by, x0, y0, x1, y1) == 0.0) return 0.0
        if (crosses(ax, ay, bx, by, x0, y0, x1, y0) || crosses(ax, ay, bx, by, x1, y0, x1, y1) ||
            crosses(ax, ay, bx, by, x1, y1, x0, y1) || crosses(ax, ay, bx, by, x0, y1, x0, y0)
        ) return 0.0
        return minOf(
            minOf(pointRect(ax, ay, x0, y0, x1, y1), pointRect(bx, by, x0, y0, x1, y1)),
            minOf(pointSegment(x0, y0, ax, ay, bx, by), pointSegment(x1, y0, ax, ay, bx, by)),
            minOf(pointSegment(x1, y1, ax, ay, bx, by), pointSegment(x0, y1, ax, ay, bx, by)),
        )
    }

    private fun cross(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double) =
        (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    private fun crosses(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double): Boolean {
        val d1 = cross(cx, cy, dx, dy, ax, ay)
        val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy)
        val d4 = cross(ax, ay, bx, by, dx, dy)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }
}
