package app.scriptorium.map

/** A tile the map page asks for: `/t/{kind}/{z}/{x}/{y}`. */
data class TileKey(val kind: String, val z: Int, val x: Int, val y: Int) {
    /** Relative path in a cache or pack folder. */
    val path: String get() = "$kind/$z/$x/$y.png"

    companion object {
        private val PATH = Regex("""^/t/(plan|dem)/(\d{1,2})/(\d{1,7})/(\d{1,7})(?:\.png)?$""")

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

/** Where tiles come from. Plan IGN covers France; elsewhere IGN answers "no data" and OSM takes over. */
object TileSources {
    const val USER_AGENT = "Scriptorium (https://github.com/KrustyLeBot/Griffonote)"

    fun ign(z: Int, x: Int, y: Int) =
        "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2" +
            "&STYLE=normal&FORMAT=image/png&TILEMATRIXSET=PM&TILEMATRIX=$z&TILEROW=$y&TILECOL=$x"

    fun osm(z: Int, x: Int, y: Int) = "https://tile.openstreetmap.org/$z/$x/$y.png"

    fun dem(z: Int, x: Int, y: Int) = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$z/$x/$y.png"

    /**
     * IGN has no tile there (outside France, sea): a 4xx, an empty body or a non-image answer (its
     * "No data found" exception report). Server errors are not that: the map shows nothing instead of
     * quietly switching style, and the next request tries IGN again. Nor are a rate limit (429) or a
     * request timeout (408): read as "no tile", a pack download would mark itself complete with holes.
     */
    fun ignHasNoTile(code: Int, contentType: String?, size: Int): Boolean =
        (code in 400..499 && code != 408 && code != 429) || code == 204 ||
            (code == 200 && (size == 0 || contentType?.startsWith("image/") == false))
}
