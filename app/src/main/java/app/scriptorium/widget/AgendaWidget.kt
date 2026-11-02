package app.scriptorium.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import app.scriptorium.R
import app.scriptorium.Settings
import app.scriptorium.StorageConfig
import app.scriptorium.agenda.AgendaData
import app.scriptorium.agenda.WidgetRow
import app.scriptorium.reminders.Reminders
import app.scriptorium.sync.SyncState
import app.scriptorium.sync.SynchrotronClient
import kotlin.concurrent.thread

/** The agenda list widget (Part B §7): header, ⟳, +, and the next entries in a scrollable collection. */
class AgendaWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        AgendaWidgets.updateAll(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        // resized: the header's text size follows the width
        manager.updateAppWidget(id, AgendaWidgets.agendaViews(context, id, manager))
    }

    override fun onDeleted(context: Context, ids: IntArray) = AgendaWidgets.deleteOptions(context, ids)

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            AgendaWidgets.ACTION_MIDNIGHT -> AgendaWidgets.updateAll(context)
            AgendaWidgets.ACTION_REFRESH -> refresh(context)
            else -> super.onReceive(context, intent)
        }
    }

    /** ⟳: spinner, ask Synchrotron for a sync (as the app does when it opens), read the folder again, redraw. */
    private fun refresh(context: Context) {
        if (AgendaWidgets.refreshing) return
        val app = context.applicationContext
        val pending = goAsync()
        AgendaWidgets.refreshing = true
        AgendaWidgets.updateAll(app)
        var done = false
        fun finish() {
            if (done) return
            done = true
            thread(name = "AgendaWidgetRefresh") {
                try {
                    AgendaData.invalidate()
                    AgendaData.load(app, 0L)?.let { Reminders.reschedule(app, it) }
                } catch (e: Exception) {
                    Log.e("AgendaWidget", "refresh failed", e)
                } finally {
                    AgendaWidgets.refreshing = false
                    AgendaWidgets.updateAll(app)
                    pending.finish()
                }
            }
        }
        val settings = Settings(app)
        if (settings.storage !is StorageConfig.Default) { finish(); return }
        // a broadcast may last a minute at most: the client reports a timeout at 20 s, and this guards a silent one
        val stop = SynchrotronClient.syncOnOpen(app, Settings.defaultDir().path) { state ->
            if (state != SyncState.Syncing) finish()
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!done) { stop(); finish() }
        }, 30_000)
    }
}

/** Rows of the agenda widget, built off the main thread by the system's collection binder. */
class AgendaWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID))

    private class Factory(val context: Context, val id: Int) : RemoteViewsFactory {
        @Volatile private var rows: List<WidgetRow> = emptyList()

        override fun onCreate() = Unit
        override fun onDataSetChanged() {
            rows = try {
                AgendaWidgets.rows(context, AgendaWidgets.options(context, id))
            } catch (e: Exception) {
                Log.e("AgendaWidget", "rows not built", e)
                emptyList()
            }
        }
        override fun onDestroy() { rows = emptyList() }
        override fun getCount() = rows.size
        override fun getViewAt(position: Int): RemoteViews =
            rows.getOrNull(position)?.let { AgendaWidgets.rowViews(context, it) } ?: RemoteViews(context.packageName, R.layout.widget_agenda_row)
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int): Long = rows.getOrNull(position)?.entry?.key?.hashCode()?.toLong() ?: position.toLong()
        override fun hasStableIds() = true
    }
}

/** The small "Aujourd'hui" widget (2×1). */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        AgendaWidgets.updateAll(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        AgendaWidgets.updateAll(context)
    }
}
