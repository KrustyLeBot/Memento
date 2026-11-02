package app.memento.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import app.memento.MainActivity
import app.memento.R
import app.memento.agenda.AgendaData
import app.memento.agenda.AgendaIndex
import app.memento.agenda.AgendaSources
import app.memento.agenda.AgendaText
import app.memento.agenda.Alarm
import app.memento.agenda.ReminderBook
import app.memento.agenda.ReminderPlanner
import java.time.Instant
import java.time.ZoneId

/**
 * Agenda reminders (README, "Agenda", reminders): the rolling 48 h window of [AgendaIndex.alarms] registered with
 * [AlarmManager], planned by [ReminderPlanner]. Exact alarms when the "Alarmes et rappels" access is granted,
 * inexact ones (a few minutes late) otherwise. Blocking (prefs, folder reads): never on the main thread.
 */
object Reminders {
    const val CHANNEL = "agenda_reminders"
    private const val TAG = "Reminders"
    private const val PREFS = "reminders"
    private const val BOOK = "book"
    private val SNOOZE_MS = 10 * 60_000L
    private val PERIOD_MS = 6 * 3_600_000L

    const val ACTION_FIRE = "app.memento.reminders.FIRE"
    const val ACTION_SNOOZE = "app.memento.reminders.SNOOZE"
    const val ACTION_PERIODIC = "app.memento.reminders.PERIODIC"

    const val EXTRA_KEY = "key"
    /** The planner's key the notification stands for (the same for its snoozes). */
    const val EXTRA_NOTIFY_KEY = "notify_key"
    const val EXTRA_TITLE = "title"
    const val EXTRA_KIND = "kind"
    const val EXTRA_TARGET = "target"
    const val EXTRA_PATH = "path"
    const val EXTRA_START = "start"
    const val EXTRA_ALL_DAY = "all_day"
    const val EXTRA_LOCATION = "location"
    const val EXTRA_AT = "at"

