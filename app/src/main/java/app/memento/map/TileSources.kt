package app.memento.map

/**
 * A tile the map page asks for: `/t/{kind}/{z}/{x}/{y}`, kind "ortho" (the IGN aerial photo, JPEG), "dem"
 * (Terrarium relief from AWS, 256 px PNG) or "demhd" (Terrarium relief from Mapterhorn, 512 px WebP). "osmfb2" is never asked for: it is where the photo's OpenStreetMap stand-in is cached,
 * in its own folder so a file's type follows its folder. "buildings" is a z15 cell of [Buildings] (JSON), asked for
 * as `/buildings/15/{x}/{y}`.
 */
data class TileKey(val kind: String, val z: Int, val x: Int, val y: Int) {
    /** Relative path in a cache or pack folder. */
    val path: String get() = "$kind/$z/$x/$y.${when (kind) { KIND_ORTHO -> "jpg"; KIND_BUILDINGS -> "json"; KIND_DEM_HD -> "webp"; else -> "png" }}"

    val mime: String get() = when (kind) {
        KIND_ORTHO -> "image/jpeg"; KIND_BUILDINGS -> "application/json"; KIND_DEM_HD -> "image/webp"; else -> "image/png"
    }

    fun fallback() = TileKey(KIND_OSM, z, x, y)

    companion object {
        const val KIND_ORTHO = "ortho"
        const val KIND_DEM = "dem"
        const val KIND_DEM_HD = "demhd"
        const val KIND_OSM = "osmfb2"
        const val KIND_BUILDINGS = "buildings"
        /** Browsing-cache folders deleted at startup: a former base layer; OSM stand-ins once cached for IGN refusals. */
        val OLD_KINDS = listOf("plan", "osm", "osmfb")
        private val PATH = Regex("""^/t/(ortho|dem|demhd)/(\d{1,2})/(\d{1,7})/(\d{1,7})(?:\.png|\.jpg|\.webp)?$""")
        private val BUILDINGS_PATH = Regex("""^/buildings/(\d{1,2})/(\d{1,7})/(\d{1,7})$""")

        /** `/buildings/15/{x}/{y}`: only z15 cells exist. */
        fun parseBuildings(path: String): TileKey? {
            val m = BUILDINGS_PATH.matchEntire(path) ?: return null
            val (z, x, y) = m.destructured.toList().map { it.toInt() }
            if (z != Buildings.Z || x >= (1 shl z) || y >= (1 shl z)) return null
            return TileKey(KIND_BUILDINGS, z, x, y)
        }

        fun parse(path: String): TileKey? {
            val m = PATH.matchEntire(path) ?: return null
            val z = m.groupValues[2].toInt()
            val x = m.groupValues[3].toInt()
            val y = m.groupValues[4].toInt()
            if (z > 20 || x >= (1 shl z) || y >= (1 shl z)) return null
            return TileKey(m.groupValues[1], z, x, y)
        }
    }
}

/** Where tiles come from. The IGN orthophotos cover France; elsewhere IGN answers "no data" and OSM takes over. */
object TileSources {
    const val USER_AGENT = "Memento (https://github.com/KrustyLeBot/Memento)"

    fun ign(z: Int, x: Int, y: Int) =
        "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=ORTHOIMAGERY.ORTHOPHOTOS" +
            "&STYLE=normal&FORMAT=image/jpeg&TILEMATRIXSET=PM&TILEMATRIX=$z&TILEROW=$y&TILECOL=$x"

    fun osm(z: Int, x: Int, y: Int) = "https://tile.openstreetmap.org/$z/$x/$y.png"

    fun dem(z: Int, x: Int, y: Int) = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$z/$x/$y.png"

    fun demHd(z: Int, x: Int, y: Int) = "https://tiles.mapterhorn.com/$z/$x/$y.webp"

    /**
     * IGN has no photo there: a 404 (its "No data found" report, abroad and at sea from z13 or so), a 400 (past the edge
     * of its tile matrix), an empty body or a non-image answer. Server errors are not "no photo": the map shows nothing
     * instead of quietly switching style, and the next request tries IGN again. Nor are a rate limit (429), a request
     * timeout (408) or a refusal (403): read as "no tile", browsing caches an OSM stand-in in the middle of France for
     * good, and a pack download marks itself complete with holes. The white tiles off the coast are kept: the map page
     * shows the parent tile through their white pixels.
     */
    fun ignHasNoTile(code: Int, contentType: String?, body: ByteArray): Boolean =
        code == 400 || code == 404 || code == 204 ||
            (code == 200 && (body.isEmpty() || contentType?.startsWith("image/") == false))
}
