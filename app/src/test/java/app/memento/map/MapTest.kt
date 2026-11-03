package app.memento.map

import app.memento.activity.Fit
import app.memento.activity.Geo
import app.memento.activity.Sport
import app.memento.activity.Track
import app.memento.activity.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import kotlin.math.cos

// Synthetic places only: the repository is public.
private const val LAT0 = 45.0
private const val LON0 = 6.0
private val K_LON = MapSquare.M_PER_DEG_LON * cos(Math.toRadians(LAT0))

/** A point [east] and [north] metres from (45, 6). */
private fun at(east: Double, north: Double) = (LAT0 + north / MapSquare.M_PER_DEG_LAT) to (LON0 + east / K_LON)

private fun routeOf(pts: List<Triple<Double, Double, Double?>>) =
    Route.of("test", pts.map { (e, n, ele) -> val (la, lo) = at(e, n); Triple(la, lo, ele) })

class MapSquareTest {
    @Test
    fun squareIsCentredWithMarginAndSquareInMetres() {
        // 4 km wide, 2 km high around (45, 6)
        val (s, w) = at(-2000.0, -1000.0)
        val (n, e) = at(2000.0, 1000.0)
        val sq = MapSquare.of(w, s, e, n)
        val half = 2000.0 * 1.25
        assertEquals(LON0 - half / K_LON, sq.w, 1e-6)
        assertEquals(LON0 + half / K_LON, sq.e, 1e-6)
        assertEquals(LAT0 - half / MapSquare.M_PER_DEG_LAT, sq.s, 1e-6)
        assertEquals(LAT0 + half / MapSquare.M_PER_DEG_LAT, sq.n, 1e-6)
        assertEquals(sq.widthM, sq.heightM, 1.0)
        assertTrue(sq.covers(Bbox(w, s, e, n)))
    }

    @Test
    fun tinyRouteGetsAtLeastAKilometreSquare() {
        val sq = MapSquare.of(LON0, LAT0, LON0 + 1e-5, LAT0 + 1e-5)
        assertEquals(1000.0, sq.heightM, 1.0)
        assertEquals(1000.0, sq.widthM, 1.0)
    }
}

class TilePlanTest {
    @Test
    fun rangeOfABoxAtKnownZooms() {
        val world = Bbox(-179.9, -85.0, 179.9, 85.0)
        assertEquals(1L, TilePlans.range(world, 0).count)
        assertEquals(4L, TilePlans.range(world, 1).count)
        // a point: exactly one tile, the one the slippy-map formula gives
        val p = TilePlans.range(Bbox(LON0, LAT0, LON0, LAT0), 12)
        assertEquals(1L, p.count)
        assertEquals(2116, p.x0)
        assertEquals(1473, p.y0)
    }

    @Test
    fun edgeOnATileBorderDoesNotPullTheNextTile() {
        // longitude 0 is the border between tiles x=1 and x=2 at z2: a box ending there stays in x=1
        val r = TilePlans.range(Bbox(-90.0, 10.0, 0.0, 20.0), 2)
        assertEquals(1, r.x0)
        assertEquals(1, r.x1)
    }

    @Test
    fun squareAloneFitsTheBudgetAtTheHighestZoomPossible() {
        // a 10 km hike: the square alone goes up to the mid zoom (higher zooms come from the corridor)
        val small = MapSquare.of(LON0, LAT0, LON0 + 10_000 / K_LON, LAT0 + 3000 / MapSquare.M_PER_DEG_LAT)
        val p = TilePlans.squareOnly(small)!!
        assertEquals(TilePlans.MID_Z, p.orthoZooms.last)
        assertEquals(8, p.orthoZooms.first)
        assertEquals(6..13, p.demZooms)
        assertTrue(p.estimatedBytes <= TilePlans.ROUTE_BUDGET)

        // a 200 km ride: lower zmax, still within budget, and one zoom more would not be
        val big = MapSquare.of(LON0, LAT0, LON0 + 200_000 / K_LON, LAT0 + 20_000 / MapSquare.M_PER_DEG_LAT)
        // its relief alone is over the budget (~3 500 tiles of 130 KB at z13): a 2D-only plan, not a refusal
        val q = TilePlans.squareOnly(big)!!
        assertNull(q.demZooms)
        assertTrue(q.orthoZooms.last in 11..TilePlans.MID_Z)
        assertTrue(q.estimatedBytes <= TilePlans.ROUTE_BUDGET)
        if (q.orthoZooms.last < TilePlans.MID_Z) {
            val next = TilePlan(big, 8..q.orthoZooms.last + 1, null)
            assertTrue(next.estimatedBytes > TilePlans.ROUTE_BUDGET)
        }
    }

