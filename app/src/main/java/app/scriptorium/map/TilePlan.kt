package app.scriptorium.map

import app.scriptorium.activity.MapMath
import kotlin.math.floor

/** The tiles of one zoom level that intersect a box, x0..x1 × y0..y1 inclusive. */
data class TileRange(val z: Int, val x0: Int, val x1: Int, val y0: Int, val y1: Int) {
    val count: Long get() = (x1 - x0 + 1).toLong() * (y1 - y0 + 1)

    fun forEach(f: (x: Int, y: Int) -> Unit) {
        for (x in x0..x1) for (y in y0..y1) f(x, y)
    }
}

/** One batch of a download: the tiles of one kind and zoom, over the square or along the corridor only. */
class TileGroup(val kind: String, val z: Int, val inCorridor: Boolean, val count: Long, val forEach: ((x: Int, y: Int) -> Unit) -> Unit)

/**
 * What an offline pack downloads: aerial-photo zooms over the whole square, the higher ones along the route only
 * ([corridor]), terrain (DEM) zooms over the square, and the estimate the budget is checked on.
 */
data class TilePlan(val square: Bbox, val orthoZooms: IntRange, val demZooms: IntRange?, val corridor: Corridor? = null) {
    val orthoRanges: List<TileRange> get() = orthoZooms.map { TilePlans.range(square, it) }
    val demRanges: List<TileRange> get() = demZooms?.map { TilePlans.range(square, it) }.orEmpty()
    val corridorCount: Long get() = corridor?.tileCount ?: 0L
    val tileCount: Long get() = orthoRanges.sumOf { it.count } + corridorCount + demRanges.sumOf { it.count }
    val estimatedBytes: Long
        get() = (orthoRanges.sumOf { it.count } + corridorCount) * TilePlans.ORTHO_TILE_BYTES + demRanges.sumOf { it.count } * TilePlans.DEM_TILE_BYTES

    /** The highest aerial-photo zoom anywhere in the pack. */
    val topZoom: Int get() = corridor?.zooms?.takeIf { !it.isEmpty() }?.last ?: orthoZooms.last

    /** Relief first (the 3D view needs it), then the photo over the square, then the corridor, zoom by zoom. */
    fun groups(): List<TileGroup> {
        val out = ArrayList<TileGroup>()
        for (r in demRanges) out.add(TileGroup(TileKey.KIND_DEM, r.z, false, r.count) { f -> r.forEach(f) })
        for (r in orthoRanges) out.add(TileGroup(TileKey.KIND_ORTHO, r.z, false, r.count) { f -> r.forEach(f) })
        corridor?.let { c ->
            for (z in c.zooms) {
                val t = c.tiles(z)
                out.add(TileGroup(TileKey.KIND_ORTHO, z, true, t.size.toLong()) { f -> for (v in t) f(Corridors.x(v), Corridors.y(v)) })
            }
        }
        return out
    }

    /** What the pack holds, said to the user before downloading. */
    fun detailLabel(): String {
        val c = corridor
        return if (c == null || c.zooms.isEmpty()) {
            if (orthoZooms.last >= TilePlans.ORTHO_MAX_Z) "Haute définition sur toute la zone" else "Définition moyenne sur toute la zone"
        } else {
            "Haute définition le long du tracé (±${TilePlans.distanceLabel(c.bufferM)}), moyenne ailleurs"
        }
    }
}

object TilePlans {
    /**
     * IGN orthophoto JPEGs measured at z12-17 (3x3 tiles per zoom, Nov 2026): 10-20 KB on average in the Alps
     * (Vanoise, Chamonix), 17-23 KB in towns (Grenoble, Annecy), never above 26 KB; a real 184 km loop came to 57 % of
     * a 22 KB estimate. 15 KB keeps the shown estimate close to the download; the real bytes are checked anyway.
     */
    const val ORTHO_TILE_BYTES = 15L * 1024
    const val DEM_TILE_BYTES = 60L * 1024
    const val ROUTE_BUDGET = 500L * 1024 * 1024
    const val ORTHO_MIN_Z = 8
    const val ORTHO_MAX_Z = 17
    val DEM_ZOOMS = 6..13
    /** Route packs: the whole square up to this zoom, higher zooms only along the route. */
    const val MID_Z = 14
    const val CORRIDOR_M = 1000.0

