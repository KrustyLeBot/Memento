package app.memento.map

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

    fun peaksUrl(b: Bbox): String = "${base}peaks?w=${Json.num(b.w, 5)}&s=${Json.num(b.s, 5)}&e=${Json.num(b.e, 5)}&n=${Json.num(b.n, 5)}"

    val infoUrl: String get() = "${base}api/info"

    // a data class prints every field: the secrets stay out of logs
    override fun toString() = "Tuileries($base)"

    companion object {
        const val PHOTO_MAX_ZOOM_DEFAULT = 17
        /** 19: IGN's top level, also the page's former fixed maxzoom. */
        val PHOTO_MAX_ZOOM_RANGE = 14..19

        fun clampPhotoMaxZoom(z: Int) = z.coerceIn(PHOTO_MAX_ZOOM_RANGE)

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

        fun validCell(body: ByteArray, x: Int, y: Int): Boolean {
            val o = try { Json.parse(String(body, Charsets.UTF_8)).asMap() } catch (_: Json.JsonException) { null } ?: return false
            return o["v"].asDouble() == 1.0 && o["x"].asDouble() == x.toDouble() && o["y"].asDouble() == y.toDouble() && o["b"] is List<*>
        }

        fun parseInfo(text: String): TuileriesInfo {
            val o = try { Json.parse(text).asMap() } catch (_: Json.JsonException) { null }
            val version = o?.get("version") as? String ?: throw TuileriesException("Réponse inattendue : ce n'est pas un serveur Tuileries ?")
            return TuileriesInfo(
                (o["name"] as? String)?.ifEmpty { null } ?: "Tuileries",
                version,
                o["max_zoom"].asDouble()?.toInt(),
                o["layers"].asList().orEmpty().filterIsInstance<String>(),
            )
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

/** `GET /api/info`: what the settings' "Tester la connexion" shows. */
data class TuileriesInfo(val name: String, val version: String, val maxZoom: Int?, val layers: List<String>) {
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

    fun set(source: Tuileries?, photoMax: Int) {
        tuileries = source?.takeIf { it.configured }
        photoMaxZoom = Tuileries.clampPhotoMaxZoom(photoMax)
    }
}
