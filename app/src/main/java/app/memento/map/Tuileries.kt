package app.memento.map

import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Base64

/**
 * The optional Tuileries tile server (the owner's own, self-hosted). When [configured], every tile, buildings cell and
 * summits list the map page and the offline packs need comes from it instead of IGN / OpenStreetMap / AWS, with HTTP
 * Basic auth and the optional Cloudflare Access service-token headers added here ([headers]); the page never sees them.
 * Same contract and rules as windows/memento_win/core/tuileries.py.
 */
data class Tuileries(
    val url: String,
    val user: String = "",
    val password: String = "",
    val cfId: String = "",
    val cfSecret: String = "",
) {
    val base: String get() = normalize(url)

    val configured: Boolean get() = base.isNotEmpty()

    /** Only ever sent to [base]: the credentials never reach IGN, OpenStreetMap or AWS. */
    fun headers(): Map<String, String> {
        val out = linkedMapOf("User-Agent" to TileSources.USER_AGENT)
        if (user.isNotEmpty() || password.isNotEmpty()) {
            out["Authorization"] = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
        }
        if (cfId.isNotEmpty()) out["CF-Access-Client-Id"] = cfId
        if (cfSecret.isNotEmpty()) out["CF-Access-Client-Secret"] = cfSecret
        return out
    }

    /** `t/ortho|dem/{z}/{x}/{y}`, `buildings/15/{x}/{y}`: the very paths the hosts serve the page. */
    fun tileUrl(key: TileKey): String =
        if (key.kind == TileKey.KIND_BUILDINGS) "${base}buildings/${key.z}/${key.x}/${key.y}" else "${base}t/${key.kind}/${key.z}/${key.x}/${key.y}"

    fun trailsUrl(z: Int, x: Int, y: Int): String = "${base}trails/$z/$x/$y"

    fun peaksUrl(b: Bbox): String = "${base}peaks?w=${Json.num(b.w, 5)}&s=${Json.num(b.s, 5)}&e=${Json.num(b.e, 5)}&n=${Json.num(b.n, 5)}"

    val infoUrl: String get() = "${base}api/info"

    val prepareUrl: String get() = "${base}api/prepare"

    // a data class prints every field: the secrets stay out of logs
    override fun toString() = "Tuileries($base)"

    companion object {
        const val PHOTO_MAX_ZOOM_DEFAULT = 17
        /** 19: IGN's top level, also the page's former fixed maxzoom. */
        val PHOTO_MAX_ZOOM_RANGE = 14..19

        fun clampPhotoMaxZoom(z: Int) = z.coerceIn(PHOTO_MAX_ZOOM_RANGE)

        /** A server without `dem` in its /api/info: the AWS Terrarium relief, to z13. */
        const val DEM_VERSION_DEFAULT = 1
        const val DEM_MAX_ZOOM_DEFAULT = 13
        /** The map page's own clamp of `demmax`. */
        val DEM_MAX_ZOOM_RANGE = 13..15

        /** `https://host/path/` with its trailing slash, or "" when it is not an http(s) URL with a host. */
        fun normalize(url: String): String {
            val u = url.trim()
            if (u.isEmpty()) return ""
            val uri = try { URI(u) } catch (_: Exception) { return "" }
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) return ""
            return if (u.endsWith("/")) u else "$u/"
        }

        /**
         * A tile answer of Tuileries, cached under the key its type says ([TileKey.typed]: a WebP, JPEG or the PNG
         * OpenStreetMap stand-in for a photo); null when no source has one (404, 204). Anything else is an error:
         * thrown, so browsing shows nothing there and a pack download retries later.
         */
        fun tileOf(key: TileKey, r: HttpResult): FetchedTile? {
            if (r.code == 404 || r.code == 204) return null
            if (r.code != 200 || r.body.isEmpty()) throw IOException("Tuileries : ${statusMessage(r.code)}")
            if (key.kind == TileKey.KIND_BUILDINGS) {
                if (!validCell(r.body, key.x, key.y)) throw IOException("Tuileries : bâtiments illisibles")
                return FetchedTile(key, r.body)
            }
            val typed = key.typed(r.contentType) ?: throw IOException("Tuileries : type inattendu ${r.contentType}")
            return FetchedTile(typed, r.body)
        }

        /** Points sent at most in a `POST /api/prepare` line: the server only needs the corridor's shape. */
        const val PREPARE_MAX_POINTS = 2000

        /** `{"line":[[lon,lat],...],"name":…}`, the route thinned evenly to [PREPARE_MAX_POINTS] (Tuileries warms a 1 km corridor). */
        fun prepareLine(route: Route): String {
            val n = route.size
            val step = maxOf(1, (n + PREPARE_MAX_POINTS - 1) / PREPARE_MAX_POINTS)
            val idx = (0 until n step step).toMutableList().apply { if (n > 0 && last() != n - 1) add(n - 1) }
            return idx.joinToString(",", "{\"line\":[", "],\"name\":${Json.quote(route.name)}}") {
                "[${Json.num(route.lons[it], 6)},${Json.num(route.lats[it], 6)}]"
            }
        }

        fun prepareBbox(b: Bbox): String = "{\"bbox\":[" + b.toList().joinToString(",") { Json.num(it, 6) } + "]}"

        /**
         * A trails tile as the page takes it: the (un-gzipped) vector tile, an empty one for "no trail here" (204, and
         * 404 while Tuileries has not built its trails: never cached, asked again later); an error throws.
         */
        fun trailsOf(r: HttpResult): ByteArray {
            if (r.code == 204 || r.code == 404) return ByteArray(0)
            if (r.code != 200) throw IOException("Sentiers Tuileries : ${statusMessage(r.code)}")
            val b = r.body
            // stored gzipped by Tuileries and sent with a Content-Encoding this client did not ask for
            return if (b.size >= 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()) java.util.zip.GZIPInputStream(b.inputStream()).use { it.readBytes() } else b
        }

        fun validCell(body: ByteArray, x: Int, y: Int): Boolean {
            val o = try { Json.parse(String(body, Charsets.UTF_8)).asMap() } catch (_: Json.JsonException) { null } ?: return false
            // v2: Tuileries' cells with their roofs' colours ("c"), which the page uses instead of sampling the photo
            return o["v"].asDouble() in setOf(1.0, 2.0) && o["x"].asDouble() == x.toDouble() && o["y"].asDouble() == y.toDouble() && o["b"] is List<*>
        }

        fun parseInfo(text: String): TuileriesInfo {
            val o = try { Json.parse(text).asMap() } catch (_: Json.JsonException) { null }
            val version = o?.get("version") as? String ?: throw TuileriesException("Réponse inattendue : ce n'est pas un serveur Tuileries ?")
            return TuileriesInfo(
                (o["name"] as? String)?.ifEmpty { null } ?: "Tuileries",
                version,
                o["max_zoom"].asDouble()?.toInt(),
                o["layers"].asList().orEmpty().filterIsInstance<String>(),
                attributionOf(o),
                o["dem"].asMap()?.get("version").asDouble()?.toInt() ?: DEM_VERSION_DEFAULT,
                o["dem"].asMap()?.get("max_zoom").asDouble()?.toInt()?.coerceIn(DEM_MAX_ZOOM_RANGE) ?: DEM_MAX_ZOOM_DEFAULT,
            )
        }

        /**
         * The photo's credits (`attributions.ortho`, e.g. Sentinel-2 by EOX abroad) and the trails', one line; the page
         * already names IGN, OpenStreetMap and Terrarium. A plain `attribution` string is taken as is.
         */
        private fun attributionOf(o: Map<String, Any?>): String {
            (o["attribution"] as? String)?.let { return it.trim().take(300) }
            val credits = o["attributions"].asMap() ?: return ""
            val out = LinkedHashSet<String>()
            for (layer in listOf("ortho", "trails")) {
                for (t in credits[layer].asList().orEmpty().filterIsInstance<String>()) if (t.isNotBlank() && "IGN" !in t) out.add(t.trim())
            }
            return out.joinToString(" · ").take(300)
        }

        /** What a refusal of Tuileries (or of Cloudflare in front of it) means to the user. */
        fun statusMessage(code: Int): String = when {
            code == 401 -> "Identifiant ou mot de passe refusé"
            code == 403 || code in 300..399 -> "Accès refusé (Cloudflare Access : vérifier l'identifiant client et le secret)"
            code == 429 -> "Trop d'essais refusés : réessayer plus tard"
            code in 502..504 -> "Serveur injoignable derrière le tunnel (HTTP $code)"
            else -> "Erreur HTTP $code"
        }
    }
}

