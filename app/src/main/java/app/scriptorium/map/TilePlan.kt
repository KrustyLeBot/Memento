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

/** What an offline pack downloads: Plan IGN zooms, terrain (DEM) zooms, and the estimate the budget is checked on. */
data class TilePlan(val square: Bbox, val planZooms: IntRange, val demZooms: IntRange?) {
    val planRanges: List<TileRange> get() = planZooms.map { TilePlans.range(square, it) }
    val demRanges: List<TileRange> get() = demZooms?.map { TilePlans.range(square, it) }.orEmpty()
    val tileCount: Long get() = planRanges.sumOf { it.count } + demRanges.sumOf { it.count }
    val estimatedBytes: Long get() = planRanges.sumOf { it.count } * TilePlans.PLAN_TILE_BYTES + demRanges.sumOf { it.count } * TilePlans.DEM_TILE_BYTES
}

object TilePlans {
    const val PLAN_TILE_BYTES = 25L * 1024
    const val DEM_TILE_BYTES = 60L * 1024
    const val ROUTE_BUDGET = 500L * 1024 * 1024
    const val PLAN_MIN_Z = 8
    const val PLAN_MAX_Z = 17
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
     * The route pack for [square]: Plan IGN from z8 up to the highest zoom ≤ 17 whose estimate, DEM z6–13
     * included, fits [budget]. Null when even z8 does not fit (a route the size of a country).
     */
    fun forRoute(square: Bbox, budget: Long = ROUTE_BUDGET): TilePlan? {
        var best: TilePlan? = null
        for (zmax in PLAN_MIN_Z..PLAN_MAX_Z) {
            val p = TilePlan(square, PLAN_MIN_Z..zmax, DEM_ZOOMS)
            if (p.estimatedBytes > budget) break
            best = p
        }
        return best
    }

    fun forZone(lon: Double, lat: Double): TilePlan =
        TilePlan(MapSquare.centered(lon, lat, ZONE_HALF_M), ZONE_ZOOMS, null)
}
