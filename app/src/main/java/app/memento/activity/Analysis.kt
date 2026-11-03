package app.memento.activity

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

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
    /** Lowest heart rate of the samples (the chart's low label), null without any. */
    val hrMin: Int? = null,
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
        // primitive copies: the loops below read each point many times, and a weak phone pays for every indirection
        val t = LongArray(n) { kept[it].time }
        val la = DoubleArray(n) { kept[it].lat }
        val lo = DoubleArray(n) { kept[it].lon }
        val altOk = BooleanArray(n) { kept[it].alt != null }
        val altRaw = DoubleArray(n) { kept[it].alt ?: 0.0 }
        val seg = IntArray(n)
        val pauses = PauseIndex(track.pauses)
        for (i in 1 until n) {
            seg[i] = seg[i - 1] + if (pauses.overlaps(t[i - 1], t[i])) 1 else 0
        }

        // stops: nothing moved more than STOP_RADIUS for at least STOP_MIN_MS
        val stopIdx = BooleanArray(n)
        val stopOf = IntArray(n) { -1 } // which stop a point belongs to: two points in the same one is an O(1) check
        val stops = ArrayList<Stop>()
        // "within STOP_RADIUS": a flat projection around the first point, exact to the micrometre at 8 m, without the
        // trigonometry of a full distance (a 200 000-point file asks it more than a million times)
        val latR = DoubleArray(n) { Math.toRadians(la[it]) }
        val lonR = DoubleArray(n) { Math.toRadians(lo[it]) }
        val cosLat = DoubleArray(n) { cos(latR[it]) }
        val r2 = (STOP_RADIUS / Geo.EARTH_RADIUS).let { it * it }
        fun near(a: Int, b: Int): Boolean {
            val dx = (lonR[b] - lonR[a]) * cosLat[a]
            val dy = latR[b] - latR[a]
            return dx * dx + dy * dy <= r2
        }
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && seg[j + 1] == seg[i] && near(i, j + 1)) j++
            if (j > i && t[j] - t[i] >= STOP_MIN_MS) {
                for (k in i..j) { stopIdx[k] = true; stopOf[k] = stops.size }
                stops.add(Stop(t[i], t[j], la[i], lo[i], manual = false))
                i = j + 1
            } else {
                i++
            }
        }

        // cumulative distance and moving time
        val cum = DoubleArray(n)
        var moving = 0L
        for (k in 1 until n) {
            val inStop = stopOf[k] >= 0 && stopOf[k] == stopOf[k - 1]
            cum[k] = cum[k - 1]
            if (seg[k] != seg[k - 1]) {
                // a pause (watch auto-pause, button): what was walked across it still counts, unless it is a jump
                val d = Geo.distance(la[k - 1], lo[k - 1], la[k], lo[k])
                if (d <= PAUSE_BRIDGE) cum[k] += d
                continue
            }
            if (!inStop) {
                cum[k] += Geo.distance(la[k - 1], lo[k - 1], la[k], lo[k])
                moving += t[k] - t[k - 1]
            }
        }

        // the smoothing window is in time: over stand-in times it would average points kilometres apart on a route
        val alt = if (track.untimed) altRaw else smoothAltitude(t, altRaw, altOk)
        val speed = smoothSpeed(t, seg, cum)
        val hasAlt = altOk.any { it }

        // ski runs
        val down = BooleanArray(n)
        val runs = ArrayList<Run>()
        if (sport == Sport.SKI && n > 1) {
            val stepDown = BooleanArray(n)
            for (k in 1 until n) {
                if (seg[k] != seg[k - 1]) continue
                val dt = (t[k] - t[k - 1]) / 1000.0
                if (dt <= 0) continue
                stepDown[k] = if (hasAlt && altOk[k] && altOk[k - 1]) {
                    (alt[k] - alt[k - 1]) / dt <= DOWN_RATE && speed[k] >= DOWN_MIN_SPEED
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
                    while (q < n && !stepDown[q] && seg[q] == seg[k] && t[q] - t[end] <= RUN_MERGE_MS) q++
                    if (q < n && stepDown[q] && seg[q] == seg[k] && t[q] - t[end] <= RUN_MERGE_MS) { m = q; continue }
                    break
                }
                val first = k - 1
                val dur = t[end] - t[first]
                val drop = if (hasAlt && altOk[first] && altOk[end]) alt[first] - alt[end] else 0.0
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
                val dt = (t[k] - t[k - 1]) / 1000.0
                if (dt > 0 && altOk[k] && altOk[k - 1]) stepUp[k] = (alt[k] - alt[k - 1]) / dt >= UP_RATE
            }
            var k = 1
            while (k < n) {
                if (!stepUp[k]) { k++; continue }
                var end = k
                var m = k + 1
                while (m < n && seg[m] == seg[k]) {
                    if (stepUp[m]) { end = m; m++; continue }
                    var q = m
                    while (q < n && !stepUp[q] && seg[q] == seg[k] && t[q] - t[end] <= CLIMB_MERGE_MS) q++
                    if (q < n && stepUp[q] && seg[q] == seg[k] && t[q] - t[end] <= CLIMB_MERGE_MS) { m = q; continue }
                    break
                }
                val first = k - 1
                val gain = if (altOk[first] && altOk[end]) alt[end] - alt[first] else 0.0
                val dur = t[end] - t[first]
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
        var hasRef = false
        var ref = 0.0
        var minAlt = 0.0
        var maxAlt = 0.0
        for (k in 0 until n) {
            if (!altOk[k]) continue
            val a = alt[k]
            if (!hasRef) { hasRef = true; ref = a; minAlt = a; maxAlt = a; continue }
            minAlt = min(minAlt, a)
            maxAlt = max(maxAlt, a)
            if (a - ref >= CLIMB_THRESHOLD) { ascent += a - ref; ref = a }
            else if (ref - a >= CLIMB_THRESHOLD) { descent += ref - a; ref = a }
        }

        // an untimed track's speeds are stand-ins: its samples are built without them rather than built then copied
        val sampleSpeed = if (track.untimed) DoubleArray(n) else speed
        val samples = ArrayList<Sample>(n)
        for (k in 0 until n) {
            val p = kept[k]
            val a = if (!altOk[k]) null else if (track.untimed) p.alt else alt[k]
            samples.add(Sample(t[k], la[k], lo[k], cum[k], a, sampleSpeed[k], p.hr, down[k], up[k], stopIdx[k]))
        }
        val pauseStops = track.pauses.map { p ->
            val at = lastAtOrBefore(t, p.start)
            val ref0 = if (at >= 0) kept[at] else kept.firstOrNull()
            Stop(p.start, p.end, ref0?.lat ?: 0.0, ref0?.lon ?: 0.0, manual = true)
        }.filter { p -> n > 0 && p.end > t[0] && p.start < t[n - 1] }

        var hrCount = 0
        var hrSum = 0L
        var hrMax = Int.MIN_VALUE
        var hrMin = Int.MAX_VALUE
        for (p in kept) {
            val h = p.hr ?: continue
            hrCount++
            hrSum += h
            if (h > hrMax) hrMax = h
            if (h < hrMin) hrMin = h
        }
        val elapsed = if (n >= 2) t[n - 1] - t[0] else (track.sessionElapsedMs ?: 0L)
        val intervals = DoubleArray(maxOf(n - 1, 0)) { (t[it + 1] - t[it]) / 1000.0 }.also { it.sort() }
        val stats = Stats(
            elapsedMs = elapsed,
            movingMs = if (n >= 2) moving else (track.sessionTimerMs ?: 0L),
            distance = deviceOrComputed(track.sessionDistance, if (n >= 2) cum[n - 1] else 0.0),
            ascent = ascent, descent = descent,
            maxSpeed = maxWindowSpeed(t, seg, cum),
            minAlt = if (hasRef) minAlt else null, maxAlt = if (hasRef) maxAlt else null,
            hrAvg = if (hrCount > 0) Math.round(hrSum.toDouble() / hrCount).toInt() else track.hrAvg,
            hrMax = if (hrCount > 0) hrMax else track.hrMax,
            runs = runs.size,
            runDrop = runs.sumOf { it.drop },
            runMaxSpeed = runs.maxOfOrNull { it.maxSpeed } ?: 0.0,
            runAvgSpeed = if (runs.isNotEmpty()) runs.sumOf { it.distance } / (runs.sumOf { it.durationMs } / 1000.0) else 0.0,
            runLongest = runs.maxOfOrNull { it.distance } ?: 0.0,
            climbs = climbs.size,
            climbGain = climbs.sumOf { it.gain },
            hrMin = if (hrCount > 0) hrMin else null,
        )
        val quality = Quality(
            points = total, dropped = total - n, hasAltitude = hasAlt, hasHr = hrCount > 0 || track.hrAvg != null,
            medianIntervalSec = if (intervals.isEmpty()) 0.0 else intervals[intervals.size / 2],
            manufacturer = track.manufacturer,
        )
        if (track.untimed) {
            // stand-in times one second apart: on a file recorded at 1 Hz (most watches) the stops above still keep
            // the jitter of standing still out of the distance, but their durations mean nothing and are not shown
            return Analysis(
                samples, emptyList(), pauseStops, runs, climbs,
                stats.copy(elapsedMs = 0, movingMs = 0, maxSpeed = 0.0, runMaxSpeed = 0.0, runAvgSpeed = 0.0),
                quality.copy(medianIntervalSec = 0.0),
            )
        }
        return Analysis(samples, stops, pauseStops, runs, climbs, stats, quality)
    }

    /** Index of the last of the sorted [t] at or before [time], -1 when none. */
    private fun lastAtOrBefore(t: LongArray, time: Long): Int {
        var lo = 0
        var hi = t.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] <= time) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    /**
     * "Is a pause open between these two instants", asked with ever later instants: a running maximum of the pauses'
     * ends instead of a scan of every pause for every point (a watch's auto-pause makes hundreds of them).
     */
    private class PauseIndex(pauses: List<Pause>) {
        private val byStart = if (pauses.size > 1) pauses.sortedBy { it.start } else pauses
        private var next = 0
        private var maxEnd = Long.MIN_VALUE

        /** Some pause starts before [to] and ends after [from]. [to] must never decrease from one call to the next. */
        fun overlaps(from: Long, to: Long): Boolean {
            while (next < byStart.size && byStart[next].start < to) {
                maxEnd = max(maxEnd, byStart[next].end)
                next++
            }
            return maxEnd > from
        }
    }

    /** The device's own total when the file has one and it is in the same range as ours (its sensors know more). */
    private fun deviceOrComputed(device: Double?, computed: Double): Double =
        if (device != null && device > 0 && (computed <= 0 || device in computed * 0.8..computed * 1.25)) device else computed

    /** Top speed held for MAX_WINDOW_MS: single GPS catch-up spikes (after a signal gap) do not count. */
    private fun maxWindowSpeed(t: LongArray, seg: IntArray, cum: DoubleArray): Double {
        var best = 0.0
        var j = 0
        for (i in t.indices) {
            if (j < i) j = i
            while (j + 1 < t.size && seg[j + 1] == seg[i] && t[j] - t[i] < MAX_WINDOW_MS) j++
            val dt = (t[j] - t[i]) / 1000.0
            if (dt >= MAX_WINDOW_MS / 1000.0 * 0.8) best = max(best, (cum[j] - cum[i]) / dt)
        }
        return best
    }

    /** Sorted by time, no repeated instants, no impossible jumps, nothing recorded while paused. */
    fun clean(track: Track): List<TrackPoint> {
        val pts = track.points
        // the readers already sort: a 200 000-point copy only for a track that really is out of order
        val sorted = if ((1 until pts.size).all { pts[it - 1].time <= pts[it].time }) pts else pts.sortedBy { it.time }
        val paused = PauseIndex(track.pauses)
        val out = ArrayList<TrackPoint>(sorted.size)
        for (p in sorted) {
            if (p.lat !in -90.0..90.0 || p.lon !in -180.0..180.0) continue
            if (paused.overlaps(p.time, p.time)) continue
            val prev = out.lastOrNull()
            if (prev != null) {
                val dt = (p.time - prev.time) / 1000.0
                if (dt <= 0) continue
                // stand-in times: a route's points can be far apart, which is no GPS jump
                if (!track.untimed && Geo.distance(prev, p) / dt > MAX_SPEED) continue
            }
            out.add(p)
        }
        return out
    }

    /** Mean of the altitudes within ALT_WINDOW_MS on either side, where [ok] says a point has one. */
    private fun smoothAltitude(t: LongArray, alt: DoubleArray, ok: BooleanArray): DoubleArray {
        val n = t.size
        val out = DoubleArray(n)
        var lo = 0
        var hi = 0
        for (i in 0 until n) {
            if (!ok[i]) continue
            while (t[lo] < t[i] - ALT_WINDOW_MS) lo++
            if (hi < i) hi = i
            while (hi + 1 < n && t[hi + 1] <= t[i] + ALT_WINDOW_MS) hi++
            var sum = 0.0
            var cnt = 0
            for (k in lo..hi) if (ok[k]) { sum += alt[k]; cnt++ }
            out[i] = sum / cnt
        }
        return out
    }

    private fun smoothSpeed(t: LongArray, seg: IntArray, cum: DoubleArray): DoubleArray {
        val n = t.size
        val out = DoubleArray(n)
        for (i in 0 until n) {
            var lo = i
            while (lo > 0 && seg[lo - 1] == seg[i] && t[i] - t[lo - 1] <= SPEED_WINDOW_MS) lo--
            var hi = i
            while (hi + 1 < n && seg[hi + 1] == seg[i] && t[hi + 1] - t[i] <= SPEED_WINDOW_MS) hi++
            if (lo == hi) {
                if (i > 0 && seg[i - 1] == seg[i]) lo = i - 1 else if (i + 1 < n && seg[i + 1] == seg[i]) hi = i + 1
            }
            val dt = (t[hi] - t[lo]) / 1000.0
            out[i] = if (dt > 0) (cum[hi] - cum[lo]) / dt else 0.0
        }
        return out
    }
}
