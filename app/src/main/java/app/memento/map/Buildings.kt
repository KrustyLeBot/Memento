package app.memento.map

import app.memento.activity.MapMath
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/** One building of a cell: [h] metres high, [rings] in microdegrees (outline first, then holes), unclosed. */
class Building(val h: Double, val rings: List<IntArray>)

/**
 * The buildings the 3D map extrudes: IGN BD TOPO V3 `batiment` (Géoplateforme WFS, Licence Ouverte Etalab 2.0), one
 * z15 tile ("cell", 850 m wide in the Alps) per request, slimmed down to footprints and heights. Same rules and same JSON as the
 * Windows app's core/buildings.py, whose docstring describes the format the page reads.
 */
object Buildings {
    const val Z = 15
    const val FORMAT = 1
    const val PAGE = 2000
    const val MAX_PAGES = 10
    const val MAX_BUILDINGS = 20000
    const val DEFAULT_HEIGHT = 6.0
    const val MAX_HEIGHT = 400.0
    private const val SIMPLIFY_UDEG = 1.5

    /**
     * A cell of buildings measured from real fetches (Nov 2026, 5×5 cells each): 33 KB on average around Annecy (90 KB
     * for the old town's cell), 14 KB around Chamonix, 3 KB in the Vanoise (Pralognan), 64 bytes for an empty one.
     */
    const val CELL_BYTES = 16L * 1024

    /** At most this many cells in a pack (about 10 MB): past it, the cells along the route or nearest the start. */
    const val MAX_PACK_CELLS = 600

    /** Along a route, the buildings within this distance of the track. */
    const val PACK_CORRIDOR_M = 1000.0

    class WfsException(message: String) : java.io.IOException(message)

    // ---- cell math ----

    fun cellBox(x: Int, y: Int): Bbox =
        Bbox(MapMath.lon(x.toDouble(), Z.toDouble()), MapMath.lat(y + 1.0, Z.toDouble()), MapMath.lon(x + 1.0, Z.toDouble()), MapMath.lat(y.toDouble(), Z.toDouble()))

    /** The cell holding a point, as `x shl 32 or y` ([Corridors.pack]). */
    fun cellOf(lon: Double, lat: Double): Long {
        val max = MapMath.tileCount(Z) - 1
        return Corridors.pack(
            floor(MapMath.worldX(lon, Z.toDouble())).toInt().coerceIn(0, max),
            floor(MapMath.worldY(lat, Z.toDouble())).toInt().coerceIn(0, max),
        )
    }

    /** The cells meeting [b]: an edge exactly on a cell border does not pull in the next. */
    fun cells(b: Bbox): TileRange = TilePlans.range(b, Z)

    /**
     * The cells an offline pack holds for its 3D [square]: all of them up to [MAX_PACK_CELLS]; past that, those within
     * [PACK_CORRIDOR_M] of the route's [corridor] line when there is one, else the ones nearest the square's centre (a
     * zone's centre is the start). Sorted, packed as [Corridors.pack].
     */
    fun packCells(square: Bbox, corridor: Corridor?): LongArray {
        val all = cells(square)
        if (all.count <= MAX_PACK_CELLS) {
            val out = LongArray(all.count.toInt())
            var i = 0
            all.forEach { x, y -> out[i++] = Corridors.pack(x, y) }
            return out.also { it.sort() }
        }
        // a zone's one-point corridor (its high-definition disc) is no line to follow: nearest the start, as without
        val pool = if (corridor != null && corridor.lons.size > 1) {
            Corridors.select(corridor.lons, corridor.lats, PACK_CORRIDOR_M, Z).filter {
                Corridors.x(it) in all.x0..all.x1 && Corridors.y(it) in all.y0..all.y1
            }
        } else {
            val list = ArrayList<Long>()
            all.forEach { x, y -> list.add(Corridors.pack(x, y)) }
            list
        }
        val cx = (all.x0 + all.x1 + 1) / 2.0
        val cy = (all.y0 + all.y1 + 1) / 2.0
        return pool.sortedWith(compareBy<Long> { hypot(Corridors.x(it) + 0.5 - cx, Corridors.y(it) + 0.5 - cy) }.thenBy { it })
            .take(MAX_PACK_CELLS).toLongArray().also { it.sort() }
    }

