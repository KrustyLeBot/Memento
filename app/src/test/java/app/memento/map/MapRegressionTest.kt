package app.memento.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

// Synthetic places only: the repository is public.
class MapRegressionTest {
    @Test
    fun rateLimitedOrTimedOutIgnIsAnErrorNotAMissingTile() {
        // read as "no tile", a pack download skipped the tile and called itself complete
        assertFalse(TileSources.ignHasNoTile(429, "text/html", ByteArray(100)))
        assertFalse(TileSources.ignHasNoTile(408, null, ByteArray(0)))
        assertTrue(TileSources.ignHasNoTile(404, "text/xml", ByteArray(137)))
    }

    @Test(expected = Json.JsonException::class)
    fun badUnicodeEscapeIsAJsonError() {
        // a NumberFormatException escaped the page-event handler's catch
        Json.parse(""""\uZZZZ"""")
    }

    @Test
    fun expiredPackInUseIsKeptUntilReleased() {
        val dir = Files.createTempDirectory("packs").toFile()
        try {
            OfflinePacks.initDir(dir)
            val created = 1_790_000_000_000L
            val square = Bbox(5.9, 44.9, 6.1, 45.1)
            for (id in listOf("r-used", "r-free")) {
                val p = Pack(id, id, created, square, 12, setOf(Pack.KIND_ORTHO), 0, complete = true, hasRoute = true)
                TileCache.writeAtomic(File(dir, "$id/manifest.json"), p.toJson().toByteArray())
            }
            val later = created + Pack.LIFETIME_MS + 1
            OfflinePacks.pin("r-used")
            OfflinePacks.refresh(later)
            assertTrue(File(dir, "r-used").isDirectory)
            assertFalse(File(dir, "r-free").isDirectory)
            assertEquals(listOf("r-used"), OfflinePacks.packs.value.map { it.id })
            OfflinePacks.unpin("r-used")
            OfflinePacks.refresh(later)
            assertFalse(File(dir, "r-used").isDirectory)
        } finally {
            OfflinePacks.unpin("r-used")
            dir.deleteRecursively()
        }
    }

    @Test
    fun gpxPointsOutOfRangeAreSkippedAndTheNameIsUnescaped() {
        val gpx = """<?xml version="1.0"?><gpx version="1.1"><trk><name>Crêtes &amp; lacs</name><trkseg>
            <trkpt lat="NaN" lon="6.0"/>
            <trkpt lat="45.0" lon="6.0"/>
            <trkpt lat="95.0" lon="6.0"/>
            <trkpt lat="45.01" lon="6.01"/>
            </trkseg></trk></gpx>"""
        val r = RouteFile.read(gpx.toByteArray(), "x")
        assertEquals("Crêtes & lacs", r.name)
        assertEquals(2, r.size)
        val b = r.bbox!!
        assertFalse(b.toList().any { it.isNaN() })
    }
}

class OffRouteDebounceTest {
    private val k = MapSquare.M_PER_DEG_LON * kotlin.math.cos(Math.toRadians(45.0))
    private fun at(east: Double, north: Double) = (45.0 + north / MapSquare.M_PER_DEG_LAT) to (6.0 + east / k)

    @Test
    fun oneNoisyFixIsNotOffRouteTwoAreAndBackIsImmediate() {
        val route = Route.of("t", (0..100).map { val (la, lo) = at(it * 10.0, 0.0); Triple(la, lo, null as Double?) })
        val rp = RouteProgress(route)
        val on = at(100.0, 5.0)
        val out = at(110.0, 70.0)
        rp.update(on.first, on.second)
        // a lone jump past 50 m, then back: never "Hors trace"
        val noisy = rp.update(out.first, out.second)
        assertFalse(noisy.offRoute)
        assertEquals(70.0, noisy.distanceToRoute, 2.0)
        assertFalse(rp.update(on.first, on.second).offRoute)
        // two in a row: off route, and the first fix back clears it
        rp.update(out.first, out.second)
        assertTrue(rp.update(out.first, out.second).offRoute)
        assertFalse(rp.update(on.first, on.second).offRoute)
    }

    @Test
    fun pinnedPackStillCoversIn3dPastItsLifetime() {
        val created = 1_790_000_000_000L
        val p = Pack("r-1", "r", created, Bbox(5.9, 44.9, 6.1, 45.1), 12, setOf(Pack.KIND_ORTHO, Pack.KIND_DEM), 0, complete = true, hasRoute = true)
        val later = created + Pack.LIFETIME_MS + 1
        assertFalse(Pack.covers3d(listOf(p), 6.0, 45.0, later))
        assertTrue(Pack.covers3d(listOf(p), 6.0, 45.0, later, setOf("r-1")))
    }
}
