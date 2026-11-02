package app.memento.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import app.memento.GpsMode
import app.memento.MainActivity
import app.memento.Settings
import app.memento.activity.Fmt
import app.memento.activity.Sport
import app.memento.map.MapRuntime
import app.memento.map.NetState
import app.memento.map.OfflinePacks

/**
 * Keeps the GPS (and the barometer) running while the screen is off. It only feeds [Recorder]: the
 * state, the log and the notification text all come from there.
 */
class RecordingService : Service(), LocationListener, SensorEventListener {
    private var locations: LocationManager? = null
    private var sensors: SensorManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pressure: Double? = null
    private var pressureAt = 0L
    private var lastNotified = 0L
    /** Where the recording started, until the 2D zone pack around it has been asked for. */
    private var zoneStart: Pair<Double, Double>? = null
    private var zoneAsked = false

    override fun onCreate() {
        super.onCreate()
        MapRuntime.init(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                Recorder.pause(System.currentTimeMillis())
                try { locations?.removeUpdates(this) } catch (_: SecurityException) {}
            }
            ACTION_RESUME -> {
                Recorder.resume(System.currentTimeMillis())
                requestUpdates()
            }
            ACTION_STOP -> {
                Recorder.finish(System.currentTimeMillis())
                shutDown()
                return START_NOT_STICKY
            }
        }
        val live = Recorder.state.value
        if (live == null || live.finished) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNow(live.paused)
        if (intent?.action == null || intent.action == ACTION_START) startSensors(live.sport)
        return START_NOT_STICKY
    }

    private var interval = 3_000L

    /** False when the permission is missing (the recording is then stopped). */
    private fun requestUpdates(): Boolean {
        val lm = locations ?: return true
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, interval, 0f, this, Looper.getMainLooper())
        } catch (e: SecurityException) {
            Recorder.finish(System.currentTimeMillis())
            shutDown()
            return false
        } catch (e: IllegalArgumentException) {
            // no GPS provider on this device
        }
        return true
    }

    private fun startForegroundNow(paused: Boolean) {
        val n = notification(paused)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun startSensors(sport: Sport) {
        if (locations != null) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locations = lm
        interval = fixInterval(sport, Settings(this).gpsMode)
        if (!requestUpdates()) return
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensors = sm
        sm.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            sm.registerListener(this, it, 2_000_000)
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "memento:recording").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    override fun onLocationChanged(loc: Location) {
        val now = System.currentTimeMillis()
        val p = pressure?.takeIf { now - pressureAt < 15_000 }
        Recorder.onFix(
            loc.time.takeIf { it > 0 } ?: now, loc.latitude, loc.longitude,
            if (loc.hasAltitude()) loc.altitude else null,
            if (loc.hasAccuracy()) loc.accuracy else null, p, if (loc.hasSpeed()) loc.speed else null,
            if (loc.hasBearing()) loc.bearing else null,
        )
        askZonePack(loc.latitude, loc.longitude)
        if (now - lastNotified > 20_000) {
            lastNotified = now
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(Recorder.state.value?.paused == true))
        }
    }

    /**
     * Without a route, a 2D map of the 20 km around the start is downloaded once, so the map still shows
     * something if the network goes. Offline at the start: asked again as soon as the network is back,
     * still around the start, never around where the user has got to.
     */
    private fun askZonePack(lat: Double, lon: Double) {
        if (zoneAsked) return
        val live = Recorder.state.value ?: return
        if (live.fixes == 0) return
        if (zoneStart == null) zoneStart = lat to lon
        if (Recorder.route() != null) { zoneAsked = true; return }
        if (!NetState.online.value) return
        zoneAsked = true
        val (la, lo) = zoneStart!!
        OfflinePacks.prepareZone(lo, la)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PRESSURE) {
            pressure = event.values[0].toDouble()
            pressureAt = System.currentTimeMillis()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    @Deprecated("Required below API 30")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}

    private fun shutDown() {
        try { locations?.removeUpdates(this) } catch (_: SecurityException) {}
        locations = null
        sensors?.unregisterListener(this)
        sensors = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try { locations?.removeUpdates(this) } catch (_: SecurityException) {}
        sensors?.unregisterListener(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun notification(paused: Boolean): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Enregistrement d'activité", NotificationManager.IMPORTANCE_LOW))
        }
        val live = Recorder.state.value
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_RECORD)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(label: String, act: String, code: Int, icon: Int) = Notification.Action.Builder(
            icon, label,
            PendingIntent.getService(this, code, Intent(this, RecordingService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        ).build()
        val text = live?.let {
            val clock = Fmt.clock(it.elapsed(System.currentTimeMillis()) / 1000)
            if (it.sport.hasGps) "$clock · ${Fmt.distance(it.distance)}" else clock
        } ?: ""
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setSmallIcon(app.memento.R.drawable.ic_stat_memento)
            .setContentTitle((live?.sport?.label ?: "Activité") + if (paused) " · en pause" else " · en cours")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                if (paused) action("Reprendre", ACTION_RESUME, 1, android.R.drawable.ic_media_play)
                else action("Pause", ACTION_PAUSE, 2, android.R.drawable.ic_media_pause),
            )
            .addAction(action("Arrêter", ACTION_STOP, 3, android.R.drawable.ic_menu_close_clear_cancel))
            .build()
    }

    companion object {
        const val ACTION_START = "app.memento.record.START"
        const val ACTION_PAUSE = "app.memento.record.PAUSE"
        const val ACTION_RESUME = "app.memento.record.RESUME"
        const val ACTION_STOP = "app.memento.record.STOP"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 42

        /** Milliseconds between two GPS fixes. */
        fun fixInterval(sport: Sport, mode: GpsMode): Long = when (mode) {
            GpsMode.ECONOMY -> 12_000L
            GpsMode.NORMAL -> when (sport) {
                Sport.WALK -> 5_000L
                Sport.BIKE, Sport.EBIKE, Sport.SKI -> 2_000L
                else -> 3_000L
            }
        }

        fun start(context: Context, action: String = ACTION_START) {
            val i = Intent(context, RecordingService::class.java).setAction(action)
            // Only the first start must come with a notification within seconds; later commands reach a running service.
            if (action == ACTION_START && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}