    @Test
    fun continentSizedRouteIsRefused() {
        assertNull(TilePlans.squareOnly(Bbox(-10.0, 35.0, 30.0, 60.0)))
    }

    @Test
    fun zonePackIsAbout250TilesPlusItsDisc() {
        val z = TilePlans.forZone(LON0, LAT0)
        assertEquals(8..13, z.orthoZooms)
        assertNull(z.demZooms)
        assertTrue("${z.tileCount}", z.tileCount - z.corridorCount in 150..350)
        assertEquals(40_000.0, z.square.widthM, 1.0)
        assertEquals(14..18, z.corridor!!.zooms)
    }
}

class PackTest {
    private val square = Bbox(5.9, 44.9, 6.1, 45.1)
    private val created = 1_790_000_000_000L
    private val pack = Pack("r-1", "Tour « test »", created, square, 16, setOf(Pack.KIND_ORTHO, Pack.KIND_DEM, Pack.KIND_PEAKS), 123_456_789, complete = true, hasRoute = true)

    @Test
    fun manifestRoundTrip() {
        assertEquals(pack, Pack.parse(pack.toJson()))
    }

    @Test
    fun expiresTwentyFourHoursAfterCreation() {
        val day = 24L * 3600 * 1000
        assertFalse(pack.expired(created + day - 1))
        assertTrue(pack.expired(created + day))
        val other = pack.copy(id = "r-2", created = created + 1000)
        assertEquals(listOf(pack), Pack.toPurge(listOf(pack, other), created + day))
    }

    @Test
    fun only3dPacksThatAreCompleteCoverForThe3dView() {
        val now = created + 1000
        assertTrue(Pack.covers3d(listOf(pack), 6.0, 45.0, now))
        assertFalse(Pack.covers3d(listOf(pack), 7.0, 45.0, now))
        assertFalse(Pack.covers3d(listOf(pack.copy(complete = false)), 6.0, 45.0, now))
        assertFalse(Pack.covers3d(listOf(pack.copy(kinds = setOf(Pack.KIND_ORTHO))), 6.0, 45.0, now))
        assertFalse(Pack.covers3d(listOf(pack), 6.0, 45.0, created + Pack.LIFETIME_MS))
    }

    @Test
    fun planOfAPackMatchesItsKinds() {
        assertEquals(8..16, pack.plan().orthoZooms)
        assertEquals(TilePlans.DEM_ZOOMS, pack.plan().demZooms)
        assertNull(pack.copy(kinds = setOf(Pack.KIND_ORTHO)).plan().demZooms)
    }

    @Test
    fun aPackFromBeforeTheAerialPhotoCoversNothing() {
        // a manifest written by the Plan IGN builds: its relief is fine, but its base layer is never read again
        val old = Pack.parse(pack.toJson().replace("\"ortho\"", "\"plan\""))
        assertTrue(old.obsolete)
        assertFalse(old.has3d)
        assertFalse(Pack.covers3d(listOf(old), 6.0, 45.0, created + 1000))
        assertEquals(listOf(old), Pack.toPurge(listOf(old), created + Pack.LIFETIME_MS))
        assertFalse(pack.obsolete)
    }
}

class RouteProgressTest {
    /** 1 km east then back along the very same line, climbing 100 m out and descending back. */
    private fun outAndBack(): Route {
        val pts = ArrayList<Triple<Double, Double, Double?>>()
        for (i in 0..100) pts.add(Triple(i * 10.0, 0.0, 1000.0 + i))
        for (i in 99 downTo 0) pts.add(Triple(i * 10.0, 0.0, 1000.0 + i))
        return routeOf(pts)
    }

