package app.scriptorium.map

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP answer, body read in full. */
class HttpResult(val code: Int, val contentType: String?, val body: ByteArray)

/**
 * Serves the map page and its data from the app's own origin (`appassets.androidplatform.net`): the page
 * from the APK assets, tiles from the offline packs, then the browsing cache, then the network, and the
 * peaks list. Runs on the WebView's background threads; every network step has a deadline.
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
        val local = (OfflinePacks.findTile(key.path) ?: cache.get(key.path))?.let { openOrNull(it) }
        if (local != null) return png(local)
        if (!NetState.online.value) return notFound()
        val bytes = fetch(key, allowOsm = true) ?: return notFound()
        cache.put(key.path, bytes)
        return png(ByteArrayInputStream(bytes))
    }

    /**
     * A tile from the network; null when no source has one there. [allowOsm] false for the offline packs:
     * the OSM tile policy forbids bulk downloads, so only browsing falls back to it.
     */
    fun fetch(key: TileKey, allowOsm: Boolean): ByteArray? {
        if (key.kind == "dem") {
            val r = get(TileSources.dem(key.z, key.x, key.y))
            if (r.code == 404 || r.code == 403) return null
            if (r.code != 200 || r.body.isEmpty()) throw IOException("Relief : HTTP ${r.code}")
            return r.body
        }
        val ign = get(TileSources.ign(key.z, key.x, key.y))
        if (!TileSources.ignHasNoTile(ign.code, ign.contentType, ign.body.size)) {
            if (ign.code != 200) throw IOException("Plan IGN : HTTP ${ign.code}")
            return ign.body
        }
        if (!allowOsm) return null
        val osm = get(TileSources.osm(key.z, key.x, key.y))
        if (osm.code == 404) return null
        if (osm.code != 200 || osm.body.isEmpty()) throw IOException("OpenStreetMap : HTTP ${osm.code}")
        return osm.body
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
        if (!NetState.online.value) return Peaks.fromCovering(cachedPeaks(), req).orEmpty()
        val list = try {
            computePeaks(req)
        } catch (e: IOException) {
            return Peaks.fromCovering(cachedPeaks(), req) ?: throw e
        }
        cache.put(rel, Peaks.cacheJson(req, list).toByteArray())
        return list
    }

    fun computePeaks(b: Bbox): List<Peak> {
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

    fun get(url: String): HttpResult {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.setRequestProperty("User-Agent", TileSources.USER_AGENT)
        try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            return HttpResult(code, c.contentType, body)
        } finally {
            c.disconnect()
        }
    }

    private fun png(stream: java.io.InputStream) = WebResourceResponse("image/png", null, 200, "OK", headers(), stream)

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
        "svg" -> "image/svg+xml"
        "woff2" -> "font/woff2"
        "pbf" -> "application/x-protobuf"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }
}
