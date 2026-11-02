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

/** What an offline pack downloads: aerial-photo zooms, terrain (DEM) zooms, and the estimate the budget is checked on. */
data class TilePlan(val square: Bbox, val orthoZooms: IntRange, val demZooms: IntRange?) {
    val orthoRanges: List<TileRange> get() = orthoZooms.map { TilePlans.range(square, it) }
    val demRanges: List<TileRange> get() = demZooms?.map { TilePlans.range(square, it) }.orEmpty()
    val tileCount: Long get() = orthoRanges.sumOf { it.count } + demRanges.sumOf { it.count }
    val estimatedBytes: Long get() = orthoRanges.sumOf { it.count } * TilePlans.ORTHO_TILE_BYTES + demRanges.sumOf { it.count } * TilePlans.DEM_TILE_BYTES
}

object TilePlans {
    /**
     * IGN orthophoto JPEGs measured at z12-17 (3x3 tiles per zoom, Nov 2026): 10-20 KB on average in the Alps
     * (Vanoise, Chamonix), 17-23 KB in towns (Grenoble, Annecy), never above 26 KB. 22 KB keeps the estimate on the
     * safe side for a mixed route; the download still checks the real bytes against the budget.
     */
    const val ORTHO_TILE_BYTES = 22L * 1024
    const val DEM_TILE_BYTES = 60L * 1024
    const val ROUTE_BUDGET = 500L * 1024 * 1024
    const val ORTHO_MIN_Z = 8
    const val ORTHO_MAX_Z = 17
    val DEM_ZOOMS = 6..13

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
     * The route pack for [square]: the aerial photo from z8 up to the highest zoom ≤ 17 whose estimate, DEM z6–13
     * included, fits [budget]. Null when even z8 does not fit (a route the size of a country).
     */
    fun forRoute(square: Bbox, budget: Long = ROUTE_BUDGET): TilePlan? {
        var best: TilePlan? = null
        for (zmax in ORTHO_MIN_Z..ORTHO_MAX_Z) {
            val p = TilePlan(square, ORTHO_MIN_Z..zmax, DEM_ZOOMS)
            if (p.estimatedBytes > budget) break
            best = p
        }
        return best
    }

    fun forZone(lon: Double, lat: Double): TilePlan =
        TilePlan(MapSquare.centered(lon, lat, ZONE_HALF_M), ZONE_ZOOMS, null)
}
