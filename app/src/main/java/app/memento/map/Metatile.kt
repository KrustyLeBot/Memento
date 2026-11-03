package app.memento.map

import java.io.IOException
import java.util.zip.GZIPInputStream

/** One entry of a Tuileries metatile: a photo, relief, buildings or trails tile, its data un-gzipped. */
class MetaEntry(val layer: String, val z: Int, val x: Int, val y: Int, val status: Int, val mime: String, val data: ByteArray) {
    /** Where it goes in the browsing cache (the very file its single request would fill); null: not to be kept. */
    fun cachePath(): String? {
        if (status != Metatile.OK || data.isEmpty()) return null
        return when (layer) {
            TileKey.KIND_ORTHO, TileKey.KIND_DEM -> TileKey(layer, z, x, y).typed(mime)?.path
            TileKey.KIND_BUILDINGS -> TileKey(layer, z, x, y).path.takeIf { z == Buildings.Z && Tuileries.validCell(data, x, y) }
            Metatile.TRAILS -> "trails/$z/$x/$y.pbf".takeIf { mime == Metatile.FORMATS[5] }
            else -> null
        }
    }
}

/**
 * `GET /meta/{z}/{x}/{y}?layers=ortho,dem,buildings,trails` of Tuileries (contract "v1.1 metatiles"): a 4x4 photo
 * block with the relief, buildings cell and trails under it, in one answer, so a weak connection carries one request
 * where the page makes dozens. Same reader as windows/memento_win/core/tuileries.py `parse_meta`.
 */
object Metatile {
    const val SIDE = 4
    const val OK = 0
    const val NONE = 1
    const val ERROR = 2
    const val PENDING = 3
    const val TRAILS = "trails"
    val LAYERS = listOf(TileKey.KIND_ORTHO, TileKey.KIND_DEM, TileKey.KIND_BUILDINGS, TRAILS)
    val FORMATS = mapOf(
        1 to "image/webp", 2 to "image/png", 3 to "image/jpeg", 4 to "application/json", 5 to "application/vnd.mapbox-vector-tile", 6 to "image/avif",
    )
    private const val HEAD = 18
    private const val ENTRY = 17

    /** The block (z, x0, y0) holding a photo tile. */
    fun block(key: TileKey) = Triple(key.z, key.x - key.x % SIDE, key.y - key.y % SIDE)

    fun url(t: Tuileries, block: Triple<Int, Int, Int>) =
        "${t.base}meta/${block.first}/${block.second}/${block.third}?layers=" + LAYERS.joinToString(",")

    /** IOException on anything but a well-formed metatile. */
    fun parse(b: ByteArray): List<MetaEntry> {
        if (b.size < HEAD || String(b, 0, 4, Charsets.ISO_8859_1) != "TUIM" || b[4].toInt() != 1) throw IOException("Métatuile illisible")
        val count = u16(b, 16)
        val out = ArrayList<MetaEntry>(count)
        var at = HEAD
        repeat(count) {
            if (at + ENTRY > b.size) throw IOException("Métatuile tronquée")
            val layer = b[at].toInt() and 0xff
            val length = u32(b, at + 13)
            if (layer >= LAYERS.size || length < 0 || at + ENTRY + length > b.size) throw IOException("Métatuile illisible")
            var status = b[at + 10].toInt() and 0xff
            val encoding = b[at + 12].toInt() and 0xff
            var data = b.copyOfRange(at + ENTRY, at + ENTRY + length)
            if (status == OK && encoding == 1) {
                data = try { GZIPInputStream(data.inputStream()).use { it.readBytes() } } catch (_: IOException) { status = ERROR; ByteArray(0) }
            } else if (status == OK && encoding != 0) {
                status = ERROR  // an encoding this client does not know: the tile is asked for alone
                data = ByteArray(0)
            }
            out.add(MetaEntry(LAYERS[layer], b[at + 1].toInt() and 0xff, u32(b, at + 2), u32(b, at + 6), status,
                FORMATS[b[at + 11].toInt() and 0xff].orEmpty(), data))
            at += ENTRY + length
        }
        return out
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    private fun u32(b: ByteArray, i: Int) =
        ((b[i].toInt() and 0xff) shl 24) or ((b[i + 1].toInt() and 0xff) shl 16) or ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
}
