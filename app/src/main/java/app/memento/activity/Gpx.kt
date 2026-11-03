package app.memento.activity

import java.time.Instant
import java.time.OffsetDateTime

/**
 * GPX reader. A new `<trkseg>` after the first is taken as a pause (the timer was stopped there). A file with no
 * `<time>` at all is read as an untimed track ([Track.untimed]).
 */
object Gpx {
    const val UNTIMED_STEP_MS = 1000L

    fun looksLikeGpx(text: String) = text.contains("<gpx", ignoreCase = true)

    fun read(text: String): Track {
        if (!looksLikeGpx(text)) throw Fit.FitException("Ce n'est pas un fichier GPX")
        val points = ArrayList<TrackPoint>()
        val untimed = ArrayList<TrackPoint>()
        val segmentStarts = ArrayList<Int>() // index of the first point of each segment
        val segPositions = GpxScan.openings(text, "<trkseg")
        var segIdx = -1
        GpxScan.ranges(text, "trkpt") { start, af, at, inf, int ->
            while (segIdx + 1 < segPositions.size && segPositions[segIdx + 1] < start) {
                segIdx++
                segmentStarts.add(points.size)
            }
            val lat = GpxScan.attr(text, af, at, "lat")?.toDoubleOrNull() ?: return@ranges
            val lon = GpxScan.attr(text, af, at, "lon")?.toDoubleOrNull() ?: return@ranges
            val ele = GpxScan.child(text, inf, int, "ele")?.toDoubleOrNull()
            val hr = GpxScan.heartRate(text, inf, int)
            val time = GpxScan.child(text, inf, int, "time")?.let { parseTime(it) }
            if (time == null) {
                untimed.add(TrackPoint(untimed.size * UNTIMED_STEP_MS, lat, lon, ele, hr, gpsAlt = ele))
            } else {
                points.add(TrackPoint(time, lat, lon, ele, hr, gpsAlt = ele))
            }
        }
        val name = GpxScan.name(text, "trk")
        // timed points win: a file with a few untimed ones among them is a normal activity
        if (points.isEmpty() && untimed.isNotEmpty()) {
            val hrs = untimed.mapNotNull { it.hr }
            return Track(
                points = untimed, name = name, untimed = true,
                hrAvg = if (hrs.isNotEmpty()) Math.round(hrs.average()).toInt() else null, hrMax = hrs.maxOrNull(),
            )
        }
        // a recorded file is in order: no second list of every point unless it really is not
        val sorted = if ((1 until points.size).all { points[it - 1].time <= points[it].time }) points else points.sortedBy { it.time }
        val pauses = ArrayList<Pause>()
        for (s in segmentStarts.drop(1)) {
            if (s in 1 until points.size) {
                val a = points[s - 1].time
                val b = points[s].time
                if (b > a) pauses.add(Pause(a, b))
            }
        }
        val hrs = sorted.mapNotNull { it.hr }
        return Track(
            points = sorted,
            pauses = pauses,
            name = name,
            startTime = sorted.firstOrNull()?.time,
            hrAvg = if (hrs.isNotEmpty()) Math.round(hrs.average()).toInt() else null,
            hrMax = hrs.maxOrNull(),
        )
    }

    fun parseTime(s: String): Long? = utcTime(s) ?: try {
        Instant.parse(s).toEpochMilli()
    } catch (_: Exception) {
        try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) { null }
    }

    /**
     * The usual `2024-05-01T08:30:00Z` / `…:00.123Z` read by hand, null for anything else (left to java.time): the
     * formatter costs microseconds a point, seconds over a 200 000-point file on a weak phone.
     */
    internal fun utcTime(s: String): Long? {
        val n = s.length
        if (n < 20 || s[n - 1] != 'Z' || s[4] != '-' || s[7] != '-' || s[10] != 'T' || s[13] != ':' || s[16] != ':') return null
        fun digits(from: Int, count: Int): Int {
            var v = 0
            for (i in from until from + count) {
                val c = s[i]
                if (c !in '0'..'9') return -1
                v = v * 10 + (c - '0')
            }
            return v
        }
        val year = digits(0, 4)
        val month = digits(5, 2)
        val day = digits(8, 2)
        val hour = digits(11, 2)
        val minute = digits(14, 2)
        val second = digits(17, 2)
        if (year < 0 || month !in 1..12 || day < 1 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        var nanos = 0
        if (n > 20) {
            val fraction = n - 21 // between the '.' and the 'Z'
            if (s[19] != '.' || fraction !in 1..9) return null
            val f = digits(20, fraction)
            if (f < 0) return null
            nanos = f
            repeat(9 - fraction) { nanos *= 10 }
        }
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val monthDays = when (month) { 2 -> if (leap) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }
        if (day > monthDays) return null
        val epochDay = java.time.LocalDate.of(year, month, day).toEpochDay()
        val seconds = epochDay * 86_400 + hour * 3600 + minute * 60 + second
        return seconds * 1000 + nanos / 1_000_000
    }
}