    @Test
    fun outAndBackFollowsTheWayBackAfterTheTurn() {
        val route = outAndBack()
        assertEquals(2000.0, route.length, 5.0)
        assertEquals(99.0, route.cumUp!!.last(), 3.0)
        assertEquals(99.0, route.cumDown!!.last(), 3.0)
        val rp = RouteProgress(route)
        var walked = 0.0
        // out: east, 3 m north of the line
        for (i in 0..100) {
            val (la, lo) = at(i * 10.0, 3.0)
            val s = rp.update(la, lo)
            assertTrue(s.matched && !s.offRoute)
            assertEquals("out $i", walked, s.along, 15.0)
            walked = i * 10.0 + 10
        }
        val turn = rp.status
        assertEquals(1000.0, turn.remaining, 15.0)
        assertEquals(0.0, turn.remainingUp!!, 3.0)
        assertEquals(99.0, turn.remainingDown!!, 3.0)
        // back: the same places again, the progress keeps going forward
        for (i in 99 downTo 0) {
            val (la, lo) = at(i * 10.0, -3.0)
            val s = rp.update(la, lo)
            assertEquals("back $i", 2000.0 - i * 10.0, s.along, 15.0)
        }
        assertEquals(0.0, rp.status.remaining, 15.0)
        assertEquals(0.0, rp.status.remainingDown!!, 3.0)
    }

    @Test
    fun loopStartsAtItsStartAndEndsAtItsEnd() {
        // a 500 m square walked anticlockwise, start = end
        val corners = listOf(0.0 to 0.0, 500.0 to 0.0, 500.0 to 500.0, 0.0 to 500.0, 0.0 to 0.0)
        val pts = ArrayList<Triple<Double, Double, Double?>>()
        for (c in 0 until 4) {
            val (e0, n0) = corners[c]
            val (e1, n1) = corners[c + 1]
            for (k in 0 until 50) pts.add(Triple(e0 + (e1 - e0) * k / 50, n0 + (n1 - n0) * k / 50, null))
        }
        pts.add(Triple(0.0, 0.0, null))
        val route = routeOf(pts)
        assertNull(route.cumUp)
        val rp = RouteProgress(route)
        val (la, lo) = at(2.0, 2.0)
        assertEquals(0.0, rp.update(la, lo).along, 10.0)
        assertNull(rp.status.remainingUp)
        // walk the loop with a fix every 25 m
        val walk = ArrayList<Pair<Double, Double>>()
        for (c in 0 until 4) {
            val (e0, n0) = corners[c]
            val (e1, n1) = corners[c + 1]
            for (k in 1..20) walk.add((e0 + (e1 - e0) * k / 20) to (n0 + (n1 - n0) * k / 20))
        }
        for ((e, n) in walk.dropLast(1)) {
            val (a, b) = at(e, n)
            rp.update(a, b)
        }
        assertEquals(1975.0, rp.status.along, 15.0)
        val (a, b) = at(0.0, 1.0)
        assertEquals(2000.0, rp.update(a, b).along, 15.0)
        assertEquals(0.0, rp.status.remaining, 15.0)
    }

    @Test
    fun offRouteBeyondFiftyMetresThenRejoinsAhead() {
        val pts = (0..200).map { Triple(it * 10.0, 0.0, null as Double?) }
        val rp = RouteProgress(routeOf(pts))
        val (la, lo) = at(0.0, 0.0)
        rp.update(la, lo)
        val (a, b) = at(300.0, 80.0)
        assertFalse(rp.update(a, b).offRoute) // one fix out may be GPS noise
        val off = rp.update(a, b)
        assertTrue(off.offRoute)
        assertEquals(80.0, off.distanceToRoute, 2.0)
        assertEquals(0.0, off.along, 1.0) // remaining stays from the last matched place
        // back on the route 1.5 km further (a shortcut)
        val (c, d) = at(1500.0, 10.0)
        val back = rp.update(c, d)
        assertFalse(back.offRoute)
        assertEquals(1500.0, back.along, 5.0)
        assertEquals(500.0, back.remaining, 5.0)
    }

