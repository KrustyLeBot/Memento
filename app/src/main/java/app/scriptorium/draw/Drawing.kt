package app.scriptorium.draw

import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One pen stroke: opaque [rgb] (0xRRGGBB), [width] in drawing units, the points as two arrays. */
class Stroke(val rgb: Int, val width: Float, val xs: FloatArray, val ys: FloatArray) {
    val size: Int get() = xs.size

    /** Distance from (x, y) to the stroke's line (its width not included). */
    fun distanceTo(x: Float, y: Float): Float {
        if (size == 0) return Float.MAX_VALUE
        if (size == 1) return hypot(x - xs[0], y - ys[0])
        var best = Float.MAX_VALUE
        for (i in 1 until size) best = min(best, segment(x, y, xs[i - 1], ys[i - 1], xs[i], ys[i]))
        return best
    }

    private fun segment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}

/**
 * A sketch: strokes on an endless page. It is stored as a plain SVG (`drawings/<name>.svg`) that any viewer
 * opens; Scriptorium reads back its own paths to edit it again.
 */
class Drawing(val strokes: List<Stroke>) {
    val isEmpty: Boolean get() = strokes.isEmpty()

    /** minX, minY, maxX, maxY of the strokes (their width included), or null when empty. */
    fun bounds(): FloatArray? {
        if (strokes.isEmpty()) return null
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (s in strokes) {
            val r = s.width / 2
            for (i in 0 until s.size) {
                x0 = min(x0, s.xs[i] - r); x1 = max(x1, s.xs[i] + r)
                y0 = min(y0, s.ys[i] - r); y1 = max(y1, s.ys[i] + r)
            }
        }
        return floatArrayOf(x0, y0, x1, y1)
    }

    /** The topmost stroke within [radius] of the point, or -1. */
    fun strokeAt(x: Float, y: Float, radius: Float): Int {
        for (i in strokes.indices.reversed()) {
            if (strokes[i].distanceTo(x, y) <= radius + strokes[i].width / 2) return i
        }
        return -1
    }

    fun toSvg(): String {
        val b = bounds() ?: floatArrayOf(0f, 0f, 100f, 100f)
        val margin = 24f
        val x = b[0] - margin
        val y = b[1] - margin
        val w = b[2] - b[0] + 2 * margin
        val h = b[3] - b[1] + 2 * margin
        val sb = StringBuilder()
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" data-scriptorium=\"1\" viewBox=\"")
            .append(num(x)).append(' ').append(num(y)).append(' ').append(num(w)).append(' ').append(num(h))
            .append("\" width=\"").append(num(w)).append("\" height=\"").append(num(h)).append("\">\n")
        sb.append("<rect x=\"").append(num(x)).append("\" y=\"").append(num(y)).append("\" width=\"").append(num(w))
            .append("\" height=\"").append(num(h)).append("\" fill=\"#ffffff\"/>\n")
        for (s in strokes) {
            if (s.size == 0) continue
            sb.append("<path d=\"M").append(num(s.xs[0])).append(' ').append(num(s.ys[0]))
            if (s.size == 1) sb.append(" L").append(num(s.xs[0])).append(' ').append(num(s.ys[0]))
            for (i in 1 until s.size) sb.append(" L").append(num(s.xs[i])).append(' ').append(num(s.ys[i]))
            sb.append("\" fill=\"none\" stroke=\"#").append(String.format(Locale.ROOT, "%06x", s.rgb and 0xFFFFFF))
                .append("\" stroke-width=\"").append(num(s.width))
                .append("\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n")
        }
        sb.append("</svg>\n")
        return sb.toString()
    }

    companion object {
        private val PATH = Regex("<path\\b([^>]*?)/?>")
        private val D = Regex("\\bd=\"([^\"]*)\"")
        private val STROKE = Regex("\\bstroke=\"#([0-9a-fA-F]{6})\"")
        private val WIDTH = Regex("\\bstroke-width=\"([0-9.]+)\"")
        private val NUMBER = Regex("-?[0-9]+(?:\\.[0-9]+)?")

        fun looksLikeDrawing(svg: String) = svg.contains("data-scriptorium") || svg.contains("data-griffonote")

        /** Reads what [toSvg] wrote; anything else in the file is ignored. */
        fun parse(svg: String): Drawing {
            val strokes = ArrayList<Stroke>()
            for (m in PATH.findAll(svg)) {
                val attrs = m.groupValues[1]
                val d = D.find(attrs)?.groupValues?.get(1) ?: continue
                val rgb = STROKE.find(attrs)?.groupValues?.get(1)?.toInt(16) ?: continue
                val width = WIDTH.find(attrs)?.groupValues?.get(1)?.toFloatOrNull() ?: 3f
                val nums = NUMBER.findAll(d).map { it.value.toFloat() }.toList()
                val n = nums.size / 2
                if (n == 0) continue
                var xs = FloatArray(n) { nums[it * 2] }
                var ys = FloatArray(n) { nums[it * 2 + 1] }
                if (n == 2 && xs[0] == xs[1] && ys[0] == ys[1]) { // a dot
                    xs = floatArrayOf(xs[0]); ys = floatArrayOf(ys[0])
                }
                strokes.add(Stroke(rgb, width, xs, ys))
            }
            return Drawing(strokes)
        }

        /** Where a new drawing goes: `drawings/dessin-<id6>.svg` (the caller checks it is free). */
        fun newPath(generateId: () -> String) = "drawings/dessin-${generateId()}.svg"

        private fun num(v: Float) = String.format(Locale.ROOT, "%.1f", v)
    }
}
