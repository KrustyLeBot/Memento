package app.memento.map

import app.memento.activity.MapMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

// Synthetic places only: the repository is public.
private const val LAT0 = 45.0
private const val LON0 = 6.0
private val K_LON = MapSquare.M_PER_DEG_LON * cos(Math.toRadians(LAT0))

private fun route(pts: List<Pair<Double, Double>>): Route =
    Route("test", DoubleArray(pts.size) { LAT0 + pts[it].second / MapSquare.M_PER_DEG_LAT }, DoubleArray(pts.size) { LON0 + pts[it].first / K_LON }, null)

/** A 184 km loop of 10 000 points: a 29 km circle with a wobble, like a road; its square is about 73 km wide. */
private fun loop184(): Route {
    val n = 10_000
    val pts = (0 until n).map { i ->
        val t = 2 * PI * i / (n - 1)
        (29_000 * cos(t) + 600 * sin(9 * t)) to (29_000 * sin(t) + 400 * cos(13 * t))
    }
    val r = route(pts)
    return r
}

/** 60 km east and back along the same road, 30 m to the side. */
private fun outAndBack(): Route {
    val out = (0..3000).map { it * 20.0 to 300 * sin(it / 200.0) }
    val back = out.reversed().map { (e, n) -> e to n + 30 }
    return route(out + back)
}

private fun tileOf(lon: Double, lat: Double, z: Int) =
    floor(MapMath.worldX(lon, z.toDouble())).toInt() to floor(MapMath.worldY(lat, z.toDouble())).toInt()

private fun TilePlan.has(z: Int, x: Int, y: Int): Boolean {
    if (z in orthoZooms) return orthoRanges.first { it.z == z }.let { x in it.x0..it.x1 && y in it.y0..it.y1 }
    return corridor?.contains(z, x, y) == true
}

/** The plan the builds before the corridor made: the whole square up to the highest zoom that fits. */
private fun squareOnlyUpTo17(square: Bbox): TilePlan {
    var best: TilePlan? = null
    for (z in 8..17) {
        val p = TilePlan(square, 8..z, TilePlans.DEM_ZOOMS)
        if (p.estimatedBytes > TilePlans.ROUTE_BUDGET) break
        best = p
    }
    return best!!
}

class CorridorTest {
    @Test
    fun loopOf184KmGetsZ17AlongTheTrackAndZ14Elsewhere() {
        val r = loop184()
        assertEquals(184_000.0, r.length, 8_000.0)
        val t0 = System.nanoTime()
        val p = TilePlans.forRoute(r)!!
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("planning took $ms ms", ms < 5000)
        assertEquals(8..14, p.orthoZooms)
        val c = p.corridor!!
        assertEquals(15..17, c.zooms)
        assertEquals(1000.0, c.bufferM, 0.0)
        assertTrue(c.lons.size < r.size / 3) // simplified
        assertTrue(p.estimatedBytes <= TilePlans.ROUTE_BUDGET)
        assertTrue(p.estimatedBytes > TilePlans.ROUTE_BUDGET / 8)
        assertEquals(17, p.topZoom)
        assertEquals("Haute définition le long du tracé (±1 km), moyenne ailleurs", p.detailLabel())

        // z17 band: about length × (2 × buffer + a tile) of ground, within 25 %
        val tileM = Corridors.tileMetres(17, LAT0)
        val expected = r.length * (2 * 1000 + tileM) / (tileM * tileM)
        val got = c.tiles(17).size.toDouble()
        assertTrue("z17 $got vs $expected", got in expected * 0.75..expected * 1.25)

        // every point of the route is in high definition at every zoom
        for (i in 0 until r.size step 7) for (z in 8..17) {
            val (x, y) = tileOf(r.lons[i], r.lats[i], z)
            assertTrue("point $i z$z", p.has(z, x, y))
        }
        // the middle of the loop, 20 km from the track, is not
        val (cx, cy) = tileOf(LON0, LAT0, 17)
        assertFalse(c.contains(17, cx, cy))
        assertTrue(p.has(14, tileOf(LON0, LAT0, 14).first, tileOf(LON0, LAT0, 14).second))

        val before = squareOnlyUpTo17(p.square)
        println(
            "184 km loop: before z8-${before.orthoZooms.last} over the square, ${before.tileCount} tiles, ~${before.estimatedBytes / 1_000_000} MB; " +
                "after z8-14 + z15-17 ±1 km, ${p.tileCount} tiles, ~${p.estimatedBytes / 1_000_000} MB",
        )
        assertTrue(before.orthoZooms.last < 17)
    }