    @Test
    fun rejoinBeyondTheForwardWindow() {
        val rp = RouteProgress(routeOf((0..500).map { Triple(it * 10.0, 0.0, null as Double?) }))
        val (la, lo) = at(0.0, 0.0)
        rp.update(la, lo)
        val (c, d) = at(4000.0, 10.0)
        val s = rp.update(c, d)
        assertFalse(s.offRoute)
        assertEquals(4000.0, s.along, 5.0)
    }

    @Test
    fun firstPositionFarAwayIsOffRouteUntilReached() {
        val rp = RouteProgress(routeOf((0..10).map { Triple(it * 10.0, 0.0, null as Double?) }))
        val (la, lo) = at(0.0, 500.0)
        rp.update(la, lo)
        val s = rp.update(la, lo)
        assertFalse(s.matched)
        assertTrue(s.offRoute)
        assertEquals(500.0, s.distanceToRoute, 3.0)
    }

    @Test
    fun etaFromMovingAverageSpeed() {
        assertEquals(1_000_000L + 3_600_000L, RouteProgress.eta(1_000_000L, 3600.0, 1.0))
        assertNull(RouteProgress.eta(1_000_000L, 3600.0, 0.0))
    }

    @Test
    fun routeWithoutEnoughAltitudesHasNone() {
        val r = routeOf(listOf(Triple(0.0, 0.0, 1000.0), Triple(10.0, 0.0, null), Triple(20.0, 0.0, null)))
        assertNull(r.eles)
        val r2 = routeOf(listOf(Triple(0.0, 0.0, 1000.0), Triple(10.0, 0.0, null), Triple(20.0, 0.0, 1010.0)))
        assertEquals(1000.0, r2.eles!![1], 0.0)
        assertEquals(10.0, r2.cumUp!!.last(), 0.0)
    }
}

class RouteFileTest {
    @Test
    fun plannedGpxWithoutTimesAndWithRoutePoints() {
        val gpx = """<?xml version="1.0"?><gpx version="1.1"><metadata><name>Boucle test</name></metadata>
            <rte><rtept lat="45.0" lon="6.0"><ele>1000</ele></rtept><rtept lat="45.001" lon="6.0"><ele>1010</ele></rtept>
            <rtept lat="45.002" lon="6.001"><ele>1005.5</ele></rtept></rte></gpx>"""
        val r = RouteFile.read(gpx.toByteArray(), "fichier")
        assertEquals("Boucle test", r.name)
        assertEquals(3, r.size)
        assertEquals(1005.5, r.eles!![2], 0.0)
        val tracked = RouteFile.readGpx("""<gpx><trk><name>T</name><trkseg><trkpt lat="45" lon="6"/><trkpt lat="45.01" lon="6.01"/></trkseg></trk></gpx>""", "x")
        assertEquals(2, tracked.size)
        assertNull(tracked.eles)
    }

    @Test
    fun fitActivityAsRoute() {
        val t0 = 1_790_000_000_000L
        val pts = (0 until 5).map { TrackPoint(t0 + it * 1000L, 45.0 + it * 1e-4, 6.0, 1000.0 + it) }
        val r = RouteFile.read(Fit.write(Track(pts), Sport.WALK, 40.0), "Sortie")
        assertEquals(5, r.size)
        assertEquals(1004.0, r.eles!![4], 0.5)
    }

    @Test
    fun packRouteJsonRoundTrip() {
        val r = Route.of("Aller « retour »", listOf(Triple(45.0, 6.0, 1000.0), Triple(45.001, 6.001, 1001.5)))
        val back = RouteFile.fromJson(RouteFile.toJson(r))
        assertEquals(r.name, back.name)
        assertEquals(r.lats[1], back.lats[1], 1e-6)
        assertEquals(1001.5, back.eles!![1], 1e-6)
    }
}

class PeaksTest {
    private val square = MapSquare.of(LON0, LAT0, LON0 + 7000 / K_LON, LAT0 + 7000 / MapSquare.M_PER_DEG_LAT)

    private fun peak(name: String, e: Double, n: Double, ele: Double, imp: Int): Peak {
        val (la, lo) = at(e, n)
        return Peak(name, ele, lo, la, imp)
    }

