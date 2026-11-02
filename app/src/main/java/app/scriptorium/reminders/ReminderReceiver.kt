package app.scriptorium.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.scriptorium.widget.AgendaWidgets
import kotlin.concurrent.thread

/** Runs [work] off the main thread, keeping the receiver alive until it ends; a failure is logged, not hidden. */
internal fun BroadcastReceiver.async(tag: String, work: () -> Unit) {
    val pending = goAsync()
    thread(name = tag) {
        try {
            work()
        } catch (e: Exception) {
            Log.e(tag, "background work failed", e)
        } finally {
            pending.finish()
        }
    }
}

/** The app's own alarms and notification actions (not exported). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Reminders.ACTION_FIRE -> async("ReminderFire") { Reminders.fire(context, intent) }
            Reminders.ACTION_SNOOZE -> async("ReminderSnooze") { Reminders.snooze(context, intent) }
            Reminders.ACTION_PERIODIC -> async("ReminderPeriodic") {
                Reminders.rescheduleFromDisk(context, maxAgeMs = 30 * 60_000L)
            }
        }
    }
}

/**
 * System events after which the alarms must be registered again (Android forgets them on reboot; a time or
 * zone change moves them) and the widgets redrawn. Only protected broadcasts reach it.
 */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        async("AgendaSystemEvent") {
            Reminders.rescheduleFromDisk(context)
            AgendaWidgets.updateAllNow(context)
        }
    }

    companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
