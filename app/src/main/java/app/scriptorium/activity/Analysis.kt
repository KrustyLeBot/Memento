package app.scriptorium.activity

import kotlin.math.max

/** One cleaned, smoothed point of an activity. */
data class Sample(
    val time: Long,
    val lat: Double,
    val lon: Double,
    /** Metres since the start, stops excluded. */
    val dist: Double,
    /** Smoothed altitude. */
    val alt: Double?,
    /** Smoothed ground speed, m/s. */
    val speed: Double,
    val hr: Int?,
    /** Part of a ski descent. */
    val down: Boolean,
    /** Part of a ski climb (lift, uphill). */
    val up: Boolean = false,
    /** Inside a stop (manual pause excluded: those are not samples at all). */
    val stopped: Boolean,
)

data class Stop(val start: Long, val end: Long, val lat: Double, val lon: Double, val manual: Boolean) {
    val durationMs: Long get() = end - start
}

data class Run(val first: Int, val last: Int, val distance: Double, val drop: Double, val maxSpeed: Double, val durationMs: Long) {
    val avgSpeed: Double get() = if (durationMs > 0) distance / (durationMs / 1000.0) else 0.0
}

data class Climb(val first: Int, val last: Int, val distance: Double, val gain: Double, val durationMs: Long)

data class Stats(
    val elapsedMs: Long,
    val movingMs: Long,
    val distance: Double,
    val ascent: Double,
    val descent: Double,
    val maxSpeed: Double,
    val minAlt: Double?,
    val maxAlt: Double?,
    val hrAvg: Int?,
    val hrMax: Int?,
    val runs: Int,
    val runDrop: Double,
    val runMaxSpeed: Double,
    val runAvgSpeed: Double,
    val runLongest: Double,
    val climbs: Int = 0,
    val climbGain: Double = 0.0,
) {
    val avgSpeed: Double get() = if (movingMs > 0) distance / (movingMs / 1000.0) else 0.0
}

data class Quality(
    val points: Int,
    val dropped: Int,
    val hasAltitude: Boolean,
    val hasHr: Boolean,
    val medianIntervalSec: Double,
    val manufacturer: Int?,
)

data class Analysis(
    val samples: List<Sample>,
    val stops: List<Stop>,
    val pauses: List<Stop>,
    val runs: List<Run>,
    val climbs: List<Climb>,
    val stats: Stats,
    val quality: Quality,
)

object Analyzer {
    const val MAX_SPEED = 50.0 // m/s: faster than this between two fixes is a GPS jump
    const val STOP_RADIUS = 8.0 // m: 1 km/h for 30 s
    const val STOP_MIN_MS = 30_000L
    const val ALT_WINDOW_MS = 10_000L
    const val SPEED_WINDOW_MS = 5_000L
    const val CLIMB_THRESHOLD = 3.0 // m
    const val DOWN_RATE = -0.3 // m/s of altitude
    const val DOWN_MIN_SPEED = 3.0 // m/s
    const val DOWN_NO_ALT_SPEED = 4.2 // m/s: without altitude, faster than a lift
    const val RUN_MERGE_MS = 15_000L
    const val RUN_MIN_DROP = 20.0
    const val RUN_MIN_MS = 15_000L
    const val PAUSE_BRIDGE = 100.0 // m
    const val UP_RATE = 0.15 // m/s of altitude
    const val CLIMB_MERGE_MS = 40_000L
    const val CLIMB_MIN_GAIN = 30.0
    const val CLIMB_MIN_MS = 60_000L
    const val MAX_WINDOW_MS = 15_000L

