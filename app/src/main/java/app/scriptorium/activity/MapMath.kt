package app.scriptorium.activity

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** Web Mercator maths for the slippy-map tiles. World coordinates are in tile units at a zoom level. */
object MapMath {
    const val TILE = 256.0
    const val MAX_ZOOM = 18
    const val MIN_ZOOM = 2

    fun worldX(lon: Double, zoom: Double) = (lon + 180.0) / 360.0 * 2.0.pow(zoom)

    fun worldY(lat: Double, zoom: Double): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * 2.0.pow(zoom)
    }

    fun lon(x: Double, zoom: Double) = x / 2.0.pow(zoom) * 360.0 - 180.0

    fun lat(y: Double, zoom: Double): Double {
        val n = PI - 2 * PI * y / 2.0.pow(zoom)
        return Math.toDegrees(atan(sinh(n)))
    }

    /** The zoom (fractional) at which [bounds] fit a [widthPx] × [heightPx] view with a margin. */
    fun fitZoom(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, widthPx: Double, heightPx: Double): Double {
        val wTiles = (worldX(maxLon, 0.0) - worldX(minLon, 0.0)).coerceAtLeast(1e-9)
        val hTiles = (worldY(minLat, 0.0) - worldY(maxLat, 0.0)).coerceAtLeast(1e-9)
        val zx = ln(widthPx * 0.85 / TILE / wTiles) / ln(2.0)
        val zy = ln(heightPx * 0.85 / TILE / hTiles) / ln(2.0)
        return min(zx, zy).coerceIn(MIN_ZOOM.toDouble(), 17.0)
    }

    fun tileCount(zoom: Int) = 1 shl zoom

    fun wrapX(x: Int, zoom: Int): Int = floorMod(x, tileCount(zoom))

    private fun floorMod(a: Int, b: Int) = ((a % b) + b) % b

    fun floorInt(v: Double) = floor(v).toInt()
}
