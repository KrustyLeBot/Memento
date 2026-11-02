package app.scriptorium.map

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
    private val sources = File(root, "app/src/main/java/app/scriptorium").walk().filter { it.extension == "kt" }.map { it.readText() }.toList()

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
        assertTrue(page.contains("BASE + 't/plan/{z}/{x}/{y}'"))
        assertTrue(page.contains("`\${BASE}t/dem/\${z}/\${x}/\${y}`"))
        // what MapLibre and the DEM protocol make of them, as TileServer sees the path
        assertEquals(TileKey("plan", 14, 8501, 5850), TileKey.parse("/t/plan/14/8501/5850"))
        assertEquals(TileKey("dem", 12, 2120, 1460), TileKey.parse("/t/dem/12/2120/1460"))
    }

    @Test
    fun peaksQueryIsTheOneTileServerReads() {
        val q = Regex("""const q = `([^`]+)`""").find(page)!!.groupValues[1]
        assertEquals("w=\${sq[0].toFixed(5)}&s=\${sq[1].toFixed(5)}&e=\${sq[2].toFixed(5)}&n=\${sq[3].toFixed(5)}", q)
        assertNotNull(Regex("""fetch\(`\$\{BASE}peaks\?\$\{q}`\)""").find(page))
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
