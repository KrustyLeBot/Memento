package app.memento.map

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Base64
import java.util.Collections

internal const val USER = "randonneur"
internal const val PASSWORD = "mot-de-passe-de-test"
// a WebP header: the bytes are passed through and stored, never decoded here
internal val WEBP = "RIFF\u001a\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1) + ByteArray(14)
private const val INFO = """{"name":"Tuileries","version":"0.1.0","max_zoom":17,"layers":["ortho","dem","buildings","peaks"]}"""

/**
 * A fake Tuileries (synthetic answers only): records each request's headers, checks Basic auth like the real one.
 * Plain sockets, one request per connection: the unit tests compile against android.jar, without the JDK's HTTP server.
 */
internal class FakeTuileries {
    val requests: MutableList<Pair<String, Map<String, String>>> = Collections.synchronizedList(ArrayList())
    val posted: MutableList<Pair<String, String>> = Collections.synchronizedList(ArrayList())
    val answers: MutableMap<String, Triple<Int, String, ByteArray>> =
        Collections.synchronizedMap(HashMap(mapOf("/api/info" to Triple(200, "application/json", INFO.toByteArray()))))
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val url: String get() = "http://127.0.0.1:${server.localPort}/"

    init {
        Thread {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: IOException) { break }
                Thread { s.use { serve(it) } }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun serve(s: Socket) {
        val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val first = reader.readLine()?.split(' ') ?: return
        val path = first.getOrNull(1) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        if (first[0] == "POST") {
            val body = CharArray(headers["content-length"]?.toInt() ?: 0)
            var n = 0
            while (n < body.size) n += reader.read(body, n, body.size - n).takeIf { it > 0 } ?: break
            posted.add(path to String(body))
        }
        requests.add(path to headers)
        val expected = "Basic " + Base64.getEncoder().encodeToString("$USER:$PASSWORD".toByteArray())
        val (code, type, body) = if (headers["authorization"] != expected) {
            Triple(401, "text/plain", "auth".toByteArray())
        } else {
            synchronized(answers) { answers[path] ?: answers.entries.firstOrNull { (it.key.endsWith("/") && path.startsWith(it.key)) || path.startsWith(it.key + "?") }?.value }
                ?: Triple(404, "text/plain", "none".toByteArray())
        }
        val out = s.getOutputStream()
        out.write("HTTP/1.1 $code X\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    fun stop() = server.close()
}

// Synthetic places and credentials only: the repository is public.
class TuileriesTest {
    private lateinit var fake: FakeTuileries

    @Before
    fun start() {
        fake = FakeTuileries()
    }

    @After
    fun stop() {
        MapConfig.set(null, Tuileries.PHOTO_MAX_ZOOM_DEFAULT)
        fake.stop()
    }

    private fun source(url: String = fake.url, password: String = PASSWORD) = Tuileries(url, USER, password, "cid.access", "csecret")

    @Test
    fun urlsHeadersAndSettingsRules() {
        val t = Tuileries("https://tuileries.example.org", USER, PASSWORD, "cid", "csec")
        assertEquals("https://tuileries.example.org/", t.base)
        assertEquals("https://tuileries.example.org/t/ortho/15/16941/11670", t.tileUrl(TileKey("ortho", 15, 16941, 11670)))
        assertEquals("https://tuileries.example.org/t/dem/12/2120/1460", t.tileUrl(TileKey("dem", 12, 2120, 1460)))
        assertEquals("https://tuileries.example.org/buildings/15/16941/11670", t.tileUrl(TileKey("buildings", 15, 16941, 11670)))
        assertEquals("https://tuileries.example.org/peaks?w=6.8&s=45.9&e=6.9&n=46", t.peaksUrl(Bbox(6.8, 45.9, 6.9, 46.0)))
        assertEquals("https://tuileries.example.org/api/info", t.infoUrl)
        val h = t.headers()
        assertEquals("Basic " + Base64.getEncoder().encodeToString("$USER:$PASSWORD".toByteArray()), h["Authorization"])
        assertEquals("cid" to "csec", h["CF-Access-Client-Id"] to h["CF-Access-Client-Secret"])
        assertEquals(TileSources.USER_AGENT, h["User-Agent"])
        assertFalse("Authorization" in Tuileries("https://t.example.org/").headers())
        assertFalse(t.toString().contains(PASSWORD) || t.toString().contains("csec"))
        for (bad in listOf("", "t.example.org", "ftp://t.example.org/", "https://", "https://t.example.org/?a=1")) {
            assertEquals(bad, "", Tuileries.normalize(bad))
        }
        assertEquals(listOf(14, 14, 17, 19), listOf(3, 14, 17, 25).map { Tuileries.clampPhotoMaxZoom(it) })
        MapConfig.set(Tuileries(""), 30)
        assertNull(MapConfig.tuileries)
        assertEquals(19, MapConfig.photoMaxZoom)
    }

    @Test
    fun eachAnswerTypeIsKeptWhereItsTypeSays() {
        val photo = TileKey("ortho", 15, 16941, 11670)
        val webp = Tuileries.tileOf(photo, HttpResult(200, "image/webp", WEBP))!!
        assertEquals("orthow/15/16941/11670.webp", webp.key.path)
        assertEquals("image/webp", webp.key.mime)
        assertEquals("ortho/15/16941/11670.jpg", Tuileries.tileOf(photo, HttpResult(200, "image/jpeg", byteArrayOf(1)))!!.key.path)
        assertEquals(TileKey.KIND_OSM, Tuileries.tileOf(photo, HttpResult(200, "image/png", byteArrayOf(1)))!!.key.kind)
        assertNull(Tuileries.tileOf(photo, HttpResult(404, "text/plain", ByteArray(4))))
        for (bad in listOf(HttpResult(502, "text/plain", ByteArray(3)), HttpResult(200, "text/html", ByteArray(3)), HttpResult(401, "text/plain", ByteArray(0)))) {
            try {
                Tuileries.tileOf(photo, bad)
                fail("${bad.code} ${bad.contentType} is an error, not a missing tile")
            } catch (_: IOException) {
            }
        }
        val dem = TileKey("dem", 12, 2120, 1460)
        assertEquals(dem, Tuileries.tileOf(dem, HttpResult(200, "image/png; charset=binary", byteArrayOf(1)))!!.key)
        assertEquals(listOf("ortho", "orthow", "orthoa", "osmfb2"), photo.variants().map { it.kind })
        assertEquals("orthoa/15/16941/11670.avif", Tuileries.tileOf(photo, HttpResult(200, "image/avif", byteArrayOf(1)))!!.key.path)
        assertEquals(listOf(dem), dem.variants())
        assertEquals("""Erreur HTTP 500""", Tuileries.statusMessage(500))
    }

    @Test
    fun configuredEveryRequestGoesToTuileriesWithItsCredentials() {
        fake.answers["/t/ortho/15/16941/11670"] = Triple(200, "image/webp", WEBP)
        fake.answers["/t/ortho/14/1/1"] = Triple(200, "image/png", byteArrayOf(1, 2))
        fake.answers["/t/ortho/14/2/2"] = Triple(502, "text/plain", "upstream down".toByteArray())
        fake.answers["/buildings/15/3/4"] = Triple(200, "application/json", """{"v":1,"z":15,"x":3,"y":4,"o":[0,0],"b":[]}""".toByteArray())
        fake.answers["/peaks?w=6.8&s=45.9&e=6.9&n=46"] = Triple(200, "application/json",
            """[{"name":"Pic Test","ele":2500,"lon":6.85,"lat":45.95,"importance":2}]""".toByteArray())
        MapConfig.set(source(), 17)
        val got = TileServer.fetch(TileKey("ortho", 15, 16941, 11670), allowOsm = true)!!
        assertEquals(TileKey("orthow", 15, 16941, 11670), got.key)
        assertArrayEquals(WEBP, got.bytes)
        assertNull(TileServer.fetch(TileKey("dem", 12, 1, 1), allowOsm = false))  // 404: no tile there
        assertEquals(TileKey.KIND_OSM, TileServer.fetch(TileKey("ortho", 14, 1, 1), allowOsm = true)!!.key.kind)
        assertNull("packs keep no OpenStreetMap stand-in", TileServer.fetch(TileKey("ortho", 14, 1, 1), allowOsm = false))
        try {
            TileServer.fetch(TileKey("ortho", 14, 2, 2), allowOsm = true)
            fail("an upstream error must fail, not fall back")
        } catch (_: IOException) {
        }
        assertEquals(TileKey("buildings", 15, 3, 4), TileServer.fetch(TileKey("buildings", 15, 3, 4), allowOsm = false)!!.key)
        assertEquals(listOf("Pic Test"), TileServer.computePeaks(Bbox(6.8, 45.9, 6.9, 46.0)).map { it.name })
        assertEquals(7, fake.requests.size)
        for ((path, h) in fake.requests) {
            assertTrue(path, h["authorization"]!!.startsWith("Basic "))
            assertEquals("cid.access", h["cf-access-client-id"])
            assertEquals("csecret", h["cf-access-client-secret"])
        }
    }

    @Test
    fun withoutTuileriesNoCredentialIsSent() {
        // the plain request IGN, OSM and AWS get: the User-Agent only
        val r = TileServer.get(fake.url + "t/ortho/1/0/0")
        assertEquals(401, r.code)
        val h = fake.requests.single().second
        assertEquals(TileSources.USER_AGENT, h["user-agent"])
        assertFalse(h.containsKey("authorization") || h.containsKey("cf-access-client-id") || h.containsKey("cf-access-client-secret"))
    }

    @Test
    fun connectionTest() {
        val info = TileServer.tuileriesInfo(source())
        assertEquals(17, info.maxZoom)
        assertEquals("Connecté : Tuileries 0.1.0 · photos archivées jusqu'au zoom 17", info.summary())
        for ((t, words) in listOf(source(password = "faux") to "mot de passe", source(url = "pas une adresse") to "Adresse invalide")) {
            try {
                TileServer.tuileriesInfo(t)
                fail("no error for $t")
            } catch (e: TuileriesException) {
                assertTrue(e.message, e.message!!.contains(words))
            }
        }
    }

    @Test
    fun trailsAndThePageQuery() {
        val mvt = byteArrayOf(0x1a, 0x02, 0x74, 0x72)
        val gz = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(mvt) } }.toByteArray()
        assertArrayEquals(mvt, Tuileries.trailsOf(HttpResult(200, "application/vnd.mapbox-vector-tile", gz)))
        assertArrayEquals(mvt, Tuileries.trailsOf(HttpResult(200, "application/vnd.mapbox-vector-tile", mvt)))
        assertEquals(0, Tuileries.trailsOf(HttpResult(204, null, ByteArray(0))).size)
        assertEquals(0, Tuileries.trailsOf(HttpResult(404, "text/plain", ByteArray(3))).size)  // not built yet
        try {
            Tuileries.trailsOf(HttpResult(502, "text/plain", ByteArray(3)))
            fail("an upstream error is not an empty tile")
        } catch (_: IOException) {
        }
        assertEquals("https://t.example.org/trails/12/2120/1460", Tuileries("https://t.example.org").trailsUrl(12, 2120, 1460))
        assertEquals("Sentinel-2 cloudless by EOX", Tuileries.parseInfo("""{"version":"1","attribution":"Sentinel-2 cloudless by EOX"}""").attribution)
        assertEquals("Sentinel-2 cloudless 2024 by EOX · © OpenStreetMap contributors", Tuileries.parseInfo(
            """{"version":"1","attributions":{"ortho":["© IGN","Sentinel-2 cloudless 2024 by EOX"],"trails":["© OpenStreetMap contributors"]}}""").attribution)
        MapConfig.set(null, 16)
        assertEquals("&photomax=16", MapConfig.pageQuery())
        MapConfig.set(source(), 16)
        assertEquals("&photomax=16&demmax=13", MapConfig.pageQuery())
        MapConfig.attribution = "Sentinel-2 cloudless & EOX"
        assertEquals("&photomax=16&demmax=13&attrib=Sentinel-2%20cloudless%20%26%20EOX", MapConfig.pageQuery())
        MapConfig.set(null, 16)
        assertEquals("", MapConfig.attribution)
        // the page's side of it
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "web/map/map.js").isFile }
        val page = File(root, "web/map/map.js").readText()
        assertTrue(page.contains("tiles: [`\${BASE}trails/{z}/{x}/{y}`]") && page.contains("'source-layer': 'trails'"))
        assertTrue(page.contains("params.get('trails') === '1'") && page.contains("params.get('attrib')"))
    }

    @Test
    fun reliefVersionAndTopZoomFromApiInfo() {
        val old = Tuileries.parseInfo(INFO)
        assertEquals(1 to 13, old.demVersion to old.demMaxZoom)  // an older server says nothing: AWS Terrarium to z13
        val info = Tuileries.parseInfo("""{"version":"2","max_zoom":19,"dem":{"version":2,"max_zoom":15}}""")
        assertEquals(2 to 15, info.demVersion to info.demMaxZoom)
        for ((dem, expected) in listOf("""{"version":3,"max_zoom":18}""" to (3 to 15), """{"max_zoom":14}""" to (1 to 14),
                "\"v2\"" to (1 to 13), """{"version":true,"max_zoom":9}""" to (1 to 13))) {
            val got = Tuileries.parseInfo("""{"version":"2","dem":$dem}""")
            assertEquals(dem, expected, got.demVersion to got.demMaxZoom)
        }
        val dir = Files.createTempDirectory("tuileries-dem").toFile()
        try {
            TileServer.initCache(dir)
            MapConfig.set(source(), 17)
            TileServer.applyInfo(info)
            assertEquals("&photomax=17&demmax=15", MapConfig.pageQuery())
            MapConfig.set(null, 17)
            assertEquals("&photomax=17", MapConfig.pageQuery())  // without Tuileries: the page's own default
            MapConfig.set(source(), 17)
            assertEquals("&photomax=17&demmax=13", MapConfig.pageQuery())  // a new server: its info read again
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aNewReliefVersionDropsTheCachedRelief() {
        val dir = Files.createTempDirectory("tuileries-dem").toFile()
        try {
            val cache = TileCache(dir, 1_000_000)
            val dem = listOf(TileKey(TileKey.KIND_DEM, 9, 1, 1), TileKey(TileKey.KIND_DEM, 13, 1, 1)).map { File(dir, it.path) }
            val photo = File(dir, TileKey(TileKey.KIND_ORTHO, 15, 1, 1).path)
            // a relief tile that came in a metatile goes where its single request would
            val meta = File(dir, MetaEntry(TileKey.KIND_DEM, 14, 2, 2, Metatile.OK, "image/png", byteArrayOf(1)).cachePath()!!)
            for (f in dem + photo + meta) TileCache.writeAtomic(f, byteArrayOf(1))
            assertFalse(cache.dropStaleDem(1))  // no marker: a cache of version 1, kept
            assertTrue(dem.all { it.isFile })
            assertTrue(cache.dropStaleDem(2))
            assertFalse(File(dir, "dem").exists() || meta.exists())
            assertTrue(photo.isFile)
            assertEquals("2", File(dir, TileCache.DEM_VERSION_FILE).readText())
            assertFalse(dir.listFiles()!!.any { it.name.startsWith(".") })  // nothing left behind
            TileCache.writeAtomic(dem[0], byteArrayOf(2))
            assertFalse(cache.dropStaleDem(2))  // recorded: the new tiles stay
            assertTrue(dem[0].isFile)
            cache.clear()
            assertTrue(File(dir, TileCache.DEM_VERSION_FILE).isFile)  // still true of the tiles fetched next
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun prepareIsSentToTuileriesOnly() {
        val lats = DoubleArray(5000) { 45.0 + it * 0.00001 }
        val lons = DoubleArray(5000) { 6.0 + it * 0.00001 }
        val body = Tuileries.prepareLine(Route("test", lats, lons, null))
        val line = Json.parse(body).asMap()!!
        assertEquals("test", line["name"])
        val pts = line["line"].asList()!!
        assertTrue(pts.size <= Tuileries.PREPARE_MAX_POINTS + 1)
        assertEquals(listOf(6.0, 45.0), pts.first().asList()!!.map { it.asDouble() })
        assertEquals("""{"bbox":[6,45,6.1,45.1]}""", Tuileries.prepareBbox(Bbox(6.0, 45.0, 6.1, 45.1)))
        TileServer.prepare(body)  // not configured: nothing sent
        MapConfig.set(source(), 17)
        TileServer.prepare(Tuileries.prepareBbox(Bbox(6.0, 45.0, 6.1, 45.1)))
        val end = System.currentTimeMillis() + 5000
        while (fake.posted.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertEquals(listOf("/api/prepare" to """{"bbox":[6,45,6.1,45.1]}"""), fake.posted.toList())
        assertEquals("csecret", fake.requests.single().second["cf-access-client-secret"])
        assertEquals("1", fake.requests.single().second["x-tuileries"])
    }

    @Test
    fun routePlansStopAtThePhotoMaxZoom() {
        val lats = DoubleArray(50) { 45.0 + it * 0.0004 }
        val lons = DoubleArray(50) { 6.0 + it * 0.0006 }
        val route = Route("test", lats, lons, null)
        assertEquals(17, TilePlans.forRoute(route)!!.topZoom)
        for (max in 14..16) assertEquals(max, TilePlans.forRoute(route, photoMax = max)!!.topZoom)
        assertEquals(17, TilePlans.forRoute(route, photoMax = 19)!!.topZoom)  // packs never went past z17
    }

    @Test
    fun aPackDownloadsThroughTuileriesAndKeepsWebp() {
        val dir = Files.createTempDirectory("packs").toFile()
        try {
            fake.answers["/t/ortho/"] = Triple(200, "image/webp", WEBP)
            fake.answers["/peaks"] = Triple(200, "application/json", "[]".toByteArray())
            MapConfig.set(source(), 17)
            OfflinePacks.initDir(dir)
            val square = Bbox(6.0, 45.0, 6.001, 45.001)
            val route = Route("test", doubleArrayOf(45.0, 45.001), doubleArrayOf(6.0, 6.001), null)
            val id = OfflinePacks.prepareRoute(route, TilePlan(square, 8..9, null))
            val end = System.currentTimeMillis() + 20_000
            while (OfflinePacks.packs.value.firstOrNull { it.id == id }?.complete != true && System.currentTimeMillis() < end) Thread.sleep(50)
            assertTrue(OfflinePacks.progress.value[id]?.error ?: "not complete", OfflinePacks.packs.value.first { it.id == id }.complete)
            val tiles = File(dir, id).walkTopDown().filter { it.isFile && it.path.contains("/orthow/") }.toList()
            assertEquals(2, tiles.size)
            assertTrue(tiles.all { it.name.endsWith(".webp") && it.readBytes().contentEquals(WEBP) })
            val one = TileKey("ortho", 8, tiles.first { it.path.contains("/orthow/8/") }.parentFile!!.name.toInt(), tiles.first { it.path.contains("/orthow/8/") }.nameWithoutExtension.toInt())
            assertEquals(File(dir, "$id/${TileKey(TileKey.KIND_ORTHO_WEBP, one.z, one.x, one.y).path}"), one.variants().firstNotNullOfOrNull { OfflinePacks.findTile(it.path) })
            assertTrue(fake.requests.none { it.second["authorization"] == null })
        } finally {
            OfflinePacks.initDir(Files.createTempDirectory("packs-none").toFile())
            dir.deleteRecursively()
        }
    }
}
