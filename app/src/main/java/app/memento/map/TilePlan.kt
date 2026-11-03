package app.memento.map

import app.memento.activity.MapMath
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
 * ([corridor]), terrain (DEM) zooms over the square, the buildings of the 3D area ([Buildings.packCells]), and the
 * estimate the budget is checked on.
 */
data class TilePlan(
    val square: Bbox,
    val orthoZooms: IntRange,
    val demZooms: IntRange?,
    val corridor: Corridor? = null,
    /** Where the relief goes when it covers less than the photo (a 3D zone smaller than its 2D one); null = [square]. */
    val demSquare: Bbox? = null,
    /** With the buildings cells of the 3D area (packs with relief only). */
    val buildings: Boolean = false,
) {
    /** The z15 cells of buildings, packed as [Corridors.pack]; computed once (a long route's corridor takes a while). */
    val buildingCells: LongArray by lazy {
        if (buildings && demZooms != null) Buildings.packCells(demSquare ?: square, corridor) else LongArray(0)
    }
    val orthoRanges: List<TileRange> get() = orthoZooms.map { TilePlans.range(square, it) }
    val demRanges: List<TileRange> get() = demZooms?.map { TilePlans.range(demSquare ?: square, it) }.orEmpty()
    val corridorCount: Long get() = corridor?.tileCount ?: 0L
    val tileCount: Long get() = orthoRanges.sumOf { it.count } + corridorCount + demRanges.sumOf { it.count } + buildingCells.size
    val estimatedBytes: Long
        get() = (orthoRanges.sumOf { it.count } + corridorCount) * TilePlans.ORTHO_TILE_BYTES + demRanges.sumOf { it.count } * TilePlans.DEM_TILE_BYTES +
            buildingCells.size * Buildings.CELL_BYTES

    /** The highest aerial-photo zoom anywhere in the pack. */
    val topZoom: Int get() = corridor?.zooms?.takeIf { !it.isEmpty() }?.last ?: orthoZooms.last

    /** Relief first (the 3D view needs it), the buildings, then the photo over the square, then the corridor, zoom by zoom. */
    fun groups(): List<TileGroup> {
        val out = ArrayList<TileGroup>()
        for (r in demRanges) out.add(TileGroup(TileKey.KIND_DEM, r.z, false, r.count) { f -> r.forEach(f) })
        val cells = buildingCells
        if (cells.isNotEmpty()) out.add(TileGroup(TileKey.KIND_BUILDINGS, Buildings.Z, false, cells.size.toLong()) { f -> for (v in cells) f(Corridors.x(v), Corridors.y(v)) })
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
        return if (c != null && c.lons.size == 1 && !c.zooms.isEmpty()) {
            "Haute définition à ${TilePlans.distanceLabel(c.bufferM)} autour du départ, moyenne ailleurs"
        } else if (c == null || c.zooms.isEmpty()) {
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
    const val DEM_TILE_BYTES = 130L * 1024  // Terrarium tiles measured at z10–13 in the Alps: 124–148 KB
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

    /**
     * The zone pack recorded around the start without a route: the photo z8–13 over a square whose side is a setting
     * ([ZONE_2D_KM], 40 km by default). The owner zooms in for the relief, not the photo: deeper zooms show the z13
     * photo enlarged (the tile server's overzoom, up to the map's z19), and the relief keeps its full precision (it
     * stops at z13 anyway). Each zoom level is four times the previous one: z14–15 would be 15 times the size.
     */
    val ZONE_ZOOMS = 8..13
    const val ZONE_KM_DEFAULT = 40
    const val ZONE_KM_STEP = 5
    val ZONE_2D_KM = 10..80
    val ZONE_3D_KM = 5..80

    /** Latitude the settings estimate for until a recording has started somewhere (tile sizes in metres depend on it). */
    const val DEFAULT_LAT = 45.5

    /**
     * The optional 3D zone: relief ([DEM_ZOOMS]) and peaks over its own square (a setting, [ZONE_3D_KM]), the photo
     * z8–15 over both squares (3D is never drawn without imagery, even when larger than the 2D zone), and the photo at
     * [ZONE_HD_Z] within [ZONE_HD_M] of the start (a one-point corridor, stored in the manifest, so a resume fetches
     * the same tiles).
     */
    const val ZONE_HD_Z = 16
    const val ZONE_HD_M = 5_000.0

    /**
     * Ceiling on a zone pack, estimated and real. The largest settings (80 km both) are estimated at about 230 MB, so
     * the plan rarely gives way; when it does, the z16 disc goes first, then the square's top zoom (z15, then z14…).
     * The real bytes are held to it as for a route.
     */
    const val ZONE_BUDGET = 250L * 1024 * 1024

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
     * when even the square at z8 does not fit (a route the size of a country). No photo zoom above [photoMax] (the
     * map's setting): the map shows that level enlarged beyond it, so a finer tile would never be drawn.
     */
    fun forRoute(route: Route, budget: Long = ROUTE_BUDGET, photoMax: Int = ORTHO_MAX_Z): TilePlan? {
        val box = route.bbox ?: return null
        val square = MapSquare.of(box)
        val zTop = minOf(ORTHO_MAX_Z, photoMax)
        TilePlan(square, ORTHO_MIN_Z..zTop, DEM_ZOOMS, buildings = true).let { if (it.estimatedBytes <= budget) return it }
        val line = Corridors.of(route, 0.0, IntRange.EMPTY)
        val counts = HashMap<Pair<Double, Int>, Long>()
        fun count(buf: Double, z: Int) = counts.getOrPut(buf to z) { Corridors.select(line.lons, line.lats, buf, z).size.toLong() }
        for (mid in minOf(MID_Z, zTop) downTo ORTHO_MIN_Z) {
            val base = TilePlan(square, ORTHO_MIN_Z..mid, DEM_ZOOMS, buildings = true)
            if (base.estimatedBytes > budget) continue
            for ((buf, stepTop) in CORRIDOR_STEPS) {
                val top = minOf(stepTop, zTop)
                if (top <= mid) continue
                val extra = (mid + 1..top).sumOf { count(buf, it) } * ORTHO_TILE_BYTES
                if (base.estimatedBytes + extra <= budget) return base.copy(corridor = line.copy(bufferM = buf, zooms = mid + 1..top))
            }
        }
        return squareOnly(square, budget)
    }

    /**
     * The square alone, from z8 up to the highest zoom ≤ [MID_Z] that fits; when even the relief does not fit (a route
     * spanning a few hundred km), a 2D-only plan rather than none: offline, the map is then drawn flat.
     */
    fun squareOnly(square: Bbox, budget: Long = ROUTE_BUDGET): TilePlan? {
        for (dem in listOf(DEM_ZOOMS, null)) {
            var best: TilePlan? = null
            for (zmax in ORTHO_MIN_Z..MID_Z) {
                val p = TilePlan(square, ORTHO_MIN_Z..zmax, dem, buildings = dem != null)
                if (p.estimatedBytes > budget) break
                best = p
            }
            // a flat pack that cannot even reach z11 (a "route" across a continent) is no use on the ground
            if (best != null) return best.takeIf { dem != null || it.orthoZooms.last >= 11 }
        }
        return null
    }

    /** "1,5 km", "500 m". */
    fun distanceLabel(m: Double): String =
        if (m >= 1000) Json.num(m / 1000, 1).replace('.', ',') + " km" else "${m.toInt()} m"

    /** A zone side as the sliders offer it: in [range], on a [ZONE_KM_STEP] step. */
    fun snapKm(km: Int, range: IntRange): Int =
        (Math.round(km.toDouble() / ZONE_KM_STEP).toInt() * ZONE_KM_STEP).coerceIn(range)

    /** The square of side [sideKm] around a point. */
    fun zoneSquare(lon: Double, lat: Double, sideKm: Int): Bbox = MapSquare.centered(lon, lat, sideKm * 500.0)

    fun forZone(lon: Double, lat: Double, sideKm: Int = ZONE_KM_DEFAULT): TilePlan =
        fitZone(TilePlan(zoneSquare(lon, lat, sideKm), ZONE_ZOOMS, null))

    /**
     * The 3D zone with the 2D one it completes: photo over both squares, relief and peaks over the 3D square
     * ([TilePlan.demSquare]), the high-definition disc around the start (no wider than the 3D square).
     */
    fun forZone3d(lon: Double, lat: Double, side2dKm: Int = ZONE_KM_DEFAULT, side3dKm: Int = ZONE_KM_DEFAULT): TilePlan {
        val sq2 = zoneSquare(lon, lat, side2dKm)
        val sq3 = zoneSquare(lon, lat, side3dKm)
        // no high-definition photo around the start: the owner zooms in for the relief, not the photo
        return fitZone(TilePlan(union(sq2, sq3), ZONE_ZOOMS, DEM_ZOOMS, null, sq3, buildings = true))
    }

    /** What answering yes to the 3D zone adds to the 2D zone already on its way: the figure asked about. */
    fun zone3dExtraBytes(lon: Double, lat: Double, side2dKm: Int = ZONE_KM_DEFAULT, side3dKm: Int = ZONE_KM_DEFAULT): Long =
        (forZone3d(lon, lat, side2dKm, side3dKm).estimatedBytes - forZone(lon, lat, side2dKm).estimatedBytes).coerceAtLeast(0)

    /** Within [ZONE_BUDGET]: without the z16 disc, then with a lower top zoom over the square. */
    private fun fitZone(p: TilePlan): TilePlan {
        if (p.estimatedBytes <= ZONE_BUDGET) return p
        var q = p.copy(corridor = null)
        while (q.estimatedBytes > ZONE_BUDGET && q.orthoZooms.last > ORTHO_MIN_Z) q = q.copy(orthoZooms = ORTHO_MIN_Z until q.orthoZooms.last)
        return q
    }

    fun union(a: Bbox, b: Bbox) = Bbox(minOf(a.w, b.w), minOf(a.s, b.s), maxOf(a.e, b.e), maxOf(a.n, b.n))

    // as the manifest writes a corridor's line, so a resumed pack computes the very same tiles
    private fun round6(v: Double) = Math.round(v * 1e6) / 1e6
}
