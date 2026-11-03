package app.memento.map

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.LruCache
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/** One HTTP answer, body read in full. */
class HttpResult(val code: Int, val contentType: String?, val body: ByteArray)

/**
 * After a connection failure, uncached tiles fail at once for a while instead of each waiting for its own
 * timeout: offline, the page gets its blank tiles immediately. Times are monotonic milliseconds.
 */
class Backoff(private val millis: Long = 20_000L) {
    @Volatile private var until = 0L

    fun failed(now: Long) {
        until = now + millis
    }

    fun succeeded() {
        until = 0L
    }

    fun blocked(now: Long): Boolean = now < until
}

/** A fetched tile: [key] says what it really is (an aerial-photo request answered by OpenStreetMap is an "osmfb2" PNG). */
class FetchedTile(val key: TileKey, val bytes: ByteArray)

/**
 * Serves the map page and its data from the app's own origin (`appassets.androidplatform.net`): the page
 * from the APK assets, tiles from the offline packs, then the browsing cache, then the network, the
 * peaks list and the buildings cells the same way. The network is IGN / OpenStreetMap / AWS, or only the Tuileries
 * server when one is configured ([MapConfig]). Runs on the WebView's background threads; every network step
 * has a deadline.
 */
object TileServer {
    const val HOST = "appassets.androidplatform.net"
    const val PAGE = "https://$HOST/map/index.html"
    private const val TAG = "TileServer"
    private const val TIMEOUT_MS = 8000
    private const val BROWSING_MAX = 300L * 1024 * 1024
    private const val PEAKS = "peaks"

    private lateinit var assets: AssetManager
    lateinit var cache: TileCache
        private set
    private lateinit var peaksDir: File

    @Synchronized
    fun init(context: Context) {
        if (::cache.isInitialized) return
        val app = context.applicationContext
        assets = app.assets
        val root = File(app.cacheDir, "maptiles")
        cache = TileCache(root, BROWSING_MAX)
        // inside the browsing cache: the answers count in its 300 MB and go least recently used first
        peaksDir = File(root, PEAKS)
    }

