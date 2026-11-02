package app.memento.activity

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** What an activity is for the app. Everything else a watch may say (running, hiking…) maps onto these. */
enum class Sport(val key: String, val label: String, val hasGps: Boolean) {
    WALK("walk", "Marche", true),
    BIKE("bike", "Vélo", true),
    EBIKE("ebike", "Vélo électrique", true),
    SKI("ski", "Ski", true),
    GPS("gps", "Autre (GPS)", true),
    PLAIN("plain", "Autre (sans GPS)", false);

    companion object {
        fun of(key: String?): Sport = values().firstOrNull { it.key == key } ?: GPS

        /** FIT `sport` enum → ours. */
        fun fromFit(code: Int?): Sport = when (code) {
            1, 11, 17, 16 -> WALK // running, walking, hiking, mountaineering
            2 -> BIKE // cycling
            21 -> EBIKE // e-biking
            12, 13, 14 -> SKI // cross-country, alpine, snowboarding
            else -> GPS
        }

        /** The sport the file declares; "generic" (some watches) or none stays "Autre": the user picks. */
        fun guess(fitCode: Int?): Sport = if (fitCode == null || fitCode == 0) GPS else fromFit(fitCode)

        fun toFit(sport: Sport): Int = when (sport) {
            WALK -> 11
            BIKE -> 2
            EBIKE -> 21
            SKI -> 13
            else -> 0
        }
    }
}

/** One GPS fix. [alt] is the altitude the analysis uses; [gpsAlt] and [pressure] are kept for later reprocessing. */
data class TrackPoint(
    val time: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double? = null,
    val hr: Int? = null,
    val gpsAlt: Double? = null,
    val pressure: Double? = null,
)

/** A stretch of time the user paused the timer (button), [start]..[end] in epoch millis. */
data class Pause(val start: Long, val end: Long)

data class Track(
    val points: List<TrackPoint>,
    val pauses: List<Pause> = emptyList(),
    /** FIT sport code, when the file had one. */
    val fitSport: Int? = null,
    val name: String? = null,
    /** Start of the session, when the file says so (needed for files without positions). */
    val startTime: Long? = null,
    val sessionElapsedMs: Long? = null,
    val sessionTimerMs: Long? = null,
    val sessionDistance: Double? = null,
    val hrAvg: Int? = null,
    val hrMax: Int? = null,
    /** Who wrote the file, when it says (FIT manufacturer code). */
    val manufacturer: Int? = null,
)

object Geo {
    private const val R = 6371008.8

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * R * asin(sqrt(a))
    }

    fun distance(a: TrackPoint, b: TrackPoint) = distance(a.lat, a.lon, b.lat, b.lon)
}
