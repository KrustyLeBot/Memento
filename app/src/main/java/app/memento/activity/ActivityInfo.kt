package app.memento.activity

import java.util.Locale

/**
 * What the note's frontmatter says about an activity (`activity: {...}`), enough to list it without
 * reading its file: the full detail is recomputed from the FIT/GPX attached to the note.
 */
data class ActivityInfo(
    val sport: Sport,
    /** Epoch millis. */
    val start: Long,
    val elapsed: Long, // seconds
    val moving: Long, // seconds
    val distance: Double, // metres
    val ascent: Int = 0,
    val descent: Int = 0,
    val hr: Int? = null,
    val vmax: Double? = null, // m/s
    val runs: Int = 0,
    /** Track file (`attachments/…fit` or `.gpx`), null for activities without GPS. */
    val file: String? = null,
    /** Altitude looked up from the terrain model for a file that had none (`attachments/….alt`). */
    val alt: String? = null,
    /** Skiing: climbs (lifts, uphill) and metres skied down. */
    val climbs: Int = 0,
    val drop: Int = 0,
) {
    fun toJson(): String = buildString {
        append("{\"sport\": \"").append(sport.key).append('"')
        append(", \"start\": ").append(start)
        append(", \"elapsed\": ").append(elapsed)
        append(", \"moving\": ").append(moving)
        append(", \"distance\": ").append(String.format(Locale.ROOT, "%.1f", distance))
        append(", \"ascent\": ").append(ascent)
        append(", \"descent\": ").append(descent)
        hr?.let { append(", \"hr\": ").append(it) }
        vmax?.let { append(", \"vmax\": ").append(String.format(Locale.ROOT, "%.2f", it)) }
        if (runs > 0) append(", \"runs\": ").append(runs)
        if (climbs > 0) append(", \"climbs\": ").append(climbs)
        if (drop > 0) append(", \"drop\": ").append(drop)
        file?.let { append(", \"file\": \"").append(it.replace("\\", "\\\\").replace("\"", "\\\"")).append('"') }
        alt?.let { append(", \"alt\": \"").append(it.replace("\\", "\\\\").replace("\"", "\\\"")).append('"') }
        append('}')
    }

    companion object {
        /** `alt` value of a recording whose barometer altitude was corrected with the terrain model (no file). */
        const val ALT_REFINED = "baro+relief"

        private val PAIR = Regex("""\"(\w+)\"\s*:\s*(\"((?:[^\"\\]|\\.)*)\"|[-+0-9.eE]+)""")

        fun parse(raw: String): ActivityInfo? {
            val map = HashMap<String, String>()
            for (m in PAIR.findAll(raw)) {
                map[m.groupValues[1]] = if (m.groupValues[2].startsWith("\"")) m.groupValues[3].replace("\\\"", "\"").replace("\\\\", "\\") else m.groupValues[2]
            }
            val start = map["start"]?.toDoubleOrNull()?.toLong() ?: return null
            return ActivityInfo(
                sport = Sport.of(map["sport"]),
                start = start,
                elapsed = map["elapsed"]?.toDoubleOrNull()?.toLong() ?: 0,
                moving = map["moving"]?.toDoubleOrNull()?.toLong() ?: 0,
                distance = map["distance"]?.toDoubleOrNull() ?: 0.0,
                ascent = map["ascent"]?.toDoubleOrNull()?.toInt() ?: 0,
                descent = map["descent"]?.toDoubleOrNull()?.toInt() ?: 0,
                hr = map["hr"]?.toDoubleOrNull()?.toInt(),
                vmax = map["vmax"]?.toDoubleOrNull(),
                runs = map["runs"]?.toDoubleOrNull()?.toInt() ?: 0,
                file = map["file"]?.ifEmpty { null },
                alt = map["alt"]?.ifEmpty { null },
                climbs = map["climbs"]?.toDoubleOrNull()?.toInt() ?: 0,
                drop = map["drop"]?.toDoubleOrNull()?.toInt() ?: 0,
            )
        }

        fun from(sport: Sport, a: Analysis, track: Track, file: String?, alt: String? = null): ActivityInfo {
            val s = a.stats
            return ActivityInfo(
                sport = sport,
                start = track.startTime ?: a.samples.firstOrNull()?.time ?: 0L,
                elapsed = s.elapsedMs / 1000,
                moving = s.movingMs / 1000,
                distance = s.distance,
                ascent = Math.round(s.ascent).toInt(),
                descent = Math.round(s.descent).toInt(),
                hr = s.hrAvg,
                vmax = if (sport == Sport.SKI && s.runs > 0) s.runMaxSpeed else s.maxSpeed.takeIf { it > 0 },
                runs = s.runs,
                file = file,
                alt = alt,
                climbs = s.climbs,
                drop = Math.round(s.runDrop).toInt(),
            )
        }
    }
}

/** Display helpers, in French. */
object Fmt {
    fun distance(m: Double): String =
        if (m < 1000) "${Math.round(m)} m" else String.format(Locale.FRANCE, "%.2f km", m / 1000)

    fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d h %02d", h, m) else String.format(Locale.ROOT, "%d min %02d", m, s)
    }

    fun clock(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    fun speed(ms: Double): String = String.format(Locale.FRANCE, "%.1f km/h", ms * 3.6)

    /** min/km, or "–" when standing still. */
    fun pace(ms: Double): String {
        if (ms < 0.2) return "–"
        val sec = Math.round(1000 / ms)
        return String.format(Locale.ROOT, "%d:%02d /km", sec / 60, sec % 60)
    }

    fun meters(m: Double): String = "${Math.round(m)} m"

    /** Slowest pace drawn on a pace chart: stops would otherwise flatten the curve. */
    const val CHART_SLOWEST_PACE_S = 20 * 60.0

    /** Pace in seconds per km for a chart, capped at [CHART_SLOWEST_PACE_S]. */
    fun chartPace(ms: Double): Double = if (ms <= 1000 / CHART_SLOWEST_PACE_S) CHART_SLOWEST_PACE_S else 1000 / ms

    /** The main speed figure of a sport: pace for walking, km/h otherwise. */
    fun speedFor(sport: Sport, ms: Double): String = if (sport == Sport.WALK) pace(ms) else speed(ms)
}
