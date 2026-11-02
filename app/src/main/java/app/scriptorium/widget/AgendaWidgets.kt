package app.scriptorium.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import app.scriptorium.MainActivity
import app.scriptorium.R
import app.scriptorium.agenda.AgendaData
import app.scriptorium.agenda.AgendaIndex
import app.scriptorium.agenda.AgendaText
import app.scriptorium.agenda.Entry
import app.scriptorium.agenda.WidgetOptions
import app.scriptorium.agenda.WidgetRow
import app.scriptorium.agenda.WidgetRows
import app.scriptorium.agenda.Zones
import app.scriptorium.reminders.Reminders
import app.scriptorium.ui.sportEmoji
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.concurrent.thread

/**
 * The two home-screen widgets (README, "Agenda", widgets): the agenda list ([AgendaWidget]) and the small
 * "Aujourd'hui" one ([TodayWidget]). They never read the folder on the main thread: rows come from
 * [AgendaData] in the collection's factory or a background thread.
 */
object AgendaWidgets {
    private const val TAG = "AgendaWidgets"
    private const val PREFS = "agenda_widgets"
    const val ACTION_REFRESH = "app.scriptorium.widget.REFRESH"
    const val ACTION_MIDNIGHT = "app.scriptorium.widget.MIDNIGHT"

    /** Widgets showing the spinner (a ⟳ in progress). */
    @Volatile var refreshing = false

    // ---- options of each widget (this phone only) ----