    private val lock = Any()

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rappels d'agenda", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Les rappels des événements et des notes datées. Son et priorité réglables ici sans toucher aux autres notifications."
        })
    }

    /** The system page of the reminders' channel (pattern of Synchrotron's settings). */
    fun openChannelSettings(context: Context) {
        createChannel(context)
        val channel = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(channel)
        } catch (e: android.content.ActivityNotFoundException) {
            Log.w(TAG, "no channel settings page, opening the app's notification settings", e)
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun notificationsAllowed(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** "Alarmes et rappels" page of this app (Android 12+). */
    fun exactAlarmSettings(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))

    fun ignoresBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    @Suppress("BatteryLife") // reminders must fire on time on Samsung phones; the user confirms in the system dialog
    fun batteryExemptionIntent(context: Context): Intent =
        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    // ---- book ----

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readBook(context: Context): ReminderBook = try {
        ReminderBook.parse(prefs(context).getString(BOOK, null))
    } catch (e: app.memento.agenda.Json.JsonException) {
        Log.e(TAG, "reminder book unreadable, starting a new one", e)
        ReminderBook()
    }

    private fun writeBook(context: Context, book: ReminderBook) {
        prefs(context).edit().putString(BOOK, book.toJson()).commit()
    }

    // ---- scheduling ----

    private fun alarmUri(key: String): Uri = Uri.Builder().scheme("memento").authority("alarm").appendPath(key).build()

    private fun fireIntent(context: Context, key: String): Intent =
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE).setData(alarmUri(key))

    private fun extras(i: Intent, a: Alarm, key: String, notifyKey: String): Intent = i
        .putExtra(EXTRA_KEY, key).putExtra(EXTRA_NOTIFY_KEY, notifyKey).putExtra(EXTRA_TITLE, a.title)
        .putExtra(EXTRA_KIND, a.kind.name).putExtra(EXTRA_TARGET, a.targetId).putExtra(EXTRA_PATH, a.targetPath)
        .putExtra(EXTRA_START, a.occurrenceStart.toEpochMilli()).putExtra(EXTRA_ALL_DAY, a.allDay)
        .putExtra(EXTRA_LOCATION, a.location).putExtra(EXTRA_AT, a.at.toEpochMilli())

    private fun setAlarm(context: Context, at: Long, pi: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java)
        if (!canExact(context)) return am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            // the access was withdrawn between the check and the call: the inexact alarm still fires
            Log.w(TAG, "exact alarm refused, inexact one set", e)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /**
     * Recomputes the window from [sources] and registers it: what the planner keeps is (re)set (same key =
     * same PendingIntent, replaced), what vanished is cancelled, snoozes of vanished reminders included. Also keeps
     * the 6-hourly re-run registered.
     */
    fun reschedule(context: Context, sources: AgendaSources, now: Instant = Instant.now()) {
        val app = context.applicationContext
        synchronized(lock) {
            val wanted = AgendaIndex.alarms(sources, now.minus(ReminderPlanner.LATE), ZoneId.systemDefault(),
                AgendaIndex.ALARM_HORIZON.plus(ReminderPlanner.LATE))
            val zone = ZoneId.systemDefault()
            val plan = ReminderPlanner.plan(wanted, readBook(app), now) { AgendaIndex.alarmAlive(sources, it, zone) }
            val am = app.getSystemService(AlarmManager::class.java)
            for (key in plan.cancel) {
                PendingIntent.getBroadcast(app, 0, fireIntent(app, key), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                    ?.let { am.cancel(it); it.cancel() }
            }
            for (a in plan.schedule) {
                val pi = PendingIntent.getBroadcast(app, 0, extras(fireIntent(app, a.key), a, a.key, a.key),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                setAlarm(app, a.at.toEpochMilli(), pi)
            }
            writeBook(app, plan.book)
            Log.i(TAG, "${plan.schedule.size} reminder(s) registered, ${plan.cancel.size} cancelled, exact=${canExact(app)}")
        }
        schedulePeriodic(app)
    }

    /** From a receiver: reads the folder (or the app's published data) and reschedules. Blocking. */
    fun rescheduleFromDisk(context: Context, maxAgeMs: Long = 0L) {
        val sources = AgendaData.load(context, maxAgeMs)
        if (sources == null) {
            Log.w(TAG, "folder not readable yet (permission): reminders left as they were")
            return
        }
        reschedule(context, sources)
    }

    private fun schedulePeriodic(context: Context) {
        val pi = PendingIntent.getBroadcast(context, 0, Intent(context, ReminderReceiver::class.java).setAction(ACTION_PERIODIC),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java)
            .setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + PERIOD_MS, PERIOD_MS, pi)
    }

    // ---- firing ----

    /** An alarm went off: remembered as shown (never shown again after a resync), then notified. */
    fun fire(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val notifyKey = intent.getStringExtra(EXTRA_NOTIFY_KEY) ?: key
        synchronized(lock) {
            val book = readBook(context)
            if (ReminderPlanner.isSnooze(key)) writeBook(context, book.copy(snoozed = book.snoozed - key))
            else {
                if (key in book.fired) return
                writeBook(context, ReminderPlanner.fired(book, key, intent.getLongExtra(EXTRA_AT, System.currentTimeMillis())))
            }
        }
        notify(context, intent, notifyKey)
    }

    private fun notificationId(notifyKey: String) = notifyKey.hashCode()

    /** "Aujourd'hui · 14:30 · 12 rue de la Paix", "Demain · Toute la journée". */
    fun text(context: Context, startMs: Long, allDay: Boolean, location: String?, now: Instant = Instant.now()): String {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(startMs).atZone(zone)
        val day = AgendaText.dayTitle(start.toLocalDate(), now.atZone(zone).toLocalDate())
        val time = if (allDay) "Toute la journée" else AgendaText.time(start.toLocalTime(), android.text.format.DateFormat.is24HourFormat(context))
        return listOfNotNull(day, time, location?.ifBlank { null }).joinToString(" · ")
    }

    private fun notify(context: Context, fired: Intent, notifyKey: String) {
        if (!notificationsAllowed(context)) {
            Log.w(TAG, "reminder not shown: notifications are off for the app")
            return
        }
        createChannel(context)
        val title = fired.getStringExtra(EXTRA_TITLE)?.ifBlank { null } ?: "(Sans titre)"
        val text = text(context, fired.getLongExtra(EXTRA_START, 0L), fired.getBooleanExtra(EXTRA_ALL_DAY, false), fired.getStringExtra(EXTRA_LOCATION))
        val id = notificationId(notifyKey)
        val open = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_ENTRY)
            .setData(alarmUri(notifyKey))
            .putExtras(fired)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val snooze = Intent(context, ReminderReceiver::class.java).setAction(ACTION_SNOOZE).setData(alarmUri(notifyKey)).putExtras(fired)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
        @Suppress("DEPRECATION")
        val n = b.setSmallIcon(R.drawable.ic_stat_memento)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_EVENT)
            .setPriority(Notification.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setWhen(fired.getLongExtra(EXTRA_START, System.currentTimeMillis()))
            .setShowWhen(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_stat_memento), "Reporter 10 min",
                PendingIntent.getBroadcast(context, 0, snooze, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
            .build()
        context.getSystemService(NotificationManager::class.java).notify(TAG, id, n)
    }

    /**
     * "Reporter 10 min": the notification goes, the same reminder comes back in 10 minutes. One snooze per
     * notification: a second tap replaces the pending one (same key, same PendingIntent) instead of adding one.
     */
    fun snooze(context: Context, intent: Intent) {
        val notifyKey = intent.getStringExtra(EXTRA_NOTIFY_KEY) ?: intent.getStringExtra(EXTRA_KEY) ?: return
        context.getSystemService(NotificationManager::class.java).cancel(TAG, notificationId(notifyKey))
        val until = System.currentTimeMillis() + SNOOZE_MS
        val key = ReminderPlanner.snoozeKey(notifyKey)
        val fire = Intent(intent).setComponent(android.content.ComponentName(context, ReminderReceiver::class.java))
            .setAction(ACTION_FIRE).setData(alarmUri(key)).putExtra(EXTRA_KEY, key).putExtra(EXTRA_NOTIFY_KEY, notifyKey)
        synchronized(lock) {
            val book = readBook(context)
            writeBook(context, book.copy(snoozed = book.snoozed + (key to until)))
        }
        setAlarm(context, until, PendingIntent.getBroadcast(context, 0, fire, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }

    /** The notification a tap on it opened is not needed any more. */
    fun dismiss(context: Context, notifyKey: String?) {
        if (notifyKey != null) context.getSystemService(NotificationManager::class.java).cancel(TAG, notificationId(notifyKey))
    }
}