/** `GET /api/info`: what the settings' "Tester la connexion" shows; a new [demVersion] replaces the cached relief. */
data class TuileriesInfo(
    val name: String,
    val version: String,
    val maxZoom: Int?,
    val layers: List<String>,
    val attribution: String = "",
    val demVersion: Int = Tuileries.DEM_VERSION_DEFAULT,
    val demMaxZoom: Int = Tuileries.DEM_MAX_ZOOM_DEFAULT,
) {
    fun summary(): String = "Connecté : $name $version" + (maxZoom?.let { " · photos archivées jusqu'au zoom $it" } ?: "")
}

class TuileriesException(message: String) : IOException(message)

/**
 * The map settings in force for the whole process (tile server, offline packs, page URL): set at startup from
 * [app.memento.Settings] and again whenever the user changes them.
 */
object MapConfig {
    /** Null: IGN, OpenStreetMap and AWS directly. */
    @Volatile
    var tuileries: Tuileries? = null
        private set

    @Volatile
    var photoMaxZoom: Int = Tuileries.PHOTO_MAX_ZOOM_DEFAULT
        private set

    /** Tuileries' own credit (the photo abroad), from its /api/info: [TileServer.refreshInfo]. */
    @Volatile
    var attribution: String = ""

    /** Tuileries' relief top zoom, from the same /api/info. */
    @Volatile
    var demMaxZoom: Int = Tuileries.DEM_MAX_ZOOM_DEFAULT

