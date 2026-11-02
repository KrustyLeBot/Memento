package app.memento.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Shrinks the scanner's page photos before they go into the PDF: scans are mostly text, so a
 * ~1600 px page at JPEG quality 50 stays readable while keeping each page around 100-200 KB,
 * which keeps the Proton Drive sync fast.
 */
object ScanImages {
    private const val MAX_SIDE = 1600
    private const val QUALITY = 50

    fun compress(context: Context, uri: Uri): ScanPdf.Jpeg {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        require(longest > 0) { "Page illisible" }
        var sample = 1
        while (longest / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Page illisible")
        val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val page = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
                .also { decoded.recycle() }
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        page.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        page.recycle()
        return ScanPdf.readJpeg(out.toByteArray())
    }
}
