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
        GpxScan.elements(text, "trkpt") { start, attrs, inner ->
            while (segIdx + 1 < segPositions.size && segPositions[segIdx + 1] < start) {
                segIdx++
                segmentStarts.add(points.size)
            }
            val lat = GpxScan.attr(attrs, "lat")?.toDoubleOrNull() ?: return@elements
            val lon = GpxScan.attr(attrs, "lon")?.toDoubleOrNull() ?: return@elements
            val ele = GpxScan.child(inner, "ele")?.toDoubleOrNull()
            val hr = GpxScan.heartRate(inner)
            val time = GpxScan.child(inner, "time")?.let { parseTime(it) }
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
        val sorted = points.sortedBy { it.time }
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

    fun parseTime(s: String): Long? = try {
        Instant.parse(s).toEpochMilli()
    } catch (_: Exception) {
        try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) { null }
    }
}