    /**
     * Where the last relief top zoom is kept, per server (set by [TileServer.init]): offline, /api/info cannot be read,
     * and the page would fall back to z13 and never draw the finer relief the offline packs hold.
     */
    @Volatile
    var demMemory: File? = null

    fun rememberDemMax(zoom: Int) {
        demMaxZoom = zoom
        val t = tuileries ?: return
        try {
            demMemory?.writeText("${t.url}\n$zoom")
        } catch (e: IOException) {
            // only a convenience for offline use: the next /api/info read sets it again
        }
    }

    fun recallDemMax() {
        val t = tuileries ?: return
        val lines = try { demMemory?.takeIf { it.isFile }?.readLines() } catch (e: IOException) { null } ?: return
        if (lines.size >= 2 && lines[0] == t.url) lines[1].toIntOrNull()?.let { demMaxZoom = it.coerceIn(Tuileries.DEM_MAX_ZOOM_RANGE) }
    }

    fun set(source: Tuileries?, photoMax: Int) {
        val t = source?.takeIf { it.configured }
        if (t != tuileries) {
            attribution = ""
            demMaxZoom = Tuileries.DEM_MAX_ZOOM_DEFAULT
            tuileries = t
            recallDemMax()
        }
        tuileries = t
        photoMaxZoom = Tuileries.clampPhotoMaxZoom(photoMax)
    }

    /** What the page takes from the settings: the photo's top zoom and, with Tuileries, its trails, relief top zoom and credit. */
    fun pageQuery(): String {
        var q = "&photomax=$photoMaxZoom"
        if (tuileries != null) {
            q += "&trails=1&demmax=$demMaxZoom"
            if (attribution.isNotEmpty()) q += "&attrib=" + java.net.URLEncoder.encode(attribution, "UTF-8").replace("+", "%20")
        }
        return q
    }
}
