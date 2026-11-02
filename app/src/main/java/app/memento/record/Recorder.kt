package app.memento.record

import app.memento.activity.AltitudeFusion
import app.memento.activity.ClimbCounter
import app.memento.activity.Geo
import app.memento.activity.RecordingLog
import app.memento.activity.Sport
import app.memento.activity.TrackPoint
import app.memento.map.OfflinePacks
import app.memento.map.Route
import app.memento.map.RouteProgress
import app.memento.map.RouteStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileWriter

/** What the live screen shows. All times are epoch millis. */
data class Live(
    val sport: Sport,
    val start: Long,
    val paused: Boolean = false,
    /** Total time spent paused before the current pause, ms. */
    val pausedMs: Long = 0,
    val pauseStart: Long = 0,
    val distance: Double = 0.0,
    val ascent: Double = 0.0,
    /** m/s, 0 when unknown. */
    val speed: Double = 0.0,
    val altitude: Double? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val fixes: Int = 0,
    /** Time spent moving (faster than [Recorder.MOVING_SPEED] between two fixes), for the arrival estimate. */
    val movingMs: Long = 0,
    /** Degrees from north, when the GPS knows it (it does only while moving). */
    val heading: Float? = null,
    val accuracy: Float? = null,
    /** Where the user is on the followed route, or null without one. */
    val route: RouteStatus? = null,
    /** Stopped: the log waits to become an activity (or to be discarded). */
    val finished: Boolean = false,
) {
    /** Time on the clock, pauses excluded. */
    fun elapsed(now: Long): Long {
        val end = if (finished || paused) pauseStart else now
        return (end - start - pausedMs).coerceAtLeast(0)
    }
}

/**
 * The state of the activity being recorded, shared by the screen and the foreground service. Every
 * fix and event is appended to a log file as it happens ([RecordingLog]).
 */
object Recorder {
    private val _state = MutableStateFlow<Live?>(null)
    val state: StateFlow<Live?> = _state

    private var logFile: File? = null
    private var writer: FileWriter? = null
    private var fusion = AltitudeFusion()
    private var climb = ClimbCounter()
    private var lastPoint: TrackPoint? = null
    private val trailLat = ArrayList<Double>()
    private val trailLon = ArrayList<Double>()
    private val trailSpeed = ArrayList<Double>()
    private var follow: RouteProgress? = null
    /** Bumped when the trail is thinned or cleared: indices into the old one are no longer valid. */
    private var trailEpoch = 0

    const val MOVING_SPEED = 0.5 // m/s

    /** The positions so far (thinned past 3000) and their speeds in m/s, for the live map. */
    class Trail(val lats: DoubleArray, val lons: DoubleArray, val speeds: DoubleArray, val epoch: Int)

    @Synchronized
    fun trail(): Trail = Trail(trailLat.toDoubleArray(), trailLon.toDoubleArray(), trailSpeed.toDoubleArray(), trailEpoch)

    /** Trail points from index [from] on, or null when the trail changed under that index (thinned). */
    @Synchronized
    fun trailSince(from: Int, epoch: Int): Trail? {
        if (epoch != trailEpoch || from > trailLat.size) return null
        val n = trailLat.size
        return Trail(
            DoubleArray(n - from) { trailLat[from + it] }, DoubleArray(n - from) { trailLon[from + it] },
            DoubleArray(n - from) { trailSpeed[from + it] }, trailEpoch,
        )
    }

    /** The route followed during this recording, set right after [begin]. */
    @Synchronized
    fun followRoute(route: Route?) {
        follow = route?.let { RouteProgress(it) }
        val s = _state.value ?: return
        _state.value = s.copy(route = follow?.status)
    }

    @Synchronized
    fun route(): Route? = follow?.route

    /** The offline pack prepared for the followed route, to show its download progress. Pinned while it is set. */
    @Volatile
    var routePack: String? = null
        set(v) {
            field?.let { OfflinePacks.unpin(it) }
            field = v
            v?.let { OfflinePacks.pin(it) }
        }

    fun logFile(dir: File) = File(dir, "recording/current.log")

    val active: Boolean get() = _state.value?.let { !it.finished } == true

    @Synchronized
    fun begin(dir: File, sport: Sport, now: Long) {
        check(!active) { "Un enregistrement est déjà en cours" }
        val f = logFile(dir)
        f.parentFile?.mkdirs()
        f.delete()
        open(f)
        append(RecordingLog.header(sport, now))
        fusion = AltitudeFusion()
        climb = ClimbCounter()
        lastPoint = null
        follow = null
        routePack = null
        StartZones.reset()
        clearTrail()
        _state.value = Live(sport, now)
    }

