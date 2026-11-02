package app.scriptorium.map

import kotlin.math.cos
import kotlin.math.max

/** A lon/lat box: west, south, east, north in degrees. */
data class Bbox(val w: Double, val s: Double, val e: Double, val n: Double) {
    fun contains(lon: Double, lat: Double) = lon in w..e && lat in s..n

    fun covers(o: Bbox) = o.w >= w && o.e <= e && o.s >= s && o.n <= n

    fun intersects(o: Bbox) = o.w <= e && o.e >= w && o.s <= n && o.n >= s

    /** East-west side in metres, measured at the centre latitude. */
    val widthM: Double get() = (e - w) * MapSquare.M_PER_DEG_LON * cos(Math.toRadians((s + n) / 2))

    val heightM: Double get() = (n - s) * MapSquare.M_PER_DEG_LAT

    fun toList() = listOf(w, s, e, n)

    companion object {
        fun of(lons: DoubleArray, lats: DoubleArray): Bbox? {
            if (lons.isEmpty()) return null
            return Bbox(lons.min(), lats.min(), lons.max(), lats.max())
        }

        fun fromList(v: List<Double>?): Bbox? = if (v != null && v.size == 4) Bbox(v[0], v[1], v[2], v[3]) else null
    }
}

/**
 * The square the map shows in 3D and the offline packs cover around a route. The web page applies the
 * same rule, so both sides ask for the very same peaks bbox and tiles.
 */
object MapSquare {
    const val M_PER_DEG_LON = 111320.0
    const val M_PER_DEG_LAT = 110540.0
    const val MARGIN = 1.25
    const val MIN_HALF = 500.0

    fun of(w: Double, s: Double, e: Double, n: Double): Bbox {
        val latC = (s + n) / 2
        val lonC = (w + e) / 2
        val cosLat = cos(Math.toRadians(latC))
        val widthM = (e - w) * M_PER_DEG_LON * cosLat
        val heightM = (n - s) * M_PER_DEG_LAT
        val half = max(max(widthM, heightM) / 2 * MARGIN, MIN_HALF)
        return centered(lonC, latC, half)
    }

    fun of(b: Bbox) = of(b.w, b.s, b.e, b.n)

    /** A square of half side [halfM] metres around a point, without margin. */
    fun centered(lon: Double, lat: Double, halfM: Double): Bbox {
        val dLon = halfM / (M_PER_DEG_LON * cos(Math.toRadians(lat)))
        val dLat = halfM / M_PER_DEG_LAT
        return Bbox(lon - dLon, lat - dLat, lon + dLon, lat + dLat)
    }
}
