package app.scriptorium.map

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import app.scriptorium.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while offline packs download, so leaving the app (or a Samsung's battery
 * manager) does not freeze them. Only shows the progress: the downloads themselves live in [OfflinePacks]
 * and stay resumable. Stops itself when none is running.
 */
class PackDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            OfflinePacks.cancelAll()
            shutDown()
            return START_NOT_STICKY
        }
        // within seconds of startForegroundService(), whatever happens next
        val n = notification(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        if (watching == null) {
            watching = scope.launch {
                combine(OfflinePacks.running, OfflinePacks.progress) { r, p -> r to p }.conflate().collect { (running, progress) ->
                    if (running.isEmpty()) {
                        shutDown()
                        return@collect
                    }
                    val parts = running.mapNotNull { progress[it] }
                    val total = parts.sumOf { it.total }
                    val fraction = if (total <= 0) null else parts.sumOf { it.done }.toFloat() / total
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(fraction))
                    delay(1000) // the progress moves every 25 tiles: one update a second is plenty
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 caps dataSync services at 6 h a day; the downloads go on as long as the process lives. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground time limit reached")
        shutDown()
    }

    private fun shutDown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(fraction: Float?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Téléchargement de cartes", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_MAP_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, PackDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setSmallIcon(app.scriptorium.R.drawable.ic_stat_scriptorium)
            .setContentTitle("Carte hors ligne en préparation")
            .setContentText(fraction?.let { "${Math.round(it * 100)} %" } ?: "")
            .setProgress(100, Math.round((fraction ?: 0f) * 100), fraction == null)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Annuler", cancel).build())
            .build()
    }

    companion object {
        private const val TAG = "PackDownloadService"
        private const val CHANNEL = "map_downloads"
        private const val NOTIFICATION_ID = 43
        private const val ACTION_CANCEL = "app.scriptorium.map.CANCEL_DOWNLOADS"

        fun start(context: Context) {
            val i = Intent(context, PackDownloadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
            } catch (e: IllegalStateException) {
                // Android 12+ refuses a start from the background (ForegroundServiceStartNotAllowedException):
                // the download still runs, only without the protection of the service
                Log.w(TAG, "download service not started: $e")
            }
        }
    }
}
