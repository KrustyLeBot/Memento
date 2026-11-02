package app.memento.draw

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.max

/** A drawing as a bitmap (thumbnails in the note), white page included. */
object DrawingBitmap {
    fun render(d: Drawing, maxPx: Int): Bitmap? {
        val b = d.bounds() ?: return null
        val margin = 24f
        val w = b[2] - b[0] + 2 * margin
        val h = b[3] - b[1] + 2 * margin
        val scale = maxPx / max(w, h)
        val bmp = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFFFFFFFF.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (s in d.strokes) {
            if (s.size == 0) continue
            paint.color = 0xFF000000.toInt() or s.rgb
            paint.strokeWidth = s.width * scale
            val x0 = (s.xs[0] - b[0] + margin) * scale
            val y0 = (s.ys[0] - b[1] + margin) * scale
            if (s.size == 1) {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x0, y0, s.width * scale / 2, paint)
                paint.style = Paint.Style.STROKE
                continue
            }
            val path = Path().apply { moveTo(x0, y0) }
            for (i in 1 until s.size) path.lineTo((s.xs[i] - b[0] + margin) * scale, (s.ys[i] - b[1] + margin) * scale)
            canvas.drawPath(path, paint)
        }
        return bmp
    }
}
