package app.scriptorium.map

import app.scriptorium.activity.Geo
import kotlin.math.cos

/** A route to follow: positions and, when the file had them, altitudes (all or none). */
class Route(val name: String, val lats: DoubleArray, val lons: DoubleArray, val eles: DoubleArray?) {
    init {
        require(lats.size == lons.size) { "lats/lons" }
        require(eles == null || eles.size == lats.size) { "eles" }
    }

    val size: Int get() = lats.size

    /** Metres from the start to each point. */
    val cumDist: DoubleArray = DoubleArray(size).also { c ->
        for (i in 1 until size) c[i] = c[i - 1] + Geo.distance(lats[i - 1], lons[i - 1], lats[i], lons[i])
    }

    val length: Double get() = if (size == 0) 0.0 else cumDist[size - 1]

    /** Climb and descent from the start to each point, with a 3 m dead band so GPS-like noise does not add up. */
    val cumUp: DoubleArray?
    val cumDown: DoubleArray?

    init {
        if (eles == null || size == 0) {
            cumUp = null; cumDown = null
        } else {
            val up = DoubleArray(size)
            val down = DoubleArray(size)
            var ref = eles[0]
            for (i in 1 until size) {
                up[i] = up[i - 1]; down[i] = down[i - 1]
                val d = eles[i] - ref
                if (d >= HYSTERESIS) { up[i] += d; ref = eles[i] } else if (-d >= HYSTERESIS) { down[i] += -d; ref = eles[i] }
            }
            cumUp = up; cumDown = down
        }
    }

    val bbox: Bbox? get() = Bbox.of(lons, lats)

    companion object {
        const val HYSTERESIS = 3.0

        /**
         * Builds a route, dropping repeated points. Altitudes are kept when at least half of the points
         * have one (the gaps take the previous value); otherwise the route has none.
         */
        fun of(name: String, pts: List<Triple<Double, Double, Double?>>): Route {
            val clean = ArrayList<Triple<Double, Double, Double?>>(pts.size)
            for (p in pts) {
                val last = clean.lastOrNull()
                if (last == null || last.first != p.first || last.second != p.second) clean.add(p)
            }
            val withEle = clean.count { it.third != null }
            val eles = if (clean.isNotEmpty() && withEle * 2 >= clean.size) {
                var prev = clean.firstNotNullOfOrNull { it.third } ?: 0.0
                DoubleArray(clean.size) { i -> (clean[i].third ?: prev).also { prev = it } }
            } else null
            return Route(name, DoubleArray(clean.size) { clean[it].first }, DoubleArray(clean.size) { clean[it].second }, eles)
        }
    }
}

/** Where the user stands relative to the route. Distances in metres. */
data class RouteStatus(
    /** False until a first position fell within [RouteProgress.OFF_ROUTE_M] of the route. */
    val matched: Boolean,
    val offRoute: Boolean,
    /** Metres from the route (to the nearest point of the matched segment). */
    val distanceToRoute: Double,
    /** Metres along the route at the projected position. */
    val along: Double,
    val remaining: Double,
    /** Null when the route has no altitude. */
    val remainingUp: Double?,
    val remainingDown: Double?,
)

/**
 * Follows the user's position along a [Route]. The search runs forward from the last matched point within
 * a window, never over the whole route, so an out-and-back or a loop does not jump to the other leg.
 * Not thread-safe: one caller (the recorder) feeds it.
 */
class RouteProgress(val route: Route) {
    private var lastSeg = -1
    private var lastAlong = 0.0
    /** Fixes in a row beyond [OFF_ROUTE_M]: one alone is often GPS noise, "Hors trace" waits for a second. */
    private var offFixes = 0
    var status: RouteStatus = RouteStatus(false, false, Double.NaN, 0.0, route.length, route.cumUp?.last(), route.cumDown?.last())
        private set

    private class Hit(val seg: Int, val t: Double, val dist: Double, val along: Double)

    fun update(lat: Double, lon: Double): RouteStatus {
        if (route.size < 2) return status
        val hit = if (lastSeg < 0) firstMatch(lat, lon) else windowMatch(lat, lon) ?: rejoin(lat, lon)
        if (hit == null || hit.dist > OFF_ROUTE_M) {
            offFixes++
            val off = status.offRoute || offFixes >= OFF_ROUTE_FIXES
            status = status.copy(offRoute = off, distanceToRoute = hit?.dist ?: nearestDistance(lat, lon))
            return status
        }
        offFixes = 0
        lastSeg = hit.seg
        lastAlong = hit.along
        status = statusAt(hit)
        return status
    }

