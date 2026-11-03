package app.memento.map

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The buildings cells (Buildings.kt): cell maths, heights, slimming, the JSON shared with the Windows core, packs. */
class BuildingsTest {
    private val x = 16941
    private val y = 11670 // Annecy's old town

    private fun testdata(name: String) =
        listOf(File("../testdata/map"), File("testdata/map")).first { it.isDirectory }.resolve(name)

    @Test
    fun cellMaths() {
        val b = Buildings.cellBox(x, y)
        assertEquals(6.119384765625, b.w, 1e-12)
        assertEquals(45.897654534346884, b.s, 1e-9)
        assertEquals(850.0, b.widthM, 5.0) // 1.2 km at the equator, 850 m in the Alps
        assertEquals(Corridors.pack(x, y), Buildings.cellOf(6.129, 45.899))
        assertEquals(1L, Buildings.cells(b).count) // edges on the borders pull in no neighbour
        assertEquals(9L, Buildings.cells(Bbox(b.w - 1e-4, b.s - 1e-4, b.e + 1e-4, b.n + 1e-4)).count)
    }

    @Test
    fun pathsAndUrls() {
        assertEquals(TileKey("buildings", 15, x, y), TileKey.parseBuildings("/buildings/15/$x/$y"))
        assertNull(TileKey.parseBuildings("/buildings/14/1/1"))
        assertNull(TileKey.parseBuildings("/buildings/15/32768/1"))
        assertNull(TileKey.parseBuildings("/buildings/15/a/1"))
        assertNull(TileKey.parse("/t/buildings/15/1/1"))
        assertEquals("buildings/15/$x/$y.json", TileKey("buildings", 15, x, y).path)
        assertEquals(Buildings.path(x, y), TileKey("buildings", 15, x, y).path)
        assertEquals("application/json", TileKey("buildings", 15, x, y).mime)
        val url = Buildings.wfsUrl(x, y, 2000)
        assertTrue(url, url.contains("TYPENAMES=BDTOPO_V3:batiment") && url.contains("STARTINDEX=2000") && url.contains("COUNT=${Buildings.PAGE}"))
        assertTrue(url, url.endsWith("&BBOX=45.8976545,6.1193848,45.9052999,6.1303711,urn:ogc:def:crs:EPSG::4326"))
    }

    @Test
    fun heightFallsBackFromHauteurToAltitudesToFloorsToDefault() {
        fun h(vararg p: Pair<String, Any?>) = Buildings.height(mapOf(*p))
        assertEquals(21.5, h("hauteur" to 21.5, "altitude_maximale_toit" to 474.0, "altitude_minimale_sol" to 450.6), 0.0)
        assertEquals(23.4, h("hauteur" to null, "altitude_maximale_toit" to 474.04, "altitude_minimale_sol" to 450.6), 0.0)
        assertEquals(3.0, h("hauteur" to 0.0, "altitude_maximale_toit" to 451.0, "altitude_minimale_sol" to 450.6), 0.0)
        assertEquals(13.0, h("altitude_maximale_toit" to 440.0, "altitude_minimale_sol" to 450.6, "nombre_d_etages" to 4.0), 0.0)
        assertEquals(Buildings.DEFAULT_HEIGHT, h("hauteur" to "12"), 0.0)
        assertEquals(Buildings.DEFAULT_HEIGHT, h(), 0.0)
        assertEquals(Buildings.MAX_HEIGHT, h("hauteur" to 9999.0), 0.0)
    }

    @Test
    fun sharedFixtureGivesTheSameCellAsTheWindowsCore() {
        val features = Buildings.parsePage(testdata("buildings-wfs.json").readText())
        val cell = Buildings.encode(x, y, Buildings.slim(features, x, y))
        assertEquals(testdata("buildings-cell.json").readText().trim(), cell)
        val slim = Buildings.slim(features, x, y)
        assertEquals(listOf(21.5, 11.8, 11.8, 13.0, 6.0, 7.0), slim.map { it.h })
        assertEquals(listOf(1, 2, 1, 1, 1, 1), slim.map { it.rings.size }) // the hole kept, the multipolygon split
        assertEquals(8, slim[1].rings[0].size) // the near-collinear vertex is gone: 4 points
    }