    fun analyze(track: Track, sport: Sport): Analysis {
        val total = track.points.size
        val kept = clean(track)
        val n = kept.size
        val seg = IntArray(n)
        for (i in 1 until n) {
            val crossesPause = track.pauses.any { it.start < kept[i].time && it.end > kept[i - 1].time }
            seg[i] = seg[i - 1] + if (crossesPause) 1 else 0
        }

        // stops: nothing moved more than STOP_RADIUS for at least STOP_MIN_MS
        val stopIdx = BooleanArray(n)
        val stops = ArrayList<Stop>()
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && seg[j + 1] == seg[i] && Geo.distance(kept[i], kept[j + 1]) <= STOP_RADIUS) j++
            if (j > i && kept[j].time - kept[i].time >= STOP_MIN_MS) {
                for (k in i..j) stopIdx[k] = true
                stops.add(Stop(kept[i].time, kept[j].time, kept[i].lat, kept[i].lon, manual = false))
                i = j + 1
            } else {
                i++
            }
        }

        // cumulative distance and moving time
        val cum = DoubleArray(n)
        var moving = 0L
        for (k in 1 until n) {
            val inStop = stopIdx[k] && stopIdx[k - 1] && sameStop(stops, kept[k - 1].time, kept[k].time)
            cum[k] = cum[k - 1]
            if (seg[k] != seg[k - 1]) {
                // a pause (watch auto-pause, button): what was walked across it still counts, unless it is a jump
                val d = Geo.distance(kept[k - 1], kept[k])
                if (d <= PAUSE_BRIDGE) cum[k] += d
                continue
            }
            if (!inStop) {
                cum[k] += Geo.distance(kept[k - 1], kept[k])
                moving += kept[k].time - kept[k - 1].time
            }
        }

        val alt = smoothAltitude(kept)
        val speed = smoothSpeed(kept, seg, cum)
        val hasAlt = alt.any { it != null }

        // ski runs
        val down = BooleanArray(n)
        val runs = ArrayList<Run>()
        if (sport == Sport.SKI && n > 1) {
            val stepDown = BooleanArray(n)
            for (k in 1 until n) {
                if (seg[k] != seg[k - 1]) continue
                val dt = (kept[k].time - kept[k - 1].time) / 1000.0
                if (dt <= 0) continue
                stepDown[k] = if (hasAlt && alt[k] != null && alt[k - 1] != null) {
                    (alt[k]!! - alt[k - 1]!!) / dt <= DOWN_RATE && speed[k] >= DOWN_MIN_SPEED
                } else {
                    speed[k] >= DOWN_NO_ALT_SPEED
                }
            }
            var k = 1
            while (k < n) {
                if (!stepDown[k]) { k++; continue }
                var end = k
                var m = k + 1
                while (m < n && seg[m] == seg[k]) {
                    if (stepDown[m]) { end = m; m++; continue }
                    // a short flat part inside a descent does not end it
                    var q = m
                    while (q < n && !stepDown[q] && seg[q] == seg[k] && kept[q].time - kept[end].time <= RUN_MERGE_MS) q++
                    if (q < n && stepDown[q] && seg[q] == seg[k] && kept[q].time - kept[end].time <= RUN_MERGE_MS) { m = q; continue }
                    break
                }
                val first = k - 1
                val dur = kept[end].time - kept[first].time
                val drop = if (hasAlt && alt[first] != null && alt[end] != null) alt[first]!! - alt[end]!! else 0.0
                val ok = dur >= RUN_MIN_MS && (!hasAlt || drop >= RUN_MIN_DROP)
                if (ok) {
                    var vmax = 0.0
                    for (q in first..end) { down[q] = true; vmax = max(vmax, speed[q]) }
                    runs.add(Run(first, end, cum[end] - cum[first], max(drop, 0.0), vmax, dur))
                }
                k = end + 1
            }
        }

        // ski climbs: altitude going up for a while (lifts, uphill), short stops inside them allowed
        val up = BooleanArray(n)
        val climbs = ArrayList<Climb>()
        if (sport == Sport.SKI && hasAlt && n > 1) {
            val stepUp = BooleanArray(n)
            for (k in 1 until n) {
                if (seg[k] != seg[k - 1]) continue
                val dt = (kept[k].time - kept[k - 1].time) / 1000.0
                if (dt > 0 && alt[k] != null && alt[k - 1] != null) stepUp[k] = (alt[k]!! - alt[k - 1]!!) / dt >= UP_RATE
            }
            var k = 1
            while (k < n) {
                if (!stepUp[k]) { k++; continue }
                var end = k
                var m = k + 1
                while (m < n && seg[m] == seg[k]) {
                    if (stepUp[m]) { end = m; m++; continue }
                    var q = m
                    while (q < n && !stepUp[q] && seg[q] == seg[k] && kept[q].time - kept[end].time <= CLIMB_MERGE_MS) q++
                    if (q < n && stepUp[q] && seg[q] == seg[k] && kept[q].time - kept[end].time <= CLIMB_MERGE_MS) { m = q; continue }
                    break
                }
                val first = k - 1
                val gain = if (alt[first] != null && alt[end] != null) alt[end]!! - alt[first]!! else 0.0
                val dur = kept[end].time - kept[first].time
                if (gain >= CLIMB_MIN_GAIN && dur >= CLIMB_MIN_MS) {
                    for (q in first..end) if (!down[q]) up[q] = true
                    climbs.add(Climb(first, end, cum[end] - cum[first], gain, dur))
                }
                k = end + 1
            }
        }

        // climb with hysteresis
        var ascent = 0.0
        var descent = 0.0
        var ref: Double? = null
        for (a in alt) {
            if (a == null) continue
            val r = ref
            if (r == null) { ref = a; continue }
            if (a - r >= CLIMB_THRESHOLD) { ascent += a - r; ref = a }
            else if (r - a >= CLIMB_THRESHOLD) { descent += r - a; ref = a }
        }

        val samples = List(n) { k ->
            Sample(kept[k].time, kept[k].lat, kept[k].lon, cum[k], alt[k], speed[k], kept[k].hr, down[k], up[k], stopIdx[k])
        }
        val pauseStops = track.pauses.map { p ->
            val ref0 = kept.lastOrNull { it.time <= p.start } ?: kept.firstOrNull()
            Stop(p.start, p.end, ref0?.lat ?: 0.0, ref0?.lon ?: 0.0, manual = true)
        }.filter { p -> kept.isNotEmpty() && p.end > kept.first().time && p.start < kept.last().time }

        val hrs = kept.mapNotNull { it.hr }
        val altVals = alt.filterNotNull()
        val elapsed = if (n >= 2) kept.last().time - kept.first().time else (track.sessionElapsedMs ?: 0L)
        val intervals = (1 until n).map { (kept[it].time - kept[it - 1].time) / 1000.0 }.sorted()
        val stats = Stats(
            elapsedMs = elapsed,
            movingMs = if (n >= 2) moving else (track.sessionTimerMs ?: 0L),
            distance = deviceOrComputed(track.sessionDistance, if (n >= 2) cum.last() else 0.0),
            ascent = ascent, descent = descent,
            maxSpeed = maxWindowSpeed(kept, seg, cum),
            minAlt = altVals.minOrNull(), maxAlt = altVals.maxOrNull(),
            hrAvg = if (hrs.isNotEmpty()) Math.round(hrs.average()).toInt() else track.hrAvg,
            hrMax = hrs.maxOrNull() ?: track.hrMax,
            runs = runs.size,
            runDrop = runs.sumOf { it.drop },
            runMaxSpeed = runs.maxOfOrNull { it.maxSpeed } ?: 0.0,
            runAvgSpeed = if (runs.isNotEmpty()) runs.sumOf { it.distance } / (runs.sumOf { it.durationMs } / 1000.0) else 0.0,
            runLongest = runs.maxOfOrNull { it.distance } ?: 0.0,
            climbs = climbs.size,
            climbGain = climbs.sumOf { it.gain },
        )
        val quality = Quality(
            points = total, dropped = total - n, hasAltitude = hasAlt, hasHr = hrs.isNotEmpty() || track.hrAvg != null,
            medianIntervalSec = if (intervals.isEmpty()) 0.0 else intervals[intervals.size / 2],
            manufacturer = track.manufacturer,
        )
        return Analysis(samples, stops, pauseStops, runs, climbs, stats, quality)
    }

    /** The device's own total when the file has one and it is in the same range as ours (its sensors know more). */
    private fun deviceOrComputed(device: Double?, computed: Double): Double =
        if (device != null && device > 0 && (computed <= 0 || device in computed * 0.8..computed * 1.25)) device else computed

    /** Top speed held for MAX_WINDOW_MS: single GPS catch-up spikes (after a signal gap) do not count. */
    private fun maxWindowSpeed(p: List<TrackPoint>, seg: IntArray, cum: DoubleArray): Double {
        var best = 0.0
        var j = 0
        for (i in p.indices) {
            if (j < i) j = i
            while (j + 1 < p.size && seg[j + 1] == seg[i] && p[j].time - p[i].time < MAX_WINDOW_MS) j++
            val dt = (p[j].time - p[i].time) / 1000.0
            if (dt >= MAX_WINDOW_MS / 1000.0 * 0.8) best = max(best, (cum[j] - cum[i]) / dt)
        }
        return best
    }

    private fun sameStop(stops: List<Stop>, a: Long, b: Long) = stops.any { a >= it.start && b <= it.end }

    /** Sorted by time, no repeated instants, no impossible jumps, nothing recorded while paused. */
    fun clean(track: Track): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        for (p in track.points.sortedBy { it.time }) {
            if (p.lat !in -90.0..90.0 || p.lon !in -180.0..180.0) continue
            if (track.pauses.any { p.time > it.start && p.time < it.end }) continue
            val prev = out.lastOrNull()
            if (prev != null) {
                val dt = (p.time - prev.time) / 1000.0
                if (dt <= 0) continue
                if (Geo.distance(prev, p) / dt > MAX_SPEED) continue
            }
            out.add(p)
        }
        return out
    }

    private fun smoothAltitude(p: List<TrackPoint>): List<Double?> {
        val out = ArrayList<Double?>(p.size)
        var lo = 0
        var hi = 0
        for (i in p.indices) {
            if (p[i].alt == null) { out.add(null); continue }
            while (p[lo].time < p[i].time - ALT_WINDOW_MS) lo++
            if (hi < i) hi = i
            while (hi + 1 < p.size && p[hi + 1].time <= p[i].time + ALT_WINDOW_MS) hi++
            var sum = 0.0
            var cnt = 0
            for (k in lo..hi) p[k].alt?.let { sum += it; cnt++ }
            out.add(sum / cnt)
        }
        return out
    }

    private fun smoothSpeed(p: List<TrackPoint>, seg: IntArray, cum: DoubleArray): DoubleArray {
        val n = p.size
        val out = DoubleArray(n)
        for (i in 0 until n) {
            var lo = i
            while (lo > 0 && seg[lo - 1] == seg[i] && p[i].time - p[lo - 1].time <= SPEED_WINDOW_MS) lo--
            var hi = i
            while (hi + 1 < n && seg[hi + 1] == seg[i] && p[hi + 1].time - p[i].time <= SPEED_WINDOW_MS) hi++
            if (lo == hi) {
                if (i > 0 && seg[i - 1] == seg[i]) lo = i - 1 else if (i + 1 < n && seg[i + 1] == seg[i]) hi = i + 1
            }
            val dt = (p[hi].time - p[lo].time) / 1000.0
            out[i] = if (dt > 0) (cum[hi] - cum[lo]) / dt else 0.0
        }
        return out
    }
}