    @Test
    fun importanceFirstThenAltitudeWithMinimumSpacing() {
        val spacing = square.widthM / 7 // 1250 m
        val list = listOf(
            peak("A", 0.0, 0.0, 2000.0, 3),
            peak("B", 100.0, 0.0, 2500.0, 4), // too close to A, and less important
            peak("C", 3000.0, 0.0, 1800.0, 3),
            peak("D", 6000.0, 0.0, 2600.0, 5),
            peak("E", 6000.0, 6000.0, Double.NaN, 1), // no altitude: left out
        )
        val sel = Peaks.select(list, square)
        assertEquals(listOf("A", "C", "D"), sel.map { it.name })
        for (i in sel.indices) for (j in i + 1 until sel.size) {
            assertTrue(Geo.distance(sel[i].lat, sel[i].lon, sel[j].lat, sel[j].lon) >= spacing)
        }
    }

    @Test
    fun atMostTwelve() {
        val many = (0 until 40).map { peak("P$it", (it % 8) * 2000.0, (it / 8) * 2000.0, 1000.0 + it, 4) }
        assertEquals(12, Peaks.select(many, MapSquare.of(LON0, LAT0, LON0 + 16000 / K_LON, LAT0 + 10000 / MapSquare.M_PER_DEG_LAT)).size)
    }

    @Test
    fun parsesIgnAnswers() {
        val wfs = """{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[6.01,45.02]},"properties":{"nature":"Sommet","toponyme":"Pointe d'Essai","importance":"4"}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[6.02,45.03]},"properties":{"nature":"Sommet","toponyme":null,"importance":"2"}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[6.03,45.04,1200.0]},"properties":{"nature":"Sommet","toponyme":"Mont Échantillon","importance":null}}]}"""
        val c = Peaks.parseWfs(wfs)
        assertEquals(listOf("Pointe d'Essai", "Mont Échantillon"), c.map { it.name })
        assertEquals(9, c[1].importance)
        assertEquals(listOf("Pointe d'Essai", "Mont Échantillon"), Peaks.preselect(c).map { it.name })
        val eles = Peaks.parseAlti("""{"elevations": [1234.5, -99999]}""")
        assertEquals(1234.5, eles[0], 0.0)
        assertTrue(eles[1].isNaN())
        assertTrue(Peaks.altiUrl(c).contains("lon=6.01|6.03&lat=45.02|45.04"))
        assertTrue(Peaks.wfsUrl(Bbox(6.0, 45.0, 6.1, 45.1)).contains("BBOX%28geometrie%2C6.0%2C45.0%2C6.1%2C45.1%2C%27CRS%3A84%27%29"))
    }

    @Test
    fun offlineAnswerFromACoveringCache() {
        val big = Bbox(5.9, 44.9, 6.2, 45.2)
        val peaks = listOf(Peak("In", 1500.0, 6.0, 45.0, 3), Peak("Out", 1600.0, 6.15, 45.15, 3))
        val cached = Peaks.parseCache(Peaks.cacheJson(big, peaks))!!
        assertEquals(big, cached.first)
        val req = Bbox(5.95, 44.95, 6.05, 45.05)
        assertEquals(listOf("In"), Peaks.fromCovering(sequenceOf(cached), req)!!.map { it.name })
        assertNull(Peaks.fromCovering(sequenceOf(cached), Bbox(5.0, 44.0, 6.0, 45.0)))
    }
}

class TileSourcesTest {
    @Test
    fun ignFallbackOnlyWhenIgnHasNoTile() {
        assertTrue(TileSources.ignHasNoTile(404, "text/xml", map("ign-ortho-nodata.xml")))
        assertTrue(TileSources.ignHasNoTile(400, "text/xml", ByteArray(200)))
        assertTrue(TileSources.ignHasNoTile(200, "image/jpeg", ByteArray(0)))
        assertTrue(TileSources.ignHasNoTile(200, "text/xml", ByteArray(300)))
        assertFalse(TileSources.ignHasNoTile(200, "image/jpeg", map("ign-ortho-alps.jpg")))
        assertFalse(TileSources.ignHasNoTile(500, "text/html", ByteArray(100)))
        assertFalse(TileSources.ignHasNoTile(503, null, ByteArray(0)))
        // the white tile off the coast is kept (the page shows its parent through it), the flat sea of low zooms too
        assertFalse(TileSources.ignHasNoTile(200, "image/jpeg", map("ign-ortho-white.jpg")))
        assertFalse(TileSources.ignHasNoTile(200, "image/jpeg", map("ign-ortho-sea.jpg")))
    }

