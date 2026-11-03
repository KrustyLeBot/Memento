package app.memento.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Android host against the shared page (web/map/map.js), read as text: every `SMap` function the Kotlin
 * code calls exists in the page, and every URL the page requests is one TileServer serves.
 */
class MapPageContractTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "web/map/map.js").isFile }
    private val page = File(root, "web/map/map.js").readText()
    private val sources = File(root, "app/src/main/java/app/memento").walk().filter { it.extension == "kt" }.map { it.readText() }.toList()

    @Test
    fun everyCalledFunctionIsInThePageApi() {
        val api = Regex("""const API = \[([^\]]+)]""").find(page)!!.groupValues[1]
            .split(',').map { it.trim().trim('\'') }.toSet()
        val called = sources.flatMap { s -> Regex("""(?:MapHost\.)?\bcall\("(\w+)"""").findAll(s).map { it.groupValues[1] }.toList() }.toSet()
        assertTrue("no call found", called.size >= 10)
        assertEquals(emptySet<String>(), called - api)
    }

    @Test
    fun tileUrlsThePageBuildsAreServed() {
        assertTrue(page.contains("tiles: ['ortho://{z}/{x}/{y}'], tileSize: 256, maxzoom: 19"))
        assertTrue(page.contains("fetch(`\${BASE}t/ortho/\${z}/\${x}/\${y}`, { signal })"))
        assertTrue(page.contains("`\${BASE}t/dem/\${z}/\${x}/\${y}`"))
        // what MapLibre and the DEM protocol make of them, as TileServer sees the path
        assertEquals(TileKey("ortho", 19, 272042, 187212), TileKey.parse("/t/ortho/19/272042/187212"))
        assertEquals(TileKey("dem", 12, 2120, 1460), TileKey.parse("/t/dem/12/2120/1460"))
    }

    @Test
    fun peaksQueryIsTheOneTileServerReads() {
        val q = Regex("""const q = `([^`]+)`""").find(page)!!.groupValues[1]
        assertEquals("w=\${sq[0].toFixed(5)}&s=\${sq[1].toFixed(5)}&e=\${sq[2].toFixed(5)}&n=\${sq[3].toFixed(5)}", q)
        assertNotNull(Regex("""fetch\(`\$\{BASE}peaks\?\$\{q}`\)""").find(page))
    }

    @Test
    fun buildingCellsThePageAsksForAreServed() {
        assertTrue(page.contains("const BLD_Z = 15;"))
        assertNotNull(Regex("""fetch\(`\$\{BASE}buildings/\$\{BLD_Z}/\$\{x}/\$\{y}`\)""").find(page))
        assertEquals(Buildings.Z, 15)
        assertEquals(TileKey("buildings", 15, 16941, 11670), TileKey.parseBuildings("/buildings/15/16941/11670"))
        // the roofs are sampled on the photo the page asks for like MapLibre does
        assertTrue(page.contains("const BLD_SAMPLE_Z = 17;"))
        assertTrue(page.contains("fetch(`\${BASE}t/ortho/\${BLD_SAMPLE_Z}/\${tx}/\${ty}`)"))
        assertEquals(TileKey("ortho", 17, 68012, 46818), TileKey.parse("/t/ortho/17/68012/46818"))
        // the compact cell format the page decodes
        for (field in listOf("doc.o", "doc.b")) assertTrue(field, page.contains(field))
    }

    @Test
    fun theSquareSentIsTheOneThePageWouldCompute() {
        // the page treats squares closer than 1e-6 degree as the same one: the 7-decimal JSON must stay inside it
        val sq = MapSquare.of(6.8123456789, 45.91234567, 6.84987654321, 45.9398765)
        val sent = MapPayload.bbox(sq).trim('[', ']').split(',').map { it.toDouble() }
        sq.toList().zip(sent).forEach { (a, b) -> assertTrue(Math.abs(a - b) < 1e-6) }
        assertTrue(page.contains("Math.abs(v - b[i]) < 1e-6"))
    }
}
