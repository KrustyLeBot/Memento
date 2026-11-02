package app.memento.map

/**
 * A tile the map page asks for: `/t/{kind}/{z}/{x}/{y}`, kind "ortho" (the IGN aerial photo, JPEG) or "dem"
 * (Terrarium relief, PNG). "osmfb" is never asked for: it is where the photo's OpenStreetMap stand-in is cached,
 * in its own folder so a file's type follows its folder. "buildings" is a z15 cell of [Buildings] (JSON), asked for
 * as `/buildings/15/{x}/{y}`.
 */
data class TileKey(val kind: String, val z: Int, val x: Int, val y: Int) {
    /** Relative path in a cache or pack folder. */
    val path: String get() = "$kind/$z/$x/$y.${when (kind) { KIND_ORTHO -> "jpg"; KIND_BUILDINGS -> "json"; else -> "png" }}"

    val mime: String get() = when (kind) { KIND_ORTHO -> "image/jpeg"; KIND_BUILDINGS -> "application/json"; else -> "image/png" }

    fun fallback() = TileKey(KIND_OSM, z, x, y)

    companion object {
        const val KIND_ORTHO = "ortho"
        const val KIND_DEM = "dem"
        const val KIND_OSM = "osmfb"
        const val KIND_BUILDINGS = "buildings"
        /** Browsing-cache folders deleted at startup: a former base layer; OSM stand-ins once cached for IGN refusals. */
        val OLD_KINDS = listOf("plan", "osm")
        private val PATH = Regex("""^/t/(ortho|dem)/(\d{1,2})/(\d{1,7})/(\d{1,7})(?:\.png|\.jpg)?$""")
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

    /**
     * IGN has no photo there: a 404 (its "No data found" report, abroad and at sea from z13 or so), a 400 (past the edge
     * of its tile matrix), an empty body,
     * a non-image answer, or the uniform white JPEG it sends at the edge of its coverage. A flat non-white tile
     * (the sea's dark blue at low zoom) is a real answer. Server errors are not "no photo": the map shows nothing
     * instead of quietly switching style, and the next request tries IGN again. Nor are a rate limit (429) or a
     * request timeout (408) or a refusal (403): read as "no tile", browsing caches an OSM stand-in in the middle of
     * France for good, and a pack download marks itself complete with holes.
     */
    fun ignHasNoTile(code: Int, contentType: String?, body: ByteArray): Boolean =
        code == 400 || code == 404 || code == 204 ||
            (code == 200 && (body.isEmpty() || contentType?.startsWith("image/") == false || FlatJpeg.isWhite(body)))
}

/**
 * Recognises a JPEG that is one flat colour, without decoding it: every block has no AC coefficient and, after the
 * first, a DC difference of zero, so only the Huffman codes are walked. Same rule as `jpeg_uniform_colour` in the
 * Windows app's core/mapcache.py. Only small files are looked at: a tile with anything on it is several kilobytes.
 */
object FlatJpeg {
    /** The (Y, Cb, Cr) colour 0..255 of a flat baseline JPEG, else null (a photo, progressive, restart markers, damaged). */
    fun colour(data: ByteArray): IntArray? {
        if (data.size > 4096 || data.size < 4 || u(data, 0) != 0xFF || u(data, 1) != 0xD8) return null
        return try { flat(data) } catch (_: IndexOutOfBoundsException) { null } catch (_: IllegalStateException) { null }
    }

    fun isWhite(data: ByteArray): Boolean {
        val c = colour(data) ?: return false
        return c[0] >= 248 && (1 until c.size).all { kotlin.math.abs(c[it] - 128) <= 4 }
    }

    private fun u(d: ByteArray, i: Int) = d[i].toInt() and 0xFF

    private class Comp(val id: Int, val h: Int, val v: Int, val q: Int)