    // ---- WFS ----

    fun wfsUrl(x: Int, y: Int, start: Int = 0): String {
        val b = cellBox(x, y)
        // the bare "EPSG:4326" suffix makes the server read the box in another axis order: 0 features
        return "https://data.geopf.fr/wfs/ows?SERVICE=WFS&VERSION=2.0.0&REQUEST=GetFeature&TYPENAMES=BDTOPO_V3:batiment" +
            "&OUTPUTFORMAT=application/json&SRSNAME=EPSG:4326&PROPERTYNAME=geometrie,hauteur,altitude_minimale_sol," +
            "altitude_maximale_toit,nombre_d_etages&COUNT=$PAGE&STARTINDEX=$start" +
            "&BBOX=${f7(b.s)},${f7(b.w)},${f7(b.n)},${f7(b.e)},urn:ogc:def:crs:EPSG::4326"
    }

    private fun f7(v: Double) = String.format(java.util.Locale.ROOT, "%.7f", v)

    /** The features of one WFS answer; [WfsException] when it is not a GeoJSON feature collection. */
    fun parsePage(text: String): List<Any?> {
        val doc = try { Json.parse(text) } catch (e: Json.JsonException) { throw WfsException("Bâtiments : réponse illisible") }
        return doc.asMap()?.get("features").asList() ?: throw WfsException("Bâtiments : réponse WFS inattendue")
    }

    fun isLastPage(features: List<Any?>) = features.size < PAGE

    /** The slim JSON of a cell, [get] doing the requests. HTTP errors and malformed answers are [WfsException]s. */
    fun fetchCell(x: Int, y: Int, get: (String) -> HttpResult): ByteArray {
        val features = ArrayList<Any?>()
        for (page in 0 until MAX_PAGES) {
            val r = get(wfsUrl(x, y, page * PAGE))
            if (r.code != 200) throw WfsException("Bâtiments : HTTP ${r.code}")
            val got = parsePage(String(r.body, Charsets.UTF_8))
            features.addAll(got)
            if (isLastPage(got)) break
        }
        return encode(x, y, slim(features, x, y)).toByteArray()
    }

    // ---- slimming ----

    private fun num(v: Any?): Double? = (v as? Double)?.takeIf { it.isFinite() }

    /** `hauteur`, else roof top minus lowest ground, else floors × 3 + 1, else a default; at most [MAX_HEIGHT]. */
    fun height(props: Map<String, Any?>): Double {
        var h = num(props["hauteur"])
        if (h == null || h <= 0) {
            val top = num(props["altitude_maximale_toit"])
            val ground = num(props["altitude_minimale_sol"])
            val floors = num(props["nombre_d_etages"])
            h = when {
                top != null && ground != null && top - ground > 0 -> max(top - ground, 3.0)
                floors != null && floors > 0 -> floors * 3 + 1
                else -> DEFAULT_HEIGHT
            }
        }
        return floor(minOf(h, MAX_HEIGHT) * 10 + 0.5) / 10
    }

    private fun udeg(v: Double): Int = floor(v * 1e6 + 0.5).toInt()