    private fun map(name: String): ByteArray =
        listOf(File("../testdata/map"), File("testdata/map")).first { it.isDirectory }.resolve(name).readBytes()

    @Test
    fun tilePathsFromThePage() {
        assertEquals(TileKey("ortho", 12, 2116, 1466), TileKey.parse("/t/ortho/12/2116/1466"))
        assertEquals(TileKey("ortho", 19, 1, 1), TileKey.parse("/t/ortho/19/1/1.jpg"))
        assertEquals(TileKey("dem", 3, 7, 0), TileKey.parse("/t/dem/3/7/0.png"))
        assertNull(TileKey.parse("/t/dem/3/8/0"))
        assertNull(TileKey.parse("/t/sat/3/1/1"))
        assertNull(TileKey.parse("/t/plan/3/1/1")) // the former base layer
        assertNull(TileKey.parse("/t/osm/3/1/1")) // a cache folder, not a URL
        assertNull(TileKey.parse("/t/ortho/../../etc"))
        val t = TileKey("ortho", 12, 2116, 1466)
        assertEquals("ortho/12/2116/1466.jpg", t.path)
        assertEquals("image/jpeg", t.mime)
        assertEquals("osmfb2/12/2116/1466.png", t.fallback().path)
        assertEquals("image/png", t.fallback().mime)
        assertEquals("image/png", TileKey("dem", 3, 7, 0).mime)
    }
}

class JsonTest {
    @Test
    fun parseAndQuote() {
        val v = Json.parse("""{"a":[1,2.5,-3e2],"b":"x\"y\\zé","c":null,"d":true}""").asMap()!!
        assertEquals(listOf(1.0, 2.5, -300.0), v["a"])
        assertEquals("x\"y\\zé", v["b"])
        assertNull(v["c"])
        assertEquals(true, v["d"])
        assertEquals(v["b"], Json.parse(Json.quote(v["b"] as String)))
        assertEquals("6.123457", Json.num(6.1234567))
        assertEquals("45", Json.num(45.0))
        assertEquals("0", Json.num(-0.0000001))
    }

    @Test
    fun fastNumbersMatchTheExactRounding() {
        val r = java.util.Random(7)
        val values = ArrayList<Double>()
        repeat(200_000) {
            values.add((r.nextDouble() - 0.5) * 360)
            values.add(r.nextDouble() * 10_000)
            values.add((r.nextInt(2_000_000) - 1_000_000) / 1e6) // decimal values, the ones near ties after scaling
            values.add(r.nextInt(100_000) / 64.0) // exact binary ties
        }
        values.addAll(listOf(0.0, -0.0, 0.5, -0.5, 1.5, 2.5, 0.0078125, -0.0078125, 0.0000005, 1e12, -1e12, 123456789.123, 5e-7, -5e-7))
        for (v in values) for (d in intArrayOf(0, 1, 2, 6, 7, 8)) {
            assertEquals("$v at $d", Json.exactNum(v, d), Json.num(v, d))
        }
    }

    @Test(expected = Json.JsonException::class)
    fun rejectsTruncated() {
        Json.parse("""{"a":[1,2""")
    }
}

class MapPayloadTest {
    @Test
    fun trackIsThinnedAndKeepsEnds() {
        val n = 12_000
        val lons = DoubleArray(n) { LON0 + it * 1e-5 }
        val lats = DoubleArray(n) { LAT0 }
        val json = MapPayload.track(lons, lats, DoubleArray(n) { 1.0 }, 1.0)
        val o = Json.parse(json).asMap()!!
        val coords = o["coords"].asList()!!
        assertEquals(MapPayload.MAX_POINTS, coords.size)
        assertEquals(lons.last(), coords.last().asList()!![0] as Double, 1e-6)
        assertEquals(MapPayload.MAX_POINTS, o["speeds"].asList()!!.size)
        assertEquals("#ff3d00", MapPayload.color(0xFFFF3D00.toInt()))
    }
}