    fun options(context: Context, id: Int): WidgetOptions = try {
        WidgetOptions.parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("w_$id", null))
    } catch (e: app.scriptorium.agenda.Json.JsonException) {
        Log.e(TAG, "options of widget $id unreadable, defaults used", e)
        WidgetOptions()
    }

    fun saveOptions(context: Context, id: Int, o: WidgetOptions) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("w_$id", o.toJson()).apply()
    }

    fun deleteOptions(context: Context, ids: IntArray) {
        val e = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        ids.forEach { e.remove("w_$it") }
        e.apply()
    }

    // ---- data ----

    /** Rows of a widget (blocking: reads the folder when the app has not published recently). */
    fun rows(context: Context, o: WidgetOptions, maxAgeMs: Long = 10 * 60_000L): List<WidgetRow> {
        val sources = AgendaData.load(context, maxAgeMs) ?: return emptyList()
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val today = now.atZone(zone).toLocalDate()
        val entries = AgendaIndex.entries(sources, Zones.dayStart(today, zone), Zones.dayStart(today.plusDays(o.days.toLong()), zone), zone).entries
        val h24 = android.text.format.DateFormat.is24HourFormat(context)
        return WidgetRows.rows(entries, now, zone, o, h24) { key -> sportEmoji(app.scriptorium.activity.Sport.of(key)) }
    }

    // ---- intents ----

    private fun mainIntent(context: Context, action: String) =
        Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun openTodayIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 1, mainIntent(context, MainActivity.ACTION_AGENDA_TODAY), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun newEventIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 2, mainIntent(context, MainActivity.ACTION_NEW_EVENT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Rows fill this template in with their entry (a collection's template must be mutable for that). */
    private fun rowTemplate(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 3, mainIntent(context, MainActivity.ACTION_OPEN_ENTRY),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))

    fun fillIn(e: Entry): Intent = Intent()
        .putExtra(Reminders.EXTRA_KIND, e.kind.name).putExtra(Reminders.EXTRA_TARGET, e.targetId)
        .putExtra(Reminders.EXTRA_PATH, e.targetPath).putExtra(Reminders.EXTRA_START, e.start.toEpochMilli())

    private fun refreshIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, 4, Intent(context, AgendaWidget::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    // ---- views ----

    private fun alpha(o: WidgetOptions) = (o.opacity.coerceIn(0, 100) * 255 + 50) / 100

    /** The agenda widget's frame (header, buttons, list adapter); the rows come from [AgendaWidgetService]. */
    fun agendaViews(context: Context, id: Int, manager: AppWidgetManager): RemoteViews {
        val o = options(context, id)
        val v = RemoteViews(context.packageName, R.layout.widget_agenda)
        val today = LocalDate.now()
        v.setTextViewText(R.id.weekday, AgendaText.weekday(today.dayOfWeek))
        v.setTextViewText(R.id.date, "${if (today.dayOfMonth == 1) "1er" else today.dayOfMonth} ${AgendaText.MONTHS[today.monthValue - 1]}")
        val width = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val narrow = width in 1..200
        v.setTextViewTextSize(R.id.date, TypedValue.COMPLEX_UNIT_SP, if (narrow) 19f else 24f)
        v.setTextViewTextSize(R.id.weekday, TypedValue.COMPLEX_UNIT_SP, if (narrow) 12f else 13f)
        v.setInt(R.id.bg, "setImageAlpha", alpha(o))
        v.setViewVisibility(R.id.refresh_box, if (o.showRefresh) View.VISIBLE else View.GONE)
        v.setViewVisibility(R.id.refresh, if (refreshing) View.INVISIBLE else View.VISIBLE)
        v.setViewVisibility(R.id.spinner, if (refreshing) View.VISIBLE else View.GONE)
        v.setOnClickPendingIntent(R.id.header, openTodayIntent(context))
        v.setOnClickPendingIntent(R.id.add, newEventIntent(context))
        v.setOnClickPendingIntent(R.id.refresh, refreshIntent(context))
        val adapter = Intent(context, AgendaWidgetService::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        // one factory per widget: the data URI makes the intents differ
        adapter.data = Uri.parse(adapter.toUri(Intent.URI_INTENT_SCHEME))
        @Suppress("DEPRECATION")
        v.setRemoteAdapter(R.id.list, adapter)
        v.setEmptyView(R.id.list, R.id.empty)
        v.setPendingIntentTemplate(R.id.list, rowTemplate(context))
        return v
    }

    fun rowViews(context: Context, row: WidgetRow): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_agenda_row)
        v.setTextViewText(R.id.row_weekday, row.weekday.orEmpty())
        v.setTextViewText(R.id.row_day, row.dayNumber.orEmpty())
        v.setTextViewText(R.id.row_title, listOfNotNull(row.icon, row.title).joinToString(" "))
        v.setTextViewText(R.id.row_time, row.time)
        v.setInt(R.id.row_bar, "setColorFilter", parseColor(row.color))
        v.setOnClickFillInIntent(R.id.row, fillIn(row.entry))
        return v
    }

    fun parseColor(hex: String): Int = try {
        android.graphics.Color.parseColor(hex)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "colour '$hex' unreadable", e)
        android.graphics.Color.GRAY
    }

    /** The "Aujourd'hui" widget (blocking: computes the rows). */
    fun todayViews(context: Context, id: Int): RemoteViews {
        val o = options(context, id)
        val v = RemoteViews(context.packageName, R.layout.widget_today)
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val today = now.atZone(zone).toLocalDate()
        val sources = AgendaData.load(context)
        val entries = if (sources == null) emptyList()
        else AgendaIndex.entries(sources, Zones.dayStart(today, zone), Zones.dayStart(today.plusDays(1), zone), zone).entries
        val t = WidgetRows.today(entries, now, zone, o)
        val h24 = android.text.format.DateFormat.is24HourFormat(context)
        val next = t.next
        if (next == null) {
            v.setTextViewText(R.id.today_title, "Rien de prévu")
            v.setTextViewText(R.id.today_sub, "Aujourd'hui, ${AgendaText.weekday(today.dayOfWeek)} ${today.dayOfMonth}")
            v.setInt(R.id.today_bar, "setColorFilter", context.getColor(R.color.widget_accent))
        } else {
            val icon = WidgetRows.icon(next) { sportEmoji(app.scriptorium.activity.Sport.of(it)) }
            v.setTextViewText(R.id.today_title, listOfNotNull(icon, next.title.ifBlank { "(Sans titre)" }).joinToString(" "))
            val time = AgendaText.timeLine(next, zone, h24)
            v.setTextViewText(R.id.today_sub, if (t.others > 0) "$time · +${t.others} autre${if (t.others > 1) "s" else ""}" else time)
            v.setInt(R.id.today_bar, "setColorFilter", parseColor(next.color))
        }
        v.setInt(R.id.bg, "setImageAlpha", alpha(o))
        v.setOnClickPendingIntent(R.id.today_root, openTodayIntent(context))
        return v
    }

    // ---- updates ----

    /**
     * Redraws every widget: the app calls it after any change (edit, sync, import, file watcher), the system
     * events and the midnight alarm too. Works on a background thread.
     */
    fun updateAll(context: Context) {
        val app = context.applicationContext
        thread(name = "AgendaWidgetsUpdate") { updateAllNow(app) }
    }

    /**
     * [updateAll] on the caller's thread (blocking, never the main thread). Receivers call it inside `goAsync()`:
     * a bare thread outliving `onReceive` may be killed with the process before the widgets are drawn.
     */
    fun updateAllNow(context: Context) {
        val app = context.applicationContext
        try {
            val manager = AppWidgetManager.getInstance(app)
            val agendaIds = manager.getAppWidgetIds(ComponentName(app, AgendaWidget::class.java))
            agendaIds.forEach { manager.updateAppWidget(it, agendaViews(app, it, manager)) }
            if (agendaIds.isNotEmpty()) {
                @Suppress("DEPRECATION")
                manager.notifyAppWidgetViewDataChanged(agendaIds, R.id.list)
            }
            manager.getAppWidgetIds(ComponentName(app, TodayWidget::class.java)).forEach { manager.updateAppWidget(it, todayViews(app, it)) }
            scheduleMidnight(app)
        } catch (e: Exception) {
            Log.e(TAG, "widget update failed", e)
        }
    }

    /** The date in the header and "today" change at midnight: one inexact alarm a few seconds after it. */
    private fun scheduleMidnight(context: Context) {
        val zone = ZoneId.systemDefault()
        val next = Zones.dayStart(LocalDate.now(zone).plusDays(1), zone).toEpochMilli() + 5_000
        val pi = PendingIntent.getBroadcast(context, 5, Intent(context, AgendaWidget::class.java).setAction(ACTION_MIDNIGHT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, next, pi)
    }
}