    /** A ring in microdegrees (x, y interleaved), closing point, repeats and near-collinear vertices removed. */
    fun ring(coords: Any?): IntArray? {
        val list = coords.asList() ?: return null
        val xs = ArrayList<Int>(list.size)
        val ys = ArrayList<Int>(list.size)
        for (c in list) {
            val p = c.asList() ?: return null
            val lon = num(p.getOrNull(0)) ?: return null
            val lat = num(p.getOrNull(1)) ?: return null
            val x = udeg(lon)
            val y = udeg(lat)
            if (xs.isEmpty() || xs.last() != x || ys.last() != y) { xs.add(x); ys.add(y) }
        }
        if (xs.size > 1 && xs.first() == xs.last() && ys.first() == ys.last()) { xs.removeAt(xs.size - 1); ys.removeAt(ys.size - 1) }
        var ox: List<Int> = xs
        var oy: List<Int> = ys
        if (xs.size > 3) { // one pass: each vertex against its kept predecessor and its original successor
            val kx = ArrayList<Int>(xs.size)
            val ky = ArrayList<Int>(xs.size)
            val n = xs.size
            for (i in 0 until n) {
                val ax = if (kx.isEmpty()) xs[n - 1] else kx.last()
                val ay = if (ky.isEmpty()) ys[n - 1] else ky.last()
                val bx = xs[(i + 1) % n]
                val by = ys[(i + 1) % n]
                val length = hypot((bx - ax).toDouble(), (by - ay).toDouble())
                val cross = abs((xs[i] - ax).toDouble() * (by - ay) - (ys[i] - ay).toDouble() * (bx - ax))
                if (length > 0 && cross / length < SIMPLIFY_UDEG && kx.size + n - i > 3) continue
                kx.add(xs[i]); ky.add(ys[i])
            }
            ox = kx; oy = ky
        }
        if (ox.size < 3) return null
        return IntArray(ox.size * 2) { if (it % 2 == 0) ox[it / 2] else oy[it / 2] }
    }

    private fun polygons(geom: Map<String, Any?>?): List<Any?> = when (geom?.get("type")) {
        "Polygon" -> listOf(geom["coordinates"])
        "MultiPolygon" -> geom["coordinates"].asList().orEmpty()
        else -> emptyList()
    }

    /**
     * The buildings whose outline's box centre lies in the cell; a multipolygon gives one entry per part. Duplicates
     * (the same feature on two WFS pages) are dropped.
     */
    fun slim(features: List<Any?>, x: Int, y: Int): List<Building> {
        val out = ArrayList<Building>()
        val seen = HashSet<Any>()
        val b = cellBox(x, y)
        for (f in features) {
            val o = f.asMap() ?: continue
            val id = o["id"]
            if (id != null && !seen.add(id)) continue
            val h = height(o["properties"].asMap().orEmpty())
            for (poly in polygons(o["geometry"].asMap())) {
                val rings = poly.asList() ?: continue
                val outer = ring(rings.firstOrNull()) ?: continue
                var x0 = Int.MAX_VALUE; var x1 = Int.MIN_VALUE; var y0 = Int.MAX_VALUE; var y1 = Int.MIN_VALUE
                for (i in outer.indices step 2) {
                    x0 = minOf(x0, outer[i]); x1 = maxOf(x1, outer[i]); y0 = minOf(y0, outer[i + 1]); y1 = maxOf(y1, outer[i + 1])
                }
                val cx = (x0.toDouble() + x1) / 2e6
                val cy = (y0.toDouble() + y1) / 2e6
                if (!(cx >= b.w && cx < b.e && cy >= b.s && cy < b.n)) continue
                out.add(Building(h, listOf(outer) + rings.drop(1).mapNotNull { ring(it) }))
                if (out.size >= MAX_BUILDINGS) return out
            }
        }
        return out
    }

    fun encode(x: Int, y: Int, buildings: List<Building>): String = buildString {
        val b = cellBox(x, y)
        val ox = udeg(b.w)
        val oy = udeg(b.s)
        append("{\"v\":").append(FORMAT).append(",\"z\":").append(Z).append(",\"x\":").append(x).append(",\"y\":").append(y)
        append(",\"o\":[").append(ox).append(',').append(oy).append("],\"b\":[")
        buildings.forEachIndexed { k, bld ->
            if (k > 0) append(',')
            append('[').append(Json.num(bld.h, 1))
            for (r in bld.rings) {
                append(",[")
                var px = ox
                var py = oy
                for (i in r.indices step 2) {
                    if (i > 0) append(',')
                    append(r[i] - px).append(',').append(r[i + 1] - py)
                    px = r[i]; py = r[i + 1]
                }
                append(']')
            }
            append(']')
        }
        append("]}")
    }

    /** Relative path in the browsing cache and in a pack folder. */
    fun path(x: Int, y: Int) = "${TileKey.KIND_BUILDINGS}/$Z/$x/$y.json"
}