    fun handle(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.host != HOST) return null
        val path = url.path ?: return notFound()
        return try {
            when {
                path.startsWith("/map/") -> asset(path.removePrefix("/"))
                path.startsWith("/t/") -> TileKey.parse(path)?.let { tile(it) } ?: notFound()
                path == "/peaks" -> peaks(url)
                path.startsWith("/buildings/") -> TileKey.parseBuildings(path)?.let { buildings(it) } ?: notFound()
                else -> notFound()
            }
        } catch (e: Exception) {
            // thrown out of here it would kill the app; the page gets the failure and shows nothing there
            Log.w(TAG, "$path: $e")
            error(502, e.message ?: "Erreur réseau")
        }
    }

    private fun asset(name: String): WebResourceResponse {
        if (name.contains("..")) return notFound()
        val stream = try { assets.open(name) } catch (_: java.io.FileNotFoundException) { return notFound() }
        return WebResourceResponse(mime(name), if (mime(name).startsWith("text/") || name.endsWith(".js")) "utf-8" else null, stream)
    }

    private fun tile(key: TileKey): WebResourceResponse {
        // the file can vanish between the lookup and the open: a cache trim, an expired pack deleted
        for (v in key.variants()) {
            (OfflinePacks.findTile(v.path) ?: cache.get(v.path))?.let { openOrNull(it) }?.let { return image(v.mime, it) }
        }
        if (!NetState.online.value || backedOff()) return overzoomed(key) ?: notFound()
        val got = try {
            fetchShared(key, allowOsm = true)
        } catch (e: IOException) {
            // a network that drops mid-hike is offline for all purposes: the enlarged photo beats an error
            return overzoomed(key) ?: throw e
        } ?: return notFound()
        return image(got.key.mime, ByteArrayInputStream(got.bytes))
    }

    /** Process-wide: a failure seen by one tile, a cell or a pack download spares the next requests their timeouts. */
    private val backoff = Backoff()

    private fun backedOff() = backoff.blocked(SystemClock.elapsedRealtime())

    /** Fetches under way, by tile path: the page asking again for a tile on its way waits for that fetch. */
    private val fetching = ConcurrentHashMap<String, FutureTask<FetchedTile?>>()

    /** [fetch] once for concurrent requests of one tile; the request that fetched it caches it. */
    private fun fetchShared(key: TileKey, allowOsm: Boolean): FetchedTile? {
        val mine = FutureTask<FetchedTile?> { fetch(key, allowOsm) }
        val task = fetching.putIfAbsent(key.path, mine) ?: mine
        if (task !== mine) return await(task)
        try {
            mine.run()
            return await(mine)?.also { cache.put(it.key.path, it.bytes) }
        } finally {
            // released once the file is in place: a request arriving meanwhile finds the file, not a second fetch
            fetching.remove(key.path, mine)
        }
    }

    /** The task's result, or what it threw (an IOException stays one for the callers' fallbacks). */
    private fun <T> await(task: FutureTask<T>): T = try {
        task.get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    /** Overzoomed tiles already made, so panning back and forth does not decode again. */
    private val overzoomCache = object : LruCache<String, ByteArray>(OVERZOOM_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    private const val OVERZOOM_CACHE_BYTES = 12 * 1024 * 1024

    /** Decoded ancestor photos: the tiles cut from one ancestor decode its JPEG once, not once each. */
    private val ancestorCache = object : LruCache<String, Bitmap>(ANCESTOR_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private const val ANCESTOR_CACHE_BYTES = 4 * 1024 * 1024

    /** Decodes under way, by file: the children asked together wait for one decode. */
    private val decoding = ConcurrentHashMap<String, FutureTask<Bitmap?>>()

    /** Not recycled when evicted: another request may still be drawing from it; the GC frees it. */
    private fun ancestor(f: File): Bitmap? {
        ancestorCache.get(f.path)?.let { return it }
        val mine = FutureTask<Bitmap?> { BitmapFactory.decodeFile(f.path)?.also { ancestorCache.put(f.path, it) } }
        val task = decoding.putIfAbsent(f.path, mine) ?: mine
        if (task === mine) {
            try {
                mine.run()
            } finally {
                decoding.remove(f.path, mine)
            }
        }
        return await(task)
    }

    /** The nearest ancestor photo tile held offline (packs, then the browsing cache), cropped and enlarged. */
    private fun overzoomed(key: TileKey): WebResourceResponse? {
        if (key.kind != TileKey.KIND_ORTHO) return null
        overzoomCache.get(key.path)?.let { return image(key.mime, ByteArrayInputStream(it)) }
        for (o in Overzoom.candidates(key)) {
            // a photo, JPEG or WebP; never the OpenStreetMap stand-in, which is not the photo
            val f = o.ancestor.variants().filter { it.kind != TileKey.KIND_OSM }
                .firstNotNullOfOrNull { OfflinePacks.findTile(it.path) ?: cache.get(it.path) } ?: continue
            val bytes = try { crop(f, o) } catch (e: IOException) { Log.w(TAG, "overzoom ${key.path}: $e"); null } ?: continue
            overzoomCache.put(key.path, bytes)
            return image(key.mime, ByteArrayInputStream(bytes))
        }
        return null
    }

    private fun crop(f: File, o: Overzoom): ByteArray? {
        val src = ancestor(f) ?: return null
        val out = Bitmap.createBitmap(Overzoom.TILE_PX, Overzoom.TILE_PX, Bitmap.Config.ARGB_8888)
        try {
            // a tile smaller than 256 px (never seen from IGN) is scaled as if it were 256
            val k = src.width.toDouble() / Overzoom.TILE_PX
            val r = Rect((o.left * k).toInt(), (o.top * k).toInt(), ((o.left + o.size) * k).toInt(), ((o.top + o.size) * k).toInt())
            android.graphics.Canvas(out).drawBitmap(src, r, Rect(0, 0, Overzoom.TILE_PX, Overzoom.TILE_PX), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            val buf = java.io.ByteArrayOutputStream(16 * 1024)
            out.compress(Bitmap.CompressFormat.JPEG, 85, buf)
            return buf.toByteArray()
        } finally {
            // src belongs to ancestorCache
            out.recycle()
        }
    }

    /**
     * A tile from the network; null when no source has one there. [allowOsm] false for the offline packs:
     * the OSM tile policy forbids bulk downloads, so only browsing falls back to it.
     */
    fun fetch(key: TileKey, allowOsm: Boolean): FetchedTile? {
        MapConfig.tuileries?.let { t ->
            // Tuileries applies the OpenStreetMap rule itself: its PNG stand-in is refused where OSM would be
            val got = Tuileries.tileOf(key, get(t.tileUrl(key), t.headers())) ?: return null
            return got.takeIf { allowOsm || it.key.kind != TileKey.KIND_OSM }
        }
        if (key.kind == TileKey.KIND_BUILDINGS) return FetchedTile(key, Buildings.fetchCell(key.x, key.y, ::get))
        if (key.kind == TileKey.KIND_DEM) {
            val r = get(TileSources.dem(key.z, key.x, key.y))
            if (r.code == 404 || r.code == 403) return null
            if (r.code != 200 || r.body.isEmpty()) throw IOException("Relief : HTTP ${r.code}")
            return FetchedTile(key, r.body)
        }
        val ign = get(TileSources.ign(key.z, key.x, key.y))
        if (!TileSources.ignHasNoTile(ign.code, ign.contentType, ign.body)) {
            if (ign.code != 200) throw IOException("Photo IGN : HTTP ${ign.code}")
            return FetchedTile(key, ign.body)
        }
        if (!allowOsm) return null
        val osm = get(TileSources.osm(key.z, key.x, key.y))
        if (osm.code == 404) return null
        if (osm.code != 200 || osm.body.isEmpty()) throw IOException("OpenStreetMap : HTTP ${osm.code}")
        return FetchedTile(key.fallback(), osm.body)
    }

    // ---- buildings ----

    /** A cell from the packs, the browsing cache, else the WFS (cached); 404 offline when none holds it. */
    private fun buildings(key: TileKey): WebResourceResponse {
        (OfflinePacks.findTile(key.path) ?: cache.get(key.path))?.let { openOrNull(it) }?.let {
            return WebResourceResponse(key.mime, "utf-8", 200, "OK", headers(), it)
        }
        if (!NetState.online.value || backedOff()) return notFound()
        val got = fetchShared(key, allowOsm = false) ?: return notFound()
        return WebResourceResponse(key.mime, "utf-8", 200, "OK", headers(), ByteArrayInputStream(got.bytes))
    }

    // ---- peaks ----

    private fun peaks(url: Uri): WebResourceResponse {
        val q = listOf("w", "s", "e", "n").map { url.getQueryParameter(it)?.toDoubleOrNull() }
        if (q.any { it == null }) return error(400, "Paramètres w, s, e, n attendus")
        val req = Bbox(q[0]!!, q[1]!!, q[2]!!, q[3]!!)
        return json(Peaks.toJson(peaksFor(req)))
    }

    /** Exact cached answer, else (offline) a covering one, else computed online and cached. */
    fun peaksFor(req: Bbox): List<Peak> {
        val rel = "$PEAKS/" + Peaks.key(req) + ".json"
        cache.get(rel)?.let { f -> Peaks.parseCache(f.readText())?.let { return it.second } }
        packPeaks().firstOrNull { Peaks.key(it.first) == Peaks.key(req) }?.let { return it.second }
        if (!NetState.online.value || backedOff()) return Peaks.fromCovering(cachedPeaks(), req).orEmpty()
        val list = try {
            computePeaks(req)
        } catch (e: IOException) {
            return Peaks.fromCovering(cachedPeaks(), req) ?: throw e
        }
        cache.put(rel, Peaks.cacheJson(req, list).toByteArray())
        return list
    }

    fun computePeaks(b: Bbox): List<Peak> {
        MapConfig.tuileries?.let { t ->
            val r = get(t.peaksUrl(b), t.headers())
            if (r.code != 200) throw IOException("Sommets Tuileries : ${Tuileries.statusMessage(r.code)}")
            return try {
                Peaks.fromJson(String(r.body, Charsets.UTF_8))
            } catch (e: Json.JsonException) {
                throw IOException("Sommets Tuileries : réponse illisible")
            }
        }
        val wfs = get(Peaks.wfsUrl(b))
        if (wfs.code != 200) throw IOException("Sommets IGN : HTTP ${wfs.code}")
        val candidates = Peaks.preselect(Peaks.parseWfs(String(wfs.body, Charsets.UTF_8)))
        if (candidates.isEmpty()) return emptyList()
        val alti = get(Peaks.altiUrl(candidates))
        if (alti.code != 200) throw IOException("Altitudes IGN : HTTP ${alti.code}")
        val eles = Peaks.parseAlti(String(alti.body, Charsets.UTF_8))
        val withEle = candidates.mapIndexed { i, p -> p.copy(ele = eles.getOrElse(i) { Double.NaN }) }
        return Peaks.select(withEle, b)
    }

    private fun packPeaks() = parsePeaks(OfflinePacks.peaksFiles())

    private fun cachedPeaks(): Sequence<Pair<Bbox, List<Peak>>> =
        packPeaks() + parsePeaks(peaksDir.listFiles()?.asSequence() ?: emptySequence())

    private fun parsePeaks(files: Sequence<File>) = files.mapNotNull { f ->
        try { Peaks.parseCache(f.readText()) } catch (e: Exception) { Log.w(TAG, "${f.name}: ${e.message}"); null }
    }

    // ---- plumbing ----

    private fun openOrNull(f: File): FileInputStream? = try {
        FileInputStream(f)
    } catch (_: java.io.FileNotFoundException) {
        null
    }

    /**
     * Asks Tuileries to warm an area before a trip (`POST /api/prepare`, [body] from [Tuileries.prepareLine] or
     * [Tuileries.prepareBbox]); fire-and-forget on its own thread, a failure only logs. Nothing without Tuileries.
     */
    fun prepare(body: String) {
        val t = MapConfig.tuileries ?: return
        Thread {
            try {
                val r = post(t.prepareUrl, t.headers(), body)
                if (r.code !in 200..299) Log.w(TAG, "prepare: ${Tuileries.statusMessage(r.code)}")
            } catch (e: IOException) {
                Log.w(TAG, "prepare: $e")
            }
        }.apply { isDaemon = true }.start()
    }

    fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.instanceFollowRedirects = false
        c.requestMethod = "POST"
        c.doOutput = true
        for ((k, v) in headers) c.setRequestProperty(k, v)
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val answer = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            return HttpResult(code, c.contentType, answer)
        } finally {
            c.disconnect()
        }
    }

    /** `GET /api/info` of [t] (blocking): the settings' "Tester la connexion". */
    fun tuileriesInfo(t: Tuileries): TuileriesInfo {
        if (!t.configured) throw TuileriesException("Adresse invalide : https://… attendue")
        val r = try {
            get(t.infoUrl, t.headers())
        } catch (e: IOException) {
            throw TuileriesException("Serveur injoignable (${e.message})")
        }
        if (r.code != 200) throw TuileriesException(Tuileries.statusMessage(r.code))
        return Tuileries.parseInfo(String(r.body, Charsets.UTF_8))
    }

    /**
     * [headers]: Tuileries' credentials, given only for its URLs. A redirect is then not followed (Cloudflare Access's
     * login page), so they never travel elsewhere.
     */
    fun get(url: String, headers: Map<String, String>? = null): HttpResult {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        if (headers != null) {
            c.instanceFollowRedirects = false
            for ((k, v) in headers) c.setRequestProperty(k, v)
        } else {
            c.setRequestProperty("User-Agent", TileSources.USER_AGENT)
        }
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            // any answer, an HTTP error too, means the network works
            backoff.succeeded()
            return HttpResult(code, c.contentType, body)
        } catch (e: IOException) {
            // here only a connection failure: no answer, a timeout, a dropped transfer
            backoff.failed(SystemClock.elapsedRealtime())
            throw e
        } finally {
            c.disconnect()
        }
    }

    private fun image(mime: String, stream: java.io.InputStream) = WebResourceResponse(mime, null, 200, "OK", headers(), stream)

    private fun json(text: String) =
        WebResourceResponse("application/json", "utf-8", 200, "OK", headers(), ByteArrayInputStream(text.toByteArray()))

    private fun notFound() = error(404, "Not Found")

    private fun error(code: Int, message: String) =
        WebResourceResponse("text/plain", "utf-8", code, if (code == 404) "Not Found" else "Error", headers(), ByteArrayInputStream(message.toByteArray()))

    private fun headers() = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store")

    private fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "js", "mjs" -> "application/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "woff2" -> "font/woff2"
        "pbf" -> "application/x-protobuf"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }
}
