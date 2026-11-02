package app.memento.agenda

import java.time.Duration
import java.time.Instant

/**
 * What the reminders remember between runs (app-private, never synced): the alarms registered with the system
 * and the ones already shown, by [Alarm.key] -> epoch millis of the alarm.
 */
data class ReminderBook(
    val scheduled: Map<String, Long> = emptyMap(),
    val fired: Map<String, Long> = emptyMap(),
    /** Snoozed reminders ("Reporter 10 min"): their own key -> when. Not part of the planning. */
    val snoozed: Map<String, Long> = emptyMap(),
) {
    fun toJson(): String = Json.write(linkedMapOf("scheduled" to scheduled, "fired" to fired, "snoozed" to snoozed))

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun parse(text: String?): ReminderBook {
            if (text.isNullOrBlank()) return ReminderBook()
            val m = Json.parse(text) as? Map<String, Any?> ?: throw Json.JsonException("pas un objet JSON")
            fun map(k: String) = (m[k] as? Map<String, Any?>).orEmpty().mapNotNull { (key, v) -> (v as? Number)?.toLong()?.let { key to it } }.toMap()
            return ReminderBook(map("scheduled"), map("fired"), map("snoozed"))
        }
    }
}

/** One run of the planner: alarms to (re)register, keys to cancel (snoozes included), and the book to store. */
data class ReminderPlan(val schedule: List<Alarm>, val cancel: List<String>, val book: ReminderBook)

/**
 * The rolling reminder window (README, "Agenda", reminders). Pure, so it is tested on the JVM:
 * - the wanted alarms are [AgendaIndex.alarms] from `now - LATE` (keys are stable: event id + occurrence + offset);
 * - an alarm already shown ([ReminderBook.fired]) is never registered again, so a sync bringing the same event back
 *   does not notify twice;
 * - every future alarm is (re)registered: the same key replaces the system's alarm, so nothing doubles, and a
 *   reboot (which forgets every alarm) is covered by the same call;
 * - an alarm whose time passed less than [LATE] ago is registered again only if it was registered before and not
 *   shown (a late or missed alarm, e.g. phone off): it fires at once. A new event's alert already in the past is not;
 * - registered alarms no longer wanted (event deleted, moved, alert removed) are cancelled;
 * - shown keys are forgotten after [KEEP_FIRED];
 * - a snooze whose reminder no longer exists ([snoozeAlive] false: event deleted, occurrence removed, alert
 *   dropped) is cancelled with the others.
 */
object ReminderPlanner {
    val LATE: Duration = Duration.ofHours(1)
    val KEEP_FIRED: Duration = Duration.ofDays(3)

    fun plan(wanted: List<Alarm>, book: ReminderBook, now: Instant, snoozeAlive: (notifyKey: String) -> Boolean = { true }): ReminderPlan {
        val nowMs = now.toEpochMilli()
        val lateMs = nowMs - LATE.toMillis()
        val schedule = ArrayList<Alarm>()
        val seen = HashSet<String>()
        for (a in wanted.sortedWith(compareBy({ it.at }, { it.key }))) {
            if (!seen.add(a.key) || a.key in book.fired) continue
            val at = a.at.toEpochMilli()
            if (at >= nowMs || (at >= lateMs && a.key in book.scheduled)) schedule.add(a)
        }
        val keys = schedule.map { it.key }.toSet()
        val fired = book.fired.filterValues { it >= nowMs - KEEP_FIRED.toMillis() }
        val dead = book.snoozed.keys.filter { !snoozeAlive(notifyKeyOf(it)) }.toSet()
        val snoozed = book.snoozed.filter { (k, v) -> v >= nowMs - KEEP_FIRED.toMillis() && k !in dead }
        val cancel = (book.scheduled.keys.filter { it !in keys } + dead).sorted()
        return ReminderPlan(schedule, cancel, ReminderBook(schedule.associate { it.key to it.at.toEpochMilli() }, fired, snoozed))
    }

    /** The book once [key] was shown. */
    fun fired(book: ReminderBook, key: String, at: Long): ReminderBook =
        book.copy(scheduled = book.scheduled - key, fired = book.fired + (key to at), snoozed = book.snoozed - key)

    /** Key of a snoozed reminder, outside the planner's keys: one per notification, so snoozing again replaces it. */
    fun snoozeKey(key: String) = "snooze|$key"

    /** The planner's key a snooze stands for. */
    fun notifyKeyOf(snoozeKey: String) = snoozeKey.removePrefix("snooze|")

    fun isSnooze(key: String) = key.startsWith("snooze|")
}
