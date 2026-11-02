package app.memento.map

/** JSON arguments of the map page's `SMap` calls. Built off the main thread: a long activity is a big string. */
object MapPayload {
    /** More points than this add nothing visible and only slow the page down. */
    const val MAX_POINTS = 5000

    fun coords(lons: DoubleArray, lats: DoubleArray, idx: IntArray = IntArray(lons.size) { it }): String = buildString(idx.size * 24) {
        append('[')
        idx.forEachIndexed { k, i ->
            if (k > 0) append(',')
            append('[').append(Json.num(lons[i])).append(',').append(Json.num(lats[i])).append(']')
        }
        append(']')
    }

    fun speeds(v: DoubleArray, idx: IntArray = IntArray(v.size) { it }): String =
        idx.joinToString(",", "[", "]") { Json.num(v[it], 2) }

    /** At most [max] evenly spaced indices of [n], first and last kept. */
    fun thin(n: Int, max: Int = MAX_POINTS): IntArray {
        if (n <= max) return IntArray(n) { it }
        return IntArray(max) { (it.toLong() * (n - 1) / (max - 1)).toInt() }
    }

    /** `setTrack({coords, speeds|null, avg|null})`, speed in m/s. */
    fun track(lons: DoubleArray, lats: DoubleArray, speeds: DoubleArray?, avg: Double?): String {
        val idx = thin(lons.size)
        return "{\"coords\":" + coords(lons, lats, idx) +
            ",\"speeds\":" + (speeds?.let { speeds(it, idx) } ?: "null") +
            ",\"avg\":" + (avg?.let { Json.num(it, 2) } ?: "null") + "}"
    }

    /** `setLines([{coords, color}])`. */
    fun lines(lines: List<Triple<DoubleArray, DoubleArray, String>>): String =
        lines.joinToString(",", "[", "]") { (lons, lats, color) -> "{\"coords\":" + coords(lons, lats) + ",\"color\":" + Json.quote(color) + "}" }

    /** `setLive({coords, speeds})`. */
    fun live(lons: DoubleArray, lats: DoubleArray, speeds: DoubleArray): String =
        "{\"coords\":" + coords(lons, lats) + ",\"speeds\":" + speeds(speeds) + "}"

    /** `setRoute(coords)`. */
    fun route(r: Route): String = coords(r.lons, r.lats, thin(r.size, 8000))

    fun bbox(b: Bbox) = b.toList().joinToString(",", "[", "]") { Json.num(it, 7) }

    fun color(argb: Int) = String.format("#%06x", argb and 0xFFFFFF)
}
