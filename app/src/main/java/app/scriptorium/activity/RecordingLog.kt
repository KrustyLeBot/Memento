package app.scriptorium.activity

import java.util.Locale

/**
 * What the recorder appends while an activity runs, one line per fix or event, so a crash or a dead
 * battery loses nothing. At the end the log becomes a FIT file and is deleted.
 *
 * ```
 * S,<sport>,<start ms>
 * P,<ms>,<lat>,<lon>,<alt|>,<gpsAlt|>,<hPa|>
 * E,<ms>,pause|resume
 * ```
 */
object RecordingLog {
    fun header(sport: Sport, start: Long) = "S,${sport.key},$start"

    fun point(p: TrackPoint): String = String.format(
        Locale.ROOT, "P,%d,%.7f,%.7f,%s,%s,%s", p.time, p.lat, p.lon,
        p.alt?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "",
        p.gpsAlt?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "",
        p.pressure?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "",
    )

    fun event(time: Long, kind: String) = "E,$time,$kind"

    class Recorded(val sport: Sport, val start: Long, val track: Track)

    /** Null when the text has no header. Unreadable lines (a torn last line) are skipped. */
    fun parse(text: String): Recorded? {
        var sport: Sport? = null
        var start = 0L
        val points = ArrayList<TrackPoint>()
        val pauses = ArrayList<Pause>()
        var pausedAt: Long? = null
        for (line in text.lineSequence()) {
            val f = line.split(',')
            try {
                when (f[0]) {
                    "S" -> { sport = Sport.of(f[1]); start = f[2].toLong() }
                    "P" -> points.add(
                        TrackPoint(
                            f[1].toLong(), f[2].toDouble(), f[3].toDouble(),
                            f.getOrNull(4)?.toDoubleOrNull(), null,
                            f.getOrNull(5)?.toDoubleOrNull(), f.getOrNull(6)?.toDoubleOrNull(),
                        ),
                    )
                    "E" -> {
                        val t = f[1].toLong()
                        if (f[2] == "pause") pausedAt = t
                        else if (f[2] == "resume") {
                            pausedAt?.let { if (t > it) pauses.add(Pause(it, t)) }
                            pausedAt = null
                        }
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }
        val s = sport ?: return null
        return Recorded(s, start, Track(points, pauses, startTime = start, fitSport = Sport.toFit(s)))
    }
}

/** Climb counted with the same hysteresis as the analysis, for the live screen. */
class ClimbCounter {
    var ascent = 0.0
        private set
    private var ref: Double? = null

    fun add(alt: Double) {
        val r = ref
        if (r == null) { ref = alt; return }
        if (alt - r >= Analyzer.CLIMB_THRESHOLD) { ascent += alt - r; ref = alt }
        else if (r - alt >= Analyzer.CLIMB_THRESHOLD) ref = alt
    }
}
