package app.memento.map

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

/** A metatile as the contract (v1.1) lays it out (synthetic data only). */
internal fun buildMeta(z: Int, x0: Int, y0: Int, entries: List<MetaEntry>, format: (MetaEntry) -> Int, gzip: (MetaEntry) -> Boolean): ByteArray {
    val buf = ByteArrayOutputStream()
    DataOutputStream(buf).run {
        write("TUIM".toByteArray())
        writeByte(1); writeByte(z); writeByte(4); writeByte(0)
        writeInt(x0); writeInt(y0); writeShort(entries.size)
        for (e in entries) {
            val data = if (gzip(e)) ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(e.data) } }.toByteArray() else e.data
            writeByte(Metatile.LAYERS.indexOf(e.layer)); writeByte(e.z); writeInt(e.x); writeInt(e.y)
            writeByte(e.status); writeByte(format(e)); writeByte(if (gzip(e)) 1 else 0); writeInt(data.size)
            write(data)
        }
    }
    return buf.toByteArray()
}

/** The tile server reading Tuileries' metatiles: one request fills the browsing cache for a block's area. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MetatileTest {
    private lateinit var fake: FakeTuileries
    private lateinit var dir: File
    private val x0 = 67764
    private val y0 = 46680
    private val cell = """{"v":2,"z":15,"x":16941,"y":11670,"o":[0,0],"b":[],"c":[]}""".toByteArray()
    private val mvt = byteArrayOf(0x1a, 0x02, 0x74, 0x72)

    @Before
    fun start() {
        fake = FakeTuileries()
        dir = Files.createTempDirectory("maptiles").toFile()
        TileServer.initCache(dir)
        TileServer.forgetMetatiles()
        MapConfig.set(Tuileries(fake.url, USER, PASSWORD), 17)
    }

    @After
    fun stop() {
        MapConfig.set(null, Tuileries.PHOTO_MAX_ZOOM_DEFAULT)
        TileServer.forgetMetatiles()
        fake.stop()
        dir.deleteRecursively()
    }

    private fun photo(x: Int) = WEBP + byteArrayOf((x % 128).toByte())

    private fun meta(): ByteArray {
        val entries = ArrayList<MetaEntry>()
        for (y in y0 until y0 + 4) for (x in x0 until x0 + 4) {
            val status = when (x to y) { (x0 + 1 to y0) -> Metatile.PENDING; (x0 + 2 to y0) -> Metatile.NONE; else -> Metatile.OK }
            entries.add(MetaEntry("ortho", 17, x, y, status, "", if (status == Metatile.OK) photo(x) else ByteArray(0)))
        }
        entries.add(MetaEntry("dem", 13, 4235, 2917, Metatile.OK, "", byteArrayOf(9, 9)))
        entries.add(MetaEntry("buildings", 15, 16941, 11670, Metatile.OK, "", cell))
        entries.add(MetaEntry("trails", 15, 16941, 11670, Metatile.OK, "", mvt))
        val formats = mapOf("ortho" to 1, "dem" to 2, "buildings" to 4, "trails" to 5)
        return buildMeta(17, x0, y0, entries, { if (it.status == Metatile.OK) formats.getValue(it.layer) else 0 }, { it.layer == "buildings" || it.layer == "trails" })
    }

    @Test
    fun parsedAndPlaced() {
        val entries = Metatile.parse(meta())
        assertEquals(19, entries.size)
        assertArrayEquals(cell, entries[17].data)
        assertEquals("orthow/17/$x0/$y0.webp", entries[0].cachePath())
        assertNull(entries[1].cachePath())
        assertEquals("dem/13/4235/2917.png", entries[16].cachePath())
        assertEquals("buildings/15/16941/11670.json", entries[17].cachePath())
        assertEquals("trails/15/16941/11670.pbf", entries[18].cachePath())
        assertEquals(Triple(17, x0, y0), Metatile.block(TileKey("ortho", 17, x0 + 3, y0 + 2)))
        for (bad in listOf(ByteArray(0), meta().copyOf(40), "XXXX".toByteArray() + meta().copyOfRange(4, 60))) {
            try {
                Metatile.parse(bad)
                throw AssertionError("parsed a bad metatile")
            } catch (_: java.io.IOException) {
            }
        }
    }

    @Test
    fun oneRequestServesTheBlockAndFillsTheCache() {
        fake.answers["/meta/17/$x0/$y0"] = Triple(200, "application/x-tuileries-metatile", meta())
        fake.answers["/t/ortho/17/${x0 + 1}/$y0"] = Triple(200, "image/avif", "avif".toByteArray())
        val first = TileServer.serveTile(TileKey("ortho", 17, x0 + 3, y0 + 3))
        assertEquals("image/webp", first.mimeType)
        assertArrayEquals(photo(x0 + 3), first.data.readBytes())
        assertEquals(listOf("/meta/17/$x0/$y0?layers=ortho,dem,buildings,trails"), fake.requests.map { it.first })
        assertArrayEquals(photo(x0), TileServer.serveTile(TileKey("ortho", 17, x0, y0 + 1)).data.readBytes())
        assertEquals(404, TileServer.serveTile(TileKey("ortho", 17, x0 + 2, y0)).statusCode)  // no tile there
        assertArrayEquals(byteArrayOf(9, 9), File(dir, "dem/13/4235/2917.png").readBytes())
        assertArrayEquals(cell, File(dir, "buildings/15/16941/11670.json").readBytes())
        assertArrayEquals(mvt, File(dir, "trails/15/16941/11670.pbf").readBytes())
        assertEquals(1, fake.requests.size)
        // pending in the metatile: asked for alone, and AVIF kept as AVIF
        val pending = TileServer.serveTile(TileKey("ortho", 17, x0 + 1, y0))
        assertEquals("image/avif", pending.mimeType)
        assertEquals("/t/ortho/17/${x0 + 1}/$y0", fake.requests.last().first)
        assertTrue(File(dir, "orthoa/17/${x0 + 1}/$y0.avif").isFile)
    }

    @Test
    fun aTuileriesWithoutMetatilesGetsSingleTiles() {
        fake.answers["/t/ortho/"] = Triple(200, "image/webp", WEBP)
        TileServer.serveTile(TileKey("ortho", 17, 4, 4))
        TileServer.serveTile(TileKey("ortho", 17, 8, 8))
        assertEquals(listOf("meta", "t", "t"), fake.requests.map { it.first.split('/')[1] })
    }
}
