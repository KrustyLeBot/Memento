package app.memento.scan

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class PageFormat(val label: String, val widthPt: Int, val heightPt: Int) {
    A4("A4", 595, 842),
    LETTER("US Letter", 612, 792),
}

/** Scan page size setting: a fixed format, or guessed from each page's shape. */
enum class ScanFormat(val label: String) { AUTO("Automatique"), A4("A4"), LETTER("US Letter") }

/**
 * Builds the PDF of a scan: one page per JPEG, the image embedded as is (no re-encoding, so the file
 * stays small) and fitted on an A4 or US Letter page. Pure Kotlin so it can be unit-tested on the JVM.
 */
object ScanPdf {
    class Jpeg(val bytes: ByteArray, val width: Int, val height: Int, val components: Int)

    /** Reads size and colour components from the JPEG's frame header (SOFn marker). */
    fun readJpeg(bytes: ByteArray): Jpeg {
        require(bytes.size > 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) { "Pas une image JPEG" }
        var i = 2
        while (i + 9 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) { i++; continue }
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue }
            val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            val isFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isFrame) {
                val height = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                val width = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                return Jpeg(bytes, width, height, bytes[i + 9].toInt() and 0xFF)
            }
            i += 2 + length
        }
        throw IllegalArgumentException("En-tête JPEG introuvable")
    }

    /**
     * A4 is longer (1.414) than US Letter (1.294): once the scanner has straightened the page, its shape
     * tells which one it is. Shapes close to neither (a receipt, a card) fall back to A4.
     */
    fun guessFormat(width: Int, height: Int): PageFormat {
        val ratio = max(width, height).toDouble() / min(width, height)
        val toA4 = abs(ratio - 842.0 / 595)
        val toLetter = abs(ratio - 792.0 / 612)
        return if (toLetter < toA4 && toLetter < 0.05) PageFormat.LETTER else PageFormat.A4
    }

    fun build(pages: List<Jpeg>, setting: ScanFormat): ByteArray {
        require(pages.isNotEmpty())
        val out = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun obj(content: () -> Unit) {
            offsets += out.size()
            out.write("${offsets.size} 0 obj\n".toByteArray())
            content()
            out.write("\nendobj\n".toByteArray())
        }
        fun text(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))

        text("%PDF-1.4\n%âãÏÓ\n")
        // Objects: 1 catalog, 2 page tree, then per page: page, content stream, image.
        val pageIds = pages.indices.map { 3 + it * 3 }
        obj { text("<< /Type /Catalog /Pages 2 0 R >>") }
        obj { text("<< /Type /Pages /Count ${pages.size} /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] >>") }
        pages.forEachIndexed { index, jpeg ->
            val format = when (setting) {
                ScanFormat.A4 -> PageFormat.A4
                ScanFormat.LETTER -> PageFormat.LETTER
                ScanFormat.AUTO -> guessFormat(jpeg.width, jpeg.height)
            }
            val landscape = jpeg.width > jpeg.height
            val pw = if (landscape) format.heightPt else format.widthPt
            val ph = if (landscape) format.widthPt else format.heightPt
            // Fit the image inside the page, centred.
            val scale = min(pw.toDouble() / jpeg.width, ph.toDouble() / jpeg.height)
            val w = jpeg.width * scale
            val h = jpeg.height * scale
            val x = (pw - w) / 2
            val y = (ph - h) / 2
            val id = pageIds[index]
            val content = "q %.2f 0 0 %.2f %.2f %.2f cm /Im0 Do Q".format(java.util.Locale.ROOT, w, h, x, y)
            obj {
                text("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pw $ph] ")
                text("/Resources << /XObject << /Im0 ${id + 2} 0 R >> >> /Contents ${id + 1} 0 R >>")
            }
            obj { text("<< /Length ${content.length} >>\nstream\n$content\nendstream") }
            obj {
                val colorSpace = if (jpeg.components == 1) "/DeviceGray" else "/DeviceRGB"
                text("<< /Type /XObject /Subtype /Image /Width ${jpeg.width} /Height ${jpeg.height} ")
                text("/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.bytes.size} >>\nstream\n")
                out.write(jpeg.bytes)
                text("\nendstream")
            }
        }
        val xref = out.size()
        text("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { text("%010d 00000 n \n".format(it)) }
        text("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }
}