    private fun statusAt(h: Hit): RouteStatus {
        val r = route
        val up = r.cumUp?.let { c -> c.last() - (c[h.seg] + (c[h.seg + 1] - c[h.seg]) * h.t) }
        val down = r.cumDown?.let { c -> c.last() - (c[h.seg] + (c[h.seg + 1] - c[h.seg]) * h.t) }
        return RouteStatus(true, false, h.dist, h.along, (r.length - h.along).coerceAtLeast(0.0), up?.coerceAtLeast(0.0), down?.coerceAtLeast(0.0))
    }

    /** The first position: the earliest point of the route within reach, so a loop starts at its start. */
    private fun firstMatch(lat: Double, lon: Double): Hit? {
        val hits = (0 until route.size - 1).map { project(it, lat, lon) }
        return legs(hits).minByOrNull { it.along } ?: hits.minByOrNull { it.dist }
    }

    /**
     * Segments from [BACK_WINDOW_M] behind to [FORWARD_WINDOW_M] ahead of the last match. Each leg of the
     * route passing by gives one candidate (a local minimum of the distance); among the legs close to the
     * best one, the nearest in route order wins, and going backwards costs more than going forwards: at
     * the turn of an out-and-back the way back is picked, not the way out.
     */
    private fun windowMatch(lat: Double, lon: Double): Hit? {
        val c = route.cumDist
        var from = lastSeg
        while (from > 0 && c[from] > lastAlong - BACK_WINDOW_M) from--
        var to = lastSeg
        while (to < route.size - 2 && c[to + 1] < lastAlong + FORWARD_WINDOW_M) to++
        val hits = (from..to).map { project(it, lat, lon) }
        val best = hits.minByOrNull { it.dist } ?: return null
        if (best.dist > OFF_ROUTE_M) return null
        return legs(hits).filter { it.dist <= best.dist + AMBIGUITY_M }.minByOrNull { orderCost(it.along) } ?: best
    }

    /** Where each leg passes closest: local minima of the distance, within reach. */
    private fun legs(hits: List<Hit>): List<Hit> = hits.filterIndexed { k, h ->
        h.dist <= OFF_ROUTE_M && (k == 0 || h.dist <= hits[k - 1].dist) && (k == hits.lastIndex || h.dist <= hits[k + 1].dist)
    }

    private fun orderCost(along: Double) = if (along >= lastAlong) along - lastAlong else (lastAlong - along) * BACKWARD_COST

    /** Back on the route after a detour or a pause: the first reachable segment ahead, else the earliest one. */
    private fun rejoin(lat: Double, lon: Double): Hit? {
        val close = legs((0 until route.size - 1).map { project(it, lat, lon) })
        if (close.isEmpty()) return null
        return close.filter { it.along >= lastAlong - BACK_WINDOW_M }.minByOrNull { it.along } ?: close.minByOrNull { it.along }
    }

    private fun nearestDistance(lat: Double, lon: Double): Double =
        (0 until route.size - 1).minOfOrNull { project(it, lat, lon).dist } ?: Double.NaN

    /** Projection on segment [i] in a local flat frame (metres), fine at segment scale. */
    private fun project(i: Int, lat: Double, lon: Double): Hit {
        val r = route
        val k = cos(Math.toRadians(lat)) * MapSquare.M_PER_DEG_LON
        val ax = (r.lons[i] - lon) * k
        val ay = (r.lats[i] - lat) * MapSquare.M_PER_DEG_LAT
        val bx = (r.lons[i + 1] - lon) * k
        val by = (r.lats[i + 1] - lat) * MapSquare.M_PER_DEG_LAT
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 1e-9) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val px = ax + dx * t
        val py = ay + dy * t
        val dist = kotlin.math.sqrt(px * px + py * py)
        val segLen = r.cumDist[i + 1] - r.cumDist[i]
        return Hit(i, t, dist, r.cumDist[i] + segLen * t)
    }

    companion object {
        const val OFF_ROUTE_M = 50.0
        const val OFF_ROUTE_FIXES = 2
        const val BACK_WINDOW_M = 200.0
        const val FORWARD_WINDOW_M = 2000.0
        const val AMBIGUITY_M = 20.0
        const val BACKWARD_COST = 3.0

        /** Estimated arrival in epoch ms at the moving average speed, or null when not moving yet. */
        fun eta(now: Long, remaining: Double, movingAvgSpeed: Double): Long? {
            if (movingAvgSpeed < 0.3 || remaining.isNaN()) return null
            return now + Math.round(remaining / movingAvgSpeed * 1000)
        }
    }
}
