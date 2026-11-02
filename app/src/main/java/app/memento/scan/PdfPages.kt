package app.memento.scan

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders the pages of a PDF with Android's own renderer. It handles one page at a time and must not be
 * closed while a page is open, so rendering and closing share a lock.
 */
class PdfPages(fd: ParcelFileDescriptor) : AutoCloseable {
    private val renderer = PdfRenderer(fd)
    private val lock = Any()
    private var closed = false

    /** Width / height of every page, in PDF points, read once so the layout does not jump while rendering. */
    val sizes: List<Pair<Int, Int>> = (0 until renderer.pageCount).map { i ->
        renderer.openPage(i).use { it.width to it.height }
    }

    /** The page as a bitmap [widthPx] wide, or null once the document is closed. */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (closed) return@withContext null
            renderer.openPage(index).use { page ->
                val height = (widthPx.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    bitmap.eraseColor(Color.WHITE) // PDF pages are transparent where nothing is drawn
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true
            renderer.close()
        }
    }
}