class TileCacheTest {
    @Test
    fun leastRecentlyUsedGoFirst() {
        val dir = Files.createTempDirectory("tilecache").toFile()
        try {
            val cache = TileCache(dir, 10_000)
            val t = System.currentTimeMillis()
            for (i in 0 until 4) {
                TileCache.writeAtomic(File(dir, "ortho/1/0/$i.jpg"), ByteArray(3000))
                File(dir, "ortho/1/0/$i.jpg").setLastModified(t - (10 - i) * 3_600_000L)
            }
            // tile 0 is the oldest, but reading it makes it the most recent
            assertNotNull(cache.get("ortho/1/0/0.jpg"))
            cache.trim()
            assertNotNull(cache.get("ortho/1/0/0.jpg"))
            assertNull(cache.get("ortho/1/0/1.jpg"))
            assertTrue(dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= 8000)
            assertFalse(dir.walkTopDown().any { it.name.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aRecentReadDoesNotWrite() {
        val dir = Files.createTempDirectory("tilecache").toFile()
        try {
            val cache = TileCache(dir, 10_000)
            val now = System.currentTimeMillis() / 1000 * 1000
            val recent = File(dir, "ortho/1/0/0.jpg")
            val old = File(dir, "ortho/1/0/1.jpg")
            TileCache.writeAtomic(recent, ByteArray(10))
            TileCache.writeAtomic(old, ByteArray(10))
            recent.setLastModified(now - 10 * 60_000L)
            old.setLastModified(now - 2 * 3_600_000L)
            assertNotNull(cache.get("ortho/1/0/0.jpg"))
            assertNotNull(cache.get("ortho/1/0/1.jpg"))
            assertEquals(now - 10 * 60_000L, recent.lastModified())
            assertTrue(old.lastModified() >= now - 60_000L)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun putTrimsOnTheTrimmer() {
        val dir = Files.createTempDirectory("tilecache").toFile()
        try {
            val jobs = ArrayList<Runnable>()
            val cache = TileCache(dir, 10_000, Executor { jobs.add(it) })
            // the size is unknown until a walk: the first put queues one, the next ones find it queued
            for (i in 0 until 4) cache.put("ortho/1/0/$i.jpg", ByteArray(3000))
            assertEquals(1, jobs.size)
            val t = System.currentTimeMillis()
            for (i in 0 until 4) File(dir, "ortho/1/0/$i.jpg").setLastModified(t - (10 - i) * 3_600_000L)
            jobs.removeAt(0).run()
            assertNull(cache.get("ortho/1/0/0.jpg"))
            assertNull(cache.get("ortho/1/0/1.jpg"))
            assertNotNull(cache.get("ortho/1/0/2.jpg"))
            assertNotNull(cache.get("ortho/1/0/3.jpg"))
            // counted from then on, without walking: 9000 bytes stay under the limit, 12 000 queue a trim
            cache.put("ortho/1/0/4.jpg", ByteArray(3000))
            assertEquals(0, jobs.size)
            cache.put("ortho/1/0/5.jpg", ByteArray(3000))
            assertEquals(1, jobs.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun emptyFileIsAMiss() {
        val dir = Files.createTempDirectory("tilecache").toFile()
        try {
            File(dir, "dem/1/0").mkdirs()
            File(dir, "dem/1/0/0.png").writeBytes(ByteArray(0))
            assertNull(TileCache(dir, 1000).get("dem/1/0/0.png"))
        } finally {
            dir.deleteRecursively()
        }
    }
}

class BackoffTest {
    @Test
    fun failureBlocksForTwentySecondsAndAnAnswerClearsIt() {
        val b = Backoff()
        assertFalse(b.blocked(1_000))
        b.failed(1_000)
        assertTrue(b.blocked(20_999))
        assertFalse(b.blocked(21_000))
        b.failed(30_000)
        b.succeeded()
        assertFalse(b.blocked(30_001))
    }
}
