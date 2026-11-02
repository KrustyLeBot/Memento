package app.memento.activity

import java.time.Instant
import java.time.OffsetDateTime

/** GPX reader. A new `<trkseg>` after the first is taken as a pause (the timer was stopped there). */
object Gpx {
    private val TRKPT = Regex("""<trkpt\b([^>]*?)(/>|>(.*?)</trkpt>)""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val SEG_OPEN = Regex("""<trkseg\b""")
    private val ATTR_LAT = Regex("""\blat\s*=\s*["']([^"']+)["']""")
    private val ATTR_LON = Regex("""\blon\s*=\s*["']([^"']+)["']""")
    private val ELE = Regex("""<ele>\s*([^<\s]+)\s*</ele>""")
    private val TIME = Regex("""<time>\s*([^<\s]+)\s*</time>""")
    private val HR = Regex("""<(?:\w+:)?hr>\s*(\d+)\s*</(?:\w+:)?hr>""")
    private val NAME = Regex("""<trk\b[^>]*>.*?<name>\s*(?:<!\[CDATA\[)?(.*?)(?:]]>)?\s*</name>""", setOf(RegexOption.DOT_MATCHES_ALL))

    fun looksLikeGpx(text: String) = text.contains("<gpx", ignoreCase = true)

    fun read(text: String): Track {
        if (!looksLikeGpx(text)) throw Fit.FitException("Ce n'est pas un fichier GPX")
        val points = ArrayList<TrackPoint>()
        val segmentStarts = ArrayList<Int>() // index of the first point of each segment
        val segPositions = SEG_OPEN.findAll(text).map { it.range.first }.toList()
        var segIdx = -1
        for (m in TRKPT.findAll(text)) {
            while (segIdx + 1 < segPositions.size && segPositions[segIdx + 1] < m.range.first) {
                segIdx++
                segmentStarts.add(points.size)
            }
            val attrs = m.groupValues[1]
            val lat = ATTR_LAT.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val lon = ATTR_LON.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val inner = m.groupValues[3]
            val time = TIME.find(inner)?.groupValues?.get(1)?.let { parseTime(it) } ?: continue
            val ele = ELE.find(inner)?.groupValues?.get(1)?.toDoubleOrNull()
            val hr = HR.find(inner)?.groupValues?.get(1)?.toIntOrNull()
            points.add(TrackPoint(time, lat, lon, ele, hr, gpsAlt = ele))
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
            name = NAME.find(text)?.groupValues?.get(1)?.trim()?.ifEmpty { null },
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