    @Test
    fun fetchPagesUntilAShortPageAndRefusesErrors() {
        val one = Json.parse(testdata("buildings-wfs.json").readText()).asMap()!!["features"].asList()!!.first()
        val calls = ArrayList<String>()
        val sizes = listOf(Buildings.PAGE, 3)
        val body = { n: Int -> "{\"features\":[" + List(n) { "{\"id\":\"b$it-${calls.size}\",\"properties\":{},\"geometry\":${geometryJson(one)}}" }.joinToString(",") + "]}" }
        Buildings.fetchCell(x, y) { url -> calls.add(url); HttpResult(200, "application/json", body(sizes[calls.size - 1]).toByteArray()) }
        assertEquals(listOf("0", "2000"), calls.map { it.substringAfter("STARTINDEX=").substringBefore('&') })
        try {
            Buildings.fetchCell(x, y) { HttpResult(503, "text/html", "busy".toByteArray()) }
            fail("an HTTP error must not be cached as an empty cell")
        } catch (e: Buildings.WfsException) {
            assertTrue(e.message!!.contains("503"))
        }
        try {
            Buildings.fetchCell(x, y) { HttpResult(200, "text/xml", "<ows:ExceptionReport/>".toByteArray()) }
            fail("an exception report is not a cell")
        } catch (_: Buildings.WfsException) {
        }
    }

    private fun geometryJson(feature: Any?): String {
        val rings = feature.asMap()!!["geometry"].asMap()!!["coordinates"].asList()!!
        fun enc(v: Any?): String = when (v) {
            is List<*> -> v.joinToString(",", "[", "]") { enc(it) }
            is Double -> Json.num(v, 8)
            else -> "null"
        }
        return "{\"type\":\"MultiPolygon\",\"coordinates\":${enc(rings)}}"
    }

    @Test
    fun packCellsAreTheWholeSquareOrTheRouteOrTheNearestToTheStart() {
        val town = MapSquare.centered(6.129, 45.899, 2000.0)
        val all = Buildings.packCells(town, null)
        assertEquals(Buildings.cells(town).count, all.size.toLong())
        assertTrue(all.size < 40)
        val zone = MapSquare.centered(6.5, 45.5, 20_000.0) // a 40 km 3D zone: ~2 200 cells
        val near = Buildings.packCells(zone, null)
        assertEquals(Buildings.MAX_PACK_CELLS, near.size)
        val centre = Buildings.cellOf(6.5, 45.5)
        assertTrue(near.binarySearch(centre) >= 0)
        val line = Corridor(listOf(6.35, 6.65), listOf(45.5, 45.5), 1000.0, 15..16)
        val along = Buildings.packCells(zone, line)
        assertTrue("${along.size}", along.size in 50..Buildings.MAX_PACK_CELLS)
        assertTrue(along.all { Buildings.cellBox(Corridors.x(it), Corridors.y(it)).let { b -> b.s < 45.52 && b.n > 45.48 } })
        assertArrayEquals(along, Buildings.packCells(zone, line)) // the same on resume
    }

    @Test
    fun packsWithReliefCarryTheirBuildingsInTheEstimateAndThePlan() {
        val route = TilePlans.squareOnly(MapSquare.centered(6.129, 45.899, 2500.0))!!
        assertTrue(route.buildings && route.buildingCells.isNotEmpty())
        val without = route.copy(buildings = false)
        assertEquals(route.buildingCells.size * Buildings.CELL_BYTES, route.estimatedBytes - without.estimatedBytes)
        assertEquals(route.buildingCells.size.toLong(), route.tileCount - without.tileCount)
        val groups = route.groups()
        assertEquals(listOf("dem", "buildings", "ortho"), groups.map { it.kind }.distinct())
        val keys = ArrayList<TileKey>()
        groups.first { it.kind == "buildings" }.forEach { cx, cy -> keys.add(TileKey("buildings", 15, cx, cy)) }
        assertEquals(route.buildingCells.size, keys.size)
        assertTrue(keys.all { it.path.startsWith("buildings/15/") && it.path.endsWith(".json") })
        // a flat (2D) plan has none
        assertTrue(route.copy(demZooms = null).buildingCells.isEmpty())
        // the 3D zone: its 3D square only, at most MAX_PACK_CELLS (~10 MB)
        val zone = TilePlans.forZone3d(6.5, 45.5)
        assertEquals(Buildings.MAX_PACK_CELLS, zone.buildingCells.size)
        assertTrue(Pack.KIND_BUILDINGS in Zones.KINDS_3D)
        val pack = Pack("z-1", "Zone", 0, zone.square, zone.orthoZooms.last, Zones.KINDS_3D, 0, false, false, corridor = zone.corridor, relief = zone.demSquare)
        assertEquals(zone.tileCount, Pack.parse(pack.toJson()).plan().tileCount)
        assertTrue(pack.copy(kinds = setOf(Pack.KIND_ORTHO, Pack.KIND_DEM)).plan().buildingCells.isEmpty()) // packs from before
    }
}