    @Test
    fun outAndBackCountsTheSharedRoadOnce() {
        val r = outAndBack()
        val c = Corridors.of(r, 1500.0, 15..17)
        val oneWay = Corridors.of(route((0..3000).map { it * 20.0 to 300 * sin(it / 200.0) }), 1500.0, 15..17)
        for (z in 15..17) {
            val both = c.tiles(z).size
            val one = oneWay.tiles(z).size
            assertTrue("z$z $both vs $one", both >= one && both <= one * 1.05)
        }
        val p = TilePlans.forRoute(r)!!
        assertNotNull(p.corridor)
        for (i in 0 until r.size step 11) for (z in 8..17) {
            val (x, y) = tileOf(r.lons[i], r.lats[i], z)
            assertTrue("point $i z$z", p.has(z, x, y))
        }
    }

    @Test
    fun aTightBudgetDropsZ17ThenNarrowsTheBandThenLowersTheSquare() {
        val r = loop184()
        val full = TilePlans.forRoute(r)!!
        val noZ17 = TilePlans.forRoute(r, full.estimatedBytes - 1)!!
        assertEquals(15..16, noZ17.corridor!!.zooms)
        assertEquals(1000.0, noZ17.corridor!!.bufferM, 0.0)
        val narrower = TilePlans.forRoute(r, noZ17.estimatedBytes - 1)!!
        assertEquals(500.0, narrower.corridor!!.bufferM, 0.0)
        assertEquals(8..14, narrower.orthoZooms)
        // a budget below the z14 square: the square goes down, a band stays
        val small = TilePlans.forRoute(r, TilePlan(full.square, 8..14, TilePlans.DEM_ZOOMS).estimatedBytes - 1)!!
        assertTrue(small.orthoZooms.last < 14)
        assertNotNull(small.corridor)
        for (b in listOf(full, noZ17, narrower, small)) assertTrue(b.estimatedBytes <= full.estimatedBytes)
    }

    @Test
    fun aShortHikeGetsItsWholeSquareAtZ17() {
        val r = route((0..500).map { it * 20.0 to it * 6.0 })
        val p = TilePlans.forRoute(r)!!
        assertEquals(8..17, p.orthoZooms)
        assertNull(p.corridor)
        assertEquals("Haute définition sur toute la zone", p.detailLabel())
    }

    @Test
    fun continentSizedRouteIsRefused() {
        assertNull(TilePlans.forRoute(route(listOf(0.0 to 0.0, 2_000_000.0 to 1_500_000.0))))
    }

    @Test
    fun aTileIsKeptWhenItsRectangleComesWithinTheBuffer() {
        // segment along y = 0 from x 0 to 10; tile [3,4]×[2,3] is 2 away
        assertEquals(2.0, Corridors.rectSegment(3.0, 2.0, 4.0, 3.0, 0.0, 0.0, 10.0, 0.0), 1e-12)
        assertEquals(0.0, Corridors.rectSegment(3.0, -1.0, 4.0, 1.0, 0.0, 0.0, 10.0, 0.0), 0.0)
        // crossing diagonally without an endpoint inside
        assertEquals(0.0, Corridors.rectSegment(4.0, 4.0, 5.0, 5.0, 0.0, 0.0, 10.0, 10.0), 0.0)
        // past the end: corner to endpoint
        assertEquals(5.0, Corridors.rectSegment(13.0, 4.0, 14.0, 5.0, 0.0, 0.0, 10.0, 0.0), 1e-12)
    }

