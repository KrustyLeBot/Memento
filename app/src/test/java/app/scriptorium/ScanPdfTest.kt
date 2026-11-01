package app.scriptorium

import app.scriptorium.scan.PageFormat
import app.scriptorium.scan.ScanFormat
import app.scriptorium.scan.ScanPdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPdfTest {
    /** Smallest JPEG-looking file the writer needs: SOI, then a SOF0 frame header. */
    private fun fakeJpeg(width: Int, height: Int, components: Int = 3) = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xE0.toByte(), 0, 4, 0, 0, // an APP0 segment to skip
        0xFF.toByte(), 0xC0.toByte(), 0, 11, 8,
        (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(), components.toByte(),
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0xFF.toByte(), 0xD9.toByte(),
    )

    @Test fun readsJpegSize() {
        val j = ScanPdf.readJpeg(fakeJpeg(1131, 1600, 1))
        assertEquals(1131, j.width)
        assertEquals(1600, j.height)
        assertEquals(1, j.components)
    }

    @Test fun guessesA4AndLetterFromThePageShape() {
        assertEquals(PageFormat.A4, ScanPdf.guessFormat(1131, 1600)) // 1.415
        assertEquals(PageFormat.LETTER, ScanPdf.guessFormat(1236, 1600)) // 1.294
        assertEquals(PageFormat.A4, ScanPdf.guessFormat(1600, 1131)) // landscape A4
        assertEquals(PageFormat.A4, ScanPdf.guessFormat(800, 1600)) // a receipt: neither, A4
    }

    @Test fun writesAValidPdfWithOnePagePerImage() {
        val pdf = String(
            ScanPdf.build(listOf(ScanPdf.readJpeg(fakeJpeg(1131, 1600)), ScanPdf.readJpeg(fakeJpeg(1600, 1236))), ScanFormat.AUTO),
            Charsets.ISO_8859_1,
        )
        assertTrue(pdf.startsWith("%PDF-1.4"))
        assertTrue(pdf.contains("/Count 2"))
        assertTrue(pdf.contains("/MediaBox [0 0 595 842]")) // A4 portrait
        assertTrue(pdf.contains("/MediaBox [0 0 792 612]")) // US Letter landscape
        assertTrue(pdf.trimEnd().endsWith("%%EOF"))
        // Every xref offset must point at the start of its object.
        val xref = pdf.substringAfter("xref\n").lines().drop(2).takeWhile { it.endsWith(" n ") }
        xref.forEachIndexed { i, line ->
            val offset = line.substring(0, 10).toInt()
            assertTrue(pdf.startsWith("${i + 1} 0 obj", offset))
        }
    }

    @Test fun fixedFormatWins() {
        val pdf = String(ScanPdf.build(listOf(ScanPdf.readJpeg(fakeJpeg(1131, 1600))), ScanFormat.LETTER), Charsets.ISO_8859_1)
        assertTrue(pdf.contains("/MediaBox [0 0 612 792]"))
    }
}