    /** An interrupted recording found at startup (the process died while recording). */
    @Synchronized
    fun recover(dir: File) {
        if (_state.value != null) return
        val f = logFile(dir)
        if (!f.isFile) return
        val rec = RecordingLog.parse(f.readText()) ?: return
        val first = rec.track.points.firstOrNull()?.time ?: rec.start
        val last = rec.track.points.lastOrNull()?.time ?: first
        logFile = f
        _state.value = Live(rec.sport, rec.start, finished = true, pauseStart = last, pausedMs = rec.track.pauses.sumOf { it.end - it.start })
    }

    @Synchronized
    fun pause(now: Long) {
        val s = _state.value ?: return
        if (s.paused || s.finished) return
        append(RecordingLog.event(now, "pause"))
        _state.value = s.copy(paused = true, pauseStart = now, speed = 0.0)
    }

    @Synchronized
    fun resume(now: Long) {
        val s = _state.value ?: return
        if (!s.paused || s.finished) return
        append(RecordingLog.event(now, "resume"))
        lastPoint = null // do not count the distance walked while paused
        _state.value = s.copy(paused = false, pausedMs = s.pausedMs + (now - s.pauseStart))
    }

    /** Stops recording; the log stays until [discard] or until the activity is saved. */
    @Synchronized
    fun finish(now: Long) {
        val s = _state.value ?: return
        if (s.finished) return
        close()
        _state.value = s.copy(finished = true, paused = false, pauseStart = if (s.paused) s.pauseStart else now)
    }

    /** Removes the log and clears the state. */
    @Synchronized
    fun discard() {
        close()
        logFile?.delete()
        logFile = null
        follow = null
        routePack = null
        StartZones.reset()
        clearTrail()
        _state.value = null
    }

    fun readLog(): String? = logFile?.takeIf { it.isFile }?.readText()

    /**
     * A GPS fix. [pressure] is the latest barometer reading in hPa, if the phone has one.
     * Fixes less accurate than 35 m are ignored.
     */
    @Synchronized
    fun onFix(time: Long, lat: Double, lon: Double, gpsAlt: Double?, accuracy: Float?, pressure: Double?, speed: Float?, bearing: Float? = null) {
        val s = _state.value ?: return
        if (s.paused || s.finished) return
        if (accuracy != null && accuracy > 35f) return
        val alt = fusion.update(time, pressure, gpsAlt, accuracy)
        val p = TrackPoint(time, lat, lon, alt, null, gpsAlt, pressure)
        append(RecordingLog.point(p))
        var distance = s.distance
        var moving = s.movingMs
        var segSpeed: Double? = null
        lastPoint?.let { prev ->
            val d = Geo.distance(prev, p)
            val dt = (time - prev.time) / 1000.0
            // ignore jitter at a standstill and impossible jumps
            if (d >= 3.0 && dt > 0 && d / dt < 50.0) distance += d
            if (dt > 0 && dt <= 60) {
                segSpeed = d / dt
                if (d / dt >= MOVING_SPEED && d / dt < 50.0) moving += time - prev.time
            }
        }
        lastPoint = p
        trailLat.add(lat); trailLon.add(lon); trailSpeed.add(speed?.toDouble() ?: segSpeed ?: 0.0)
        if (trailLat.size > 3000) { // keep every other point
            val la = trailLat.filterIndexed { i, _ -> i % 2 == 0 }
            val lo = trailLon.filterIndexed { i, _ -> i % 2 == 0 }
            val sp = trailSpeed.filterIndexed { i, _ -> i % 2 == 0 }
            trailLat.clear(); trailLat.addAll(la); trailLon.clear(); trailLon.addAll(lo); trailSpeed.clear(); trailSpeed.addAll(sp)
            trailEpoch++
        }
        alt?.let { climb.add(it) }
        _state.value = s.copy(
            distance = distance, ascent = climb.ascent, speed = (speed ?: 0f).toDouble(),
            altitude = alt ?: s.altitude, lat = lat, lon = lon, fixes = s.fixes + 1, movingMs = moving,
            heading = bearing, accuracy = accuracy, route = follow?.update(lat, lon),
        )
    }

    private fun clearTrail() {
        trailLat.clear(); trailLon.clear(); trailSpeed.clear()
        trailEpoch++
    }

    private fun open(f: File) {
        close()
        logFile = f
        writer = FileWriter(f, true)
    }

    private fun append(line: String) {
        val w = writer ?: logFile?.let { FileWriter(it, true).also { nw -> writer = nw } } ?: return
        w.write(line)
        w.write("\n")
        w.flush()
    }

    private fun close() {
        try { writer?.close() } catch (_: Exception) {}
        writer = null
    }
}