    private fun flat(d: ByteArray): IntArray? {
        val quant = HashMap<Int, Int>()
        val huff = HashMap<Int, Map<Long, Int>>()
        var comps = emptyList<Comp>()
        var width = 0
        var height = 0
        var pos = 2
        var body: Int
        while (true) {
            check(u(d, pos) == 0xFF)
            val marker = u(d, pos + 1)
            val length = (u(d, pos + 2) shl 8) or u(d, pos + 3)
            body = pos + 4
            val end = pos + 2 + length
            pos = end
            when {
                marker == 0xDB -> {
                    var i = body
                    while (i < end) {
                        val precision = u(d, i) shr 4
                        quant[u(d, i) and 15] = if (precision != 0) (u(d, i + 1) shl 8) or u(d, i + 2) else u(d, i + 1)
                        i += 1 + 64 * (precision + 1)
                    }
                }
                marker == 0xC0 || marker == 0xC1 -> {
                    height = (u(d, body + 1) shl 8) or u(d, body + 2)
                    width = (u(d, body + 3) shl 8) or u(d, body + 4)
                    comps = (0 until u(d, body + 5)).map { k ->
                        val b = body + 6 + 3 * k
                        Comp(u(d, b), u(d, b + 1) shr 4, u(d, b + 1) and 15, u(d, b + 2))
                    }
                }
                marker == 0xC4 -> {
                    var i = body
                    while (i < end) {
                        val table = u(d, i)
                        val codes = HashMap<Long, Int>()
                        var code = 0
                        var k = i + 17
                        for (bits in 1..16) {
                            repeat(u(d, i + bits)) { codes[(bits.toLong() shl 32) or code.toLong()] = u(d, k); code++; k++ }
                            code = code shl 1
                        }
                        huff[table] = codes
                        i = k
                    }
                }
                marker == 0xDD || marker == 0xC2 || (marker in 0xC3..0xCF && marker != 0xC4) -> return null
                marker == 0xDA -> break
            }
        }
        val scan = (0 until u(d, body)).associate { k -> u(d, body + 1 + 2 * k) to u(d, body + 2 + 2 * k) }
        if (comps.isEmpty() || scan.size != comps.size || width == 0 || height == 0) return null
        val bits = Bits(d, pos)
        val hmax = comps.maxOf { it.h }
        val vmax = comps.maxOf { it.v }
        val mcus = if (comps.size > 1) ceilDiv(width, 8 * hmax) * ceilDiv(height, 8 * vmax) else ceilDiv(width, 8) * ceilDiv(height, 8)
        val dc = HashMap<Int, Int>()
        repeat(mcus) {
            for (c in comps) {
                val tables = scan[c.id] ?: return null
                val dcTable = huff[tables shr 4] ?: return null
                val acTable = huff[0x10 or (tables and 15)] ?: return null
                repeat(if (comps.size > 1) c.h * c.v else 1) {
                    val diff = bits.extend(bits.decode(dcTable))
                    if (c.id in dc && diff != 0) return null
                    if (c.id !in dc) dc[c.id] = diff
                    if (bits.decode(acTable) != 0) return null // anything but an immediate end of block
                }
            }
        }
        if (comps.any { it.q !in quant }) return null
        return comps.map { c -> Math.round(dc.getValue(c.id) * quant.getValue(c.q) / 8.0 + 128).toInt().coerceIn(0, 255) }.toIntArray()
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    /** The entropy-coded bits, with the 0xFF00 byte stuffing undone. */
    private class Bits(val d: ByteArray, var pos: Int) {
        private var acc = 0
        private var n = 0

        fun bit(): Int {
            if (n == 0) {
                val byte = d[pos].toInt() and 0xFF
                if (byte == 0xFF) {
                    check(d[pos + 1].toInt() == 0) { "marker inside the scan" }
                    pos++
                }
                pos++
                acc = byte
                n = 8
            }
            n--
            return (acc shr n) and 1
        }

        fun decode(codes: Map<Long, Int>): Int {
            var code = 0
            for (length in 1..16) {
                code = (code shl 1) or bit()
                codes[(length.toLong() shl 32) or code.toLong()]?.let { return it }
            }
            throw IllegalStateException("bad Huffman code")
        }

        fun extend(size: Int): Int {
            var value = 0
            repeat(size) { value = (value shl 1) or bit() }
            return if (size > 0 && value < (1 shl (size - 1))) value - (1 shl size) + 1 else value
        }
    }
}
