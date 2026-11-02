package app.scriptorium.map

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
        assertFalse(TileSources.ignHasNoTile(429, "text/html", 100))
        assertFalse(TileSources.ignHasNoTile(408, null, 0))
        assertTrue(TileSources.ignHasNoTile(404, "text/xml", 137))
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
                val p = Pack(id, id, created, square, 12, setOf(Pack.KIND_PLAN), 0, complete = true, hasRoute = true)
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