    /**
     * What gives way when the corridor does not fit the budget, in order: z17 first, then the buffer narrows. Past the
     * last step the square's zoom goes down.
     */
    val CORRIDOR_STEPS = listOf(1000.0 to 17, 1000.0 to 16, 500.0 to 16, 500.0 to 15)

    /** The zone pack recorded around the start without a route: 2D only, 20 km square, z8–15. */
    const val ZONE_HALF_M = 10_000.0
    val ZONE_ZOOMS = 8..15

    fun range(b: Bbox, z: Int): TileRange {
        val max = MapMath.tileCount(z) - 1
        val zd = z.toDouble()
        // a box edge exactly on a tile border does not pull in the next tile
        val eps = 1e-9
        val x0 = floor(MapMath.worldX(b.w, zd)).toInt().coerceIn(0, max)
        val x1 = floor(MapMath.worldX(b.e, zd) - eps).toInt().coerceIn(x0, max)
        val y0 = floor(MapMath.worldY(b.n, zd)).toInt().coerceIn(0, max)
        val y1 = floor(MapMath.worldY(b.s, zd) - eps).toInt().coerceIn(y0, max)
        return TileRange(z, x0, x1, y0, y1)
    }

    /**
     * The route pack for [route]: the aerial photo of its square from z8 to [MID_Z] and, within [CORRIDOR_M] of the
     * track, up to z17; relief z6–13 over the square. When that outgrows [budget], [CORRIDOR_STEPS] are tried, then a
     * lower square zoom, then the square alone. A route small enough to get its whole square at z17 gets that. Null
     * when even the square at z8 does not fit (a route the size of a country).
     */
    fun forRoute(route: Route, budget: Long = ROUTE_BUDGET): TilePlan? {
        val box = route.bbox ?: return null
        val square = MapSquare.of(box)
        TilePlan(square, ORTHO_MIN_Z..ORTHO_MAX_Z, DEM_ZOOMS).let { if (it.estimatedBytes <= budget) return it }
        val line = Corridors.of(route, 0.0, IntRange.EMPTY)
        val counts = HashMap<Pair<Double, Int>, Long>()
        fun count(buf: Double, z: Int) = counts.getOrPut(buf to z) { Corridors.select(line.lons, line.lats, buf, z).size.toLong() }
        for (mid in MID_Z downTo ORTHO_MIN_Z) {
            val base = TilePlan(square, ORTHO_MIN_Z..mid, DEM_ZOOMS)
            if (base.estimatedBytes > budget) continue
            for ((buf, top) in CORRIDOR_STEPS) {
                if (top <= mid) continue
                val extra = (mid + 1..top).sumOf { count(buf, it) } * ORTHO_TILE_BYTES
                if (base.estimatedBytes + extra <= budget) return base.copy(corridor = line.copy(bufferM = buf, zooms = mid + 1..top))
            }
        }
        return squareOnly(square, budget)
    }

    /** The square alone, from z8 up to the highest zoom ≤ [MID_Z] that fits. */
    fun squareOnly(square: Bbox, budget: Long = ROUTE_BUDGET): TilePlan? {
        var best: TilePlan? = null
        for (zmax in ORTHO_MIN_Z..MID_Z) {
            val p = TilePlan(square, ORTHO_MIN_Z..zmax, DEM_ZOOMS)
            if (p.estimatedBytes > budget) break
            best = p
        }
        return best
    }

    /** "1,5 km", "500 m". */
    fun distanceLabel(m: Double): String =
        if (m >= 1000) Json.num(m / 1000, 1).replace('.', ',') + " km" else "${m.toInt()} m"

    fun forZone(lon: Double, lat: Double): TilePlan =
        TilePlan(MapSquare.centered(lon, lat, ZONE_HALF_M), ZONE_ZOOMS, null)
}
