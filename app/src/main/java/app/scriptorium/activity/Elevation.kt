package app.scriptorium.activity

/**
 * Altitude for a track whose file has none (some watches record positions only): looked up in a terrain
 * model (SRTM 30 m, through the public OpenTopoData service) at points about 30 m apart, then
 * interpolated along the track. The result is kept in a small `.alt` file next to the track, so the
 * original FIT/GPX stays untouched and nothing is asked twice.
 *
 * Only positions leave the phone, and only when the user asks for it.
 */
object Elevation {
    const val BASE = "https://api.opentopodata.org/v1/srtm30m?locations="
    const val PER_REQUEST = 100
    private const val STEP = 30.0
    private const val MAX_SAMPLES = 1500

    class ElevationException(message: String) : Exception(message)

    /** One point about every 30 m (more spaced on very long tracks: 1500 at most). */
    fun pick(points: List<TrackPoint>): List<TrackPoint> {
        if (points.isEmpty()) return emptyList()
        var total = 0.0
        for (i in 1 until points.size) total += Geo.distance(points[i - 1], points[i])
        val step = maxOf(STEP, total / MAX_SAMPLES)
        val out = ArrayList<TrackPoint>()
        var last = points.first()
        out.add(last)
        for (p in points) {
            if (Geo.distance(last, p) >= step) { out.add(p); last = p }
        }
        if (out.last() !== points.last()) out.add(points.last())
        return out
    }

    /**
     * Asks for the altitude of [samples]. [get] fetches a URL and returns its body; [pause] is called
     * between two requests (the service allows one per second). Returns (time, altitude) pairs.
     */
    fun query(samples: List<TrackPoint>, get: (String) -> String, pause: () -> Unit, progress: (Int, Int) -> Unit = { _, _ -> }): List<Pair<Long, Double>> {
        val out = ArrayList<Pair<Long, Double>>()
        val chunks = samples.chunked(PER_REQUEST)
        for ((n, chunk) in chunks.withIndex()) {
            if (n > 0) pause()
            progress(n, chunks.size)
            val url = BASE + chunk.joinToString("|") { String.format(java.util.Locale.ROOT, "%.5f,%.5f", it.lat, it.lon) }
            val body = get(url)
            if (!body.contains("\"OK\"")) throw ElevationException("Service d'altitude : réponse inattendue")
            val values = ELEVATION.findAll(body).map { it.groupValues[1] }.toList()
            if (values.size != chunk.size) throw ElevationException("Service d'altitude : réponse incomplète")
            for ((i, v) in values.withIndex()) v.toDoubleOrNull()?.let { out.add(chunk[i].time to it) }
        }
        if (out.size < 2) throw ElevationException("Pas d'altitude connue pour cette zone")
        return out
    }

    private val ELEVATION = Regex("\"elevation\"\\s*:\\s*(null|-?[0-9]+(?:\\.[0-9]+)?)")

    fun encode(alts: List<Pair<Long, Double>>): String =
        alts.joinToString("\n", postfix = "\n") { (t, a) -> "$t," + String.format(java.util.Locale.ROOT, "%.1f", a) }

    fun parse(text: String): List<Pair<Long, Double>> = text.lineSequence().mapNotNull { line ->
        val f = line.split(',')
        val t = f.getOrNull(0)?.toLongOrNull()
        val a = f.getOrNull(1)?.toDoubleOrNull()
        if (t != null && a != null) t to a else null
    }.sortedBy { it.first }.toList()

    /** The track with [alts] interpolated (by time) onto every point that has no altitude. */
    fun apply(track: Track, alts: List<Pair<Long, Double>>): Track {
        if (alts.isEmpty()) return track
        val times = LongArray(alts.size) { alts[it].first }
        val points = track.points.map { p ->
            if (p.alt != null) return@map p
            var lo = java.util.Arrays.binarySearch(times, p.time)
            val alt = if (lo >= 0) alts[lo].second else {
                lo = -lo - 2 // index of the last sample before p
                when {
                    lo < 0 -> alts.first().second
                    lo >= alts.size - 1 -> alts.last().second
                    else -> {
                        val (t0, a0) = alts[lo]
                        val (t1, a1) = alts[lo + 1]
                        a0 + (a1 - a0) * (p.time - t0).toDouble() / (t1 - t0).coerceAtLeast(1)
                    }
                }
            }
            p.copy(alt = alt)
        }
        return track.copy(points = points)
    }

    /**
     * Corrects a track that has an altitude (the barometer's) with the terrain model: the gap between both,
     * smoothed over ±5 minutes, follows the barometer's slow drift (weather) without taking the terrain
     * model's local errors. Returns the track with the corrected altitude.
     */
    fun refine(track: Track, dem: List<Pair<Long, Double>>): Track {
        val byTime = HashMap<Long, Double>()
        for (p in track.points) p.alt?.let { byTime[p.time] = it }
        val diffs = dem.mapNotNull { (t, a) -> byTime[t]?.let { t to a - it } }.sortedBy { it.first }
        if (diffs.size < 2) return track
        val window = 300_000L
        val smooth = diffs.map { (t, _) ->
            val near = diffs.filter { kotlin.math.abs(it.first - t) <= window }.map { it.second }.sorted()
            t to near[near.size / 2]
        }
        val times = LongArray(smooth.size) { smooth[it].first }
        val points = track.points.map { p ->
            val alt = p.alt ?: return@map p
            var i = java.util.Arrays.binarySearch(times, p.time)
            val corr = if (i >= 0) smooth[i].second else {
                i = -i - 2
                when {
                    i < 0 -> smooth.first().second
                    i >= smooth.size - 1 -> smooth.last().second
                    else -> {
                        val (t0, c0) = smooth[i]
                        val (t1, c1) = smooth[i + 1]
                        c0 + (c1 - c0) * (p.time - t0).toDouble() / (t1 - t0).coerceAtLeast(1)
                    }
                }
            }
            p.copy(alt = alt + corr)
        }
        return track.copy(points = points)
    }

    fun missing(track: Track) = track.points.size >= 2 && track.points.none { it.alt != null }
}
