package app.memento.map

import app.memento.activity.Geo
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

    /** The legs (see [scan]) of some segments and the nearest of them. */
    private class Scan(val legs: List<Hit>, val nearest: Hit?)

    fun update(lat: Double, lon: Double): RouteStatus {
        if (route.size < 2) return status
        // one pass over the whole route at most per fix: it runs on the main thread, once a second, on routes of 200 000 points
        var whole: Scan? = null
        fun whole(): Scan = whole ?: scan(0, route.size - 2, lat, lon).also { whole = it }
        val hit = if (lastSeg < 0) firstMatch(whole()) else windowMatch(lat, lon) ?: rejoin(whole())
        if (hit == null || hit.dist > OFF_ROUTE_M) {
            offFixes++
            val off = status.offRoute || offFixes >= OFF_ROUTE_FIXES
            status = status.copy(offRoute = off, distanceToRoute = hit?.dist ?: whole().nearest?.dist ?: Double.NaN)
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
    private fun firstMatch(all: Scan): Hit? = all.legs.minByOrNull { it.along } ?: all.nearest

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
        val window = scan(from, to, lat, lon)
        val best = window.nearest ?: return null
        if (best.dist > OFF_ROUTE_M) return null
        return window.legs.filter { it.dist <= best.dist + AMBIGUITY_M }.minByOrNull { orderCost(it.along) } ?: best
    }

    /**
     * Segments [from]..[to] in one pass that allocates only for what it returns: where each leg passes closest
     * (local minima of the distance, within reach) and the first nearest segment.
     */
    private fun scan(from: Int, to: Int, lat: Double, lon: Double): Scan {
        val k = cos(Math.toRadians(lat)) * MapSquare.M_PER_DEG_LON
        val legs = ArrayList<Hit>()
        var best = -1
        var bestDist = 0.0
        // a segment whose box is out of reach and farther than the nearest so far can be neither a leg nor the nearest:
        // its box's distance stands in for its own (a neighbour within reach compares the same against either)
        var chunk = -1
        var chunkBound = 0.0
        fun dist(i: Int): Double {
            if (best >= 0) {
                // the same test for a whole run of segments first: far from the route, it is all a fix costs
                val c = i / CHUNK
                if (c != chunk) { chunk = c; chunkBound = chunkDistance(c, lat, lon, k) }
                if (chunkBound > OFF_ROUTE_M + 1e-6 && chunkBound > bestDist + 1e-6) return chunkBound
                val bound = boxDistance(i, lat, lon, k)
                if (bound > OFF_ROUTE_M + 1e-6 && bound > bestDist + 1e-6) return bound
            }
            return distance(i, lat, lon, k)
        }
        var prev = 0.0
        var cur = dist(from)
        for (i in from..to) {
            val next = if (i < to) dist(i + 1) else 0.0
            if (best < 0 || cur < bestDist) { best = i; bestDist = cur }
            if (cur <= OFF_ROUTE_M && (i == from || cur <= prev) && (i == to || cur <= next)) legs.add(project(i, lat, lon, k))
            prev = cur
            cur = next
        }
        return Scan(legs, if (best >= 0) project(best, lat, lon, k) else null)
    }

    private fun orderCost(along: Double) = if (along >= lastAlong) along - lastAlong else (lastAlong - along) * BACKWARD_COST

    /** Back on the route after a detour or a pause: the first reachable segment ahead, else the earliest one. */
    private fun rejoin(all: Scan): Hit? {
        val close = all.legs
        if (close.isEmpty()) return null
        return close.filter { it.along >= lastAlong - BACK_WINDOW_M }.minByOrNull { it.along } ?: close.minByOrNull { it.along }
    }

    /** Position on segment [i] (0..1) in a local flat frame (metres), fine at segment scale; [k]: metres per degree of longitude. */
    private fun param(i: Int, lat: Double, lon: Double, k: Double): Double {
        val r = route
        val ax = (r.lons[i] - lon) * k
        val ay = (r.lats[i] - lat) * MapSquare.M_PER_DEG_LAT
        val dx = (r.lons[i + 1] - lon) * k - ax
        val dy = (r.lats[i + 1] - lat) * MapSquare.M_PER_DEG_LAT - ay
        val len2 = dx * dx + dy * dy
        return if (len2 < 1e-9) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
    }

    /** Bounding boxes (min lat, max lat, min lon, max lon) of the segments [CHUNK] by [CHUNK]. */
    private val chunks: DoubleArray = run {
        val segs = (route.size - 1).coerceAtLeast(0)
        val count = (segs + CHUNK - 1) / CHUNK
        DoubleArray(count * 4).also { b ->
            for (c in 0 until count) {
                var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
                var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
                for (p in c * CHUNK..minOf((c + 1) * CHUNK, route.size - 1)) {
                    minLat = minOf(minLat, route.lats[p]); maxLat = maxOf(maxLat, route.lats[p])
                    minLon = minOf(minLon, route.lons[p]); maxLon = maxOf(maxLon, route.lons[p])
                }
                b[c * 4] = minLat; b[c * 4 + 1] = maxLat; b[c * 4 + 2] = minLon; b[c * 4 + 3] = maxLon
            }
        }
    }

    /** Distance to the box of chunk [c]: never more than the distance to any of its segments. */
    private fun chunkDistance(c: Int, lat: Double, lon: Double, k: Double): Double {
        val b = chunks
        val gx = if (b[c * 4 + 2] > lon) (b[c * 4 + 2] - lon) * k else if (b[c * 4 + 3] < lon) (lon - b[c * 4 + 3]) * k else 0.0
        val gy = if (b[c * 4] > lat) (b[c * 4] - lat) * MapSquare.M_PER_DEG_LAT else if (b[c * 4 + 1] < lat) (lat - b[c * 4 + 1]) * MapSquare.M_PER_DEG_LAT else 0.0
        return maxOf(gx, gy)
    }

    /** Distance to the bounding box of segment [i]: never more than the distance to the segment itself. */
    private fun boxDistance(i: Int, lat: Double, lon: Double, k: Double): Double {
        val r = route
        val ax = (r.lons[i] - lon) * k
        val bx = (r.lons[i + 1] - lon) * k
        val ay = (r.lats[i] - lat) * MapSquare.M_PER_DEG_LAT
        val by = (r.lats[i + 1] - lat) * MapSquare.M_PER_DEG_LAT
        val gx = if (ax > 0 && bx > 0) minOf(ax, bx) else if (ax < 0 && bx < 0) -maxOf(ax, bx) else 0.0
        val gy = if (ay > 0 && by > 0) minOf(ay, by) else if (ay < 0 && by < 0) -maxOf(ay, by) else 0.0
        return maxOf(gx, gy)
    }

    private fun distance(i: Int, lat: Double, lon: Double, k: Double): Double {
        val r = route
        val t = param(i, lat, lon, k)
        val ax = (r.lons[i] - lon) * k
        val ay = (r.lats[i] - lat) * MapSquare.M_PER_DEG_LAT
        val px = ax + ((r.lons[i + 1] - lon) * k - ax) * t
        val py = ay + ((r.lats[i + 1] - lat) * MapSquare.M_PER_DEG_LAT - ay) * t
        return kotlin.math.sqrt(px * px + py * py)
    }

    private fun project(i: Int, lat: Double, lon: Double, k: Double): Hit {
        val segLen = route.cumDist[i + 1] - route.cumDist[i]
        val t = param(i, lat, lon, k)
        return Hit(i, t, distance(i, lat, lon, k), route.cumDist[i] + segLen * t)
    }

    companion object {
        const val OFF_ROUTE_M = 50.0
        const val OFF_ROUTE_FIXES = 2
        const val BACK_WINDOW_M = 200.0
        const val FORWARD_WINDOW_M = 2000.0
        const val AMBIGUITY_M = 20.0
        const val BACKWARD_COST = 3.0
        private const val CHUNK = 64

        /** Estimated arrival in epoch ms at the moving average speed, or null when not moving yet. */
        fun eta(now: Long, remaining: Double, movingAvgSpeed: Double): Long? {
            if (movingAvgSpeed < 0.3 || remaining.isNaN()) return null
            return now + Math.round(remaining / movingAvgSpeed * 1000)
        }
    }
}