    @Test
    fun simplifyKeepsEndsAndShape() {
        val lons = DoubleArray(1000) { LON0 + it * 1e-4 }
        val lats = DoubleArray(1000) { LAT0 }
        assertEquals(listOf(0, 999), Corridors.simplify(lons, lats, 15.0).toList())
        lats[500] = LAT0 + 0.01
        assertTrue(500 in Corridors.simplify(lons, lats, 15.0).toList())
    }
}

class OverzoomTest {
    @Test
    fun cropOfTheParentQuarter() {
        // z15 (x odd, y even) is the top-right quarter of its z14 parent
        val o = Overzoom.of(TileKey("ortho", 15, 101, 200), 1)
        assertEquals(TileKey("ortho", 14, 50, 100), o.ancestor)
        assertEquals(128, o.left)
        assertEquals(0, o.top)
        assertEquals(128, o.size)
    }

    @Test
    fun cropThreeZoomsUp() {
        // 8x8 children per ancestor: x = 8·10 + 5, y = 8·20 + 7 → 32 px cells, column 5, row 7
        val o = Overzoom.of(TileKey("ortho", 17, 85, 167), 3)
        assertEquals(TileKey("ortho", 14, 10, 20), o.ancestor)
        assertEquals(32, o.size)
        assertEquals(160, o.left)
        assertEquals(224, o.top)
        assertTrue(o.left + o.size <= 256 && o.top + o.size <= 256)
    }

    @Test
    fun candidatesNearestFirstAndBounded() {
        val c = Overzoom.candidates(TileKey("ortho", 19, 1000, 2000))
        assertEquals((18 downTo 13).toList(), c.map { it.ancestor.z })
        assertEquals(Overzoom.MAX_DZ, c.size)
        assertEquals(1, Overzoom.candidates(TileKey("ortho", 1, 1, 0)).size)
    }
}

class CorridorManifestTest {
    private val created = 1_790_000_000_000L

    @Test
    fun manifestRoundTripKeepsTheCorridorAndItsTiles() {
        val plan = TilePlans.forRoute(outAndBack())!!
        val pack = Pack(
            "r-1", "Aller-retour", created, plan.square, plan.orthoZooms.last,
            setOf(Pack.KIND_ORTHO, Pack.KIND_DEM, Pack.KIND_PEAKS, Pack.KIND_BUILDINGS), 0, complete = false, hasRoute = true, corridor = plan.corridor,
        )
        val back = Pack.parse(pack.toJson())
        assertEquals(pack.corridor, back.corridor)
        assertEquals(back, Pack.parse(back.toJson()))
        assertEquals(plan.tileCount, back.plan().tileCount)
        for (z in plan.corridor!!.zooms) assertTrue(plan.corridor!!.tiles(z).contentEquals(back.plan().corridor!!.tiles(z)))
        // 3D anywhere in the square, not only along the band
        val done = back.copy(complete = true, kinds = setOf(Pack.KIND_ORTHO, Pack.KIND_DEM))
        assertTrue(Pack.covers3d(listOf(done), plan.square.w + 1e-4, plan.square.n - 1e-4, created + 1000))
    }

    @Test
    fun aManifestWithoutCorridorStillReads() {
        val old = """{"id":"r-9","name":"Ancien","created":$created,"square":[5.9,44.9,6.1,45.1],"zmax":15,""" +
            """"kinds":["dem","ortho","peaks"],"bytes":1234,"complete":true,"route":true}"""
        val p = Pack.parse(old)
        assertNull(p.corridor)
        assertEquals(8..15, p.plan().orthoZooms)
        assertEquals(15, p.plan().topZoom)
        assertTrue(Pack.covers3d(listOf(p), 6.0, 45.0, created + 1000))
    }
}
