package app.memento.agenda

import app.memento.Note
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class EntryKind { EVENT, NOTE, ACTIVITY, BIRTHDAY }

/** A contact's birthday, as the contacts module gives it. */
data class Birthday(
    val contactId: String,
    /** Display name ("Marie Dupont"). */
    val name: String,
    /** `1990-05-14` or `--05-14` (also `19900514`, `--0514`). */
    val birthday: String,
    /** The contact file, to open it. */
    val path: String? = null,
)

/** What the index reads: everything already in memory. */
data class AgendaSources(
    /** Files of `agenda/` ([AgendaStore.scan]). */
    val events: List<Event> = emptyList(),
    /** Regular notes: the dated ones (`start` or `reminder`) are picked here. */
    val notes: List<Note> = emptyList(),
    /** Notes of `activities/` (with [Note.activity]). */
    val activities: List<Note> = emptyList(),
    val birthdays: List<Birthday> = emptyList(),
    val settings: CalendarSettings = CalendarSettings(),
)

/** One line of the calendar. */
data class Entry(
    val kind: EntryKind,
    /** Stable: "<id>|<wall start>" (events, notes), "activity|<id>", "birthday|<contact id>|<year>". */
    val key: String,
    val title: String,
    /** Second line: location, "3/5", "12,3 km · 1 h 05", "(34 ans)". */
    val subtitle: String?,
    /** "#RRGGBB" of the calendar or the layer. */
    val color: String,
    val allDay: Boolean,
    /** All-day: 00:00 (device zone) of the first day. */
    val start: Instant,
    /** All-day: 00:00 (device zone) of the day after the last day. */
    val end: Instant,
    /** Device zone. */
    val startDate: LocalDate,
    /** Last day shown, included (activities: their start day). */
    val endDate: LocalDate,
    /** Note / event / activity / contact id to open. */
    val targetId: String,
    val targetPath: String?,
    /** Events: the resolved calendar. */
    val calendarId: String? = null,
    /** Events and dated notes. */
    val event: Event? = null,
    /** Wall start/end in the event's own zone ("14:30 à Paris"); null for activities and birthdays. */
    val occurrence: Occurrence? = null,
    /** Activities: the sport key (the UI picks the emoji). */
    val sport: String? = null,
    val recurring: Boolean = false,
)

data class EntriesResult(
    val entries: List<Entry>,
    /** French, one line each: unreadable files, rules, truncated series. Never dropped silently. */
    val problems: List<String>,
)

/** An alarm of the next hours ([AgendaIndex.alarms]). */
data class Alarm(
    /** "<id>|<wall start>|<offset minutes>" or "<id>|reminder|<ms>": stable across rescans and syncs. */
    val key: String,
    val at: Instant,
    val kind: EntryKind,
    val title: String,
    val targetId: String,
    val targetPath: String?,
    val occurrenceStart: Instant,
    val allDay: Boolean,
    val offsetMinutes: Int,
    val location: String? = null,
)

/**
 * The dated-entries index: everything the calendar shows in a window, and the alarms of the next hours.
 * Pure functions over what the app holds in memory; nothing is written. Same rules as Python's
 * `core/agenda/entries.py`:
 * - EVENT: occurrences of the event files, colour/visibility of their calendar (unknown id = default calendar);
 *   second line = location.
 * - NOTE: notes with `start` (expanded like events) or `reminder` (zero-length at that instant); Notes layer
 *   colour; second line = checklist progress "3/5".
 * - ACTIVITY: one timed entry from `start` lasting `elapsed`, on its start day only; Activités layer (optional
 *   sport filter); second line "12,3 km · 1 h 05".
 * - BIRTHDAY: every year, all-day, "Anniversaire de <nom>", "(34 ans)" when the year is known (29 February ->
 *   28 February on other years).
 * Sorted by start, all-day first at equal start, then title, then key.
 */
object AgendaIndex {
    val ALARM_HORIZON: Duration = Duration.ofHours(48)

    /** "12,3 km · 1 h 05" (distance with one decimal, duration "1 h 05" or "45 min"). */
    fun activitySubtitle(distanceM: Double, elapsedS: Long): String? {
        val parts = ArrayList<String>()
        if (distanceM > 0) {
            val tenths = Math.floor(distanceM / 100.0 + 0.5).toLong()
            parts.add("${tenths / 10},${tenths % 10} km")
        }
        if (elapsedS > 0) {
            val minutes = elapsedS / 60
            parts.add(if (minutes >= 60) String.format(java.util.Locale.ROOT, "%d h %02d", minutes / 60, minutes % 60) else "$minutes min")
        }
        return parts.joinToString(" · ").ifEmpty { null }
    }

    private val BIRTHDAY = Regex("""^(?:(\d{4})-?|--)(\d{2})-?(\d{2})$""")

    /** (year or null, month, day). Throws [IllegalArgumentException] when unreadable. */
    fun parseBirthday(text: String): Triple<Int?, Int, Int> {
        val m = BIRTHDAY.matchEntire(text.trim()) ?: throw IllegalArgumentException(text)
        val year = m.groupValues[1].ifEmpty { null }?.toInt()
        val month = m.groupValues[2].toInt()
        val day = m.groupValues[3].toInt()
        try {
            LocalDate.of(year ?: 2000, month, day)
        } catch (e: java.time.DateTimeException) {
            throw IllegalArgumentException(text)
        }
        return Triple(year, month, day)
    }

    private val ORDER: Comparator<Entry> =
        compareBy<Entry>({ it.start }, { if (it.allDay) 0 else 1 }, { it.title }, { it.key })

    private fun overrides(events: List<Event>): Map<String, List<Stamp>> {
        val out = HashMap<String, MutableList<Stamp>>()
        for (e in events) {
            val of = e.recurrenceOf ?: continue
            val at = e.recurrenceAt ?: continue
            out.getOrPut(of) { ArrayList() }.add(at)
        }
        return out
    }

    /** The notes with a `start`, read as events (conflict copies left out), and the problems met. */
    fun datedNotes(notes: List<Note>): Pair<List<Event>, List<String>> {
        val out = ArrayList<Event>()
        val problems = ArrayList<String>()
        for (n in notes) {
            if (n.conflict) continue
            val e = try {
                EventFormat.fromNote(n)
            } catch (x: AgendaFormatException) {
                problems.add(x.message ?: n.path); continue
            } ?: continue
            out.add(e)
            e.warnings.forEach { problems.add("${n.path} : $it") }
        }
        return out to problems
    }

    private fun progressText(body: String): String? = EventFormat.checklistProgress(body)?.let { "${it.first}/${it.second}" }

    private fun localDate(i: Instant, zone: ZoneId): LocalDate = i.atZone(zone).toLocalDate()

    private fun occurrenceEntry(kind: EntryKind, o: Occurrence, color: String, subtitle: String?, calendarId: String?, device: ZoneId): Entry {
        val e = o.event
        val startDate: LocalDate
        val endDate: LocalDate
        if (e.allDay) {
            startDate = o.start.date
            endDate = o.end!!.date
        } else {
            startDate = localDate(o.startInstant, device)
            val last = localDate(o.endInstant.minusMillis(1), device)
            endDate = if (last.isBefore(startDate)) startDate else last
        }
        return Entry(kind, o.key, e.title, subtitle, color, e.allDay, o.startInstant, o.endInstant, startDate, endDate,
            e.id, e.path.ifEmpty { null }, calendarId, e, o, null, e.isSeries)
    }

    /** Every entry overlapping [from, to), sorted. */
    fun entries(sources: AgendaSources, from: Instant, to: Instant, device: ZoneId, respectVisibility: Boolean = true): EntriesResult {
        val entries = ArrayList<Entry>()
        val problems = ArrayList<String>()
        val settings = sources.settings
        val over = overrides(sources.events)

        fun addExpansion(kind: EntryKind, e: Event, color: String, subtitle: String?, calendarId: String?) {
            val x = RecurrenceExpander.expand(e, from, to, device, over[e.id].orEmpty())
            x.error?.let { problems.add("${e.path.ifEmpty { e.title }} : $it") }
            if (x.truncated) problems.add("${e.path.ifEmpty { e.title }} : trop d'occurrences, liste coupée")
            x.occurrences.forEach { entries.add(occurrenceEntry(kind, it, color, subtitle, calendarId, device)) }
        }

        for (e in sources.events) {
            if (e.note.conflict) continue
            e.warnings.forEach { problems.add("${e.path} : $it") }
            val cal = settings.calendar(e.calendar)
            if (respectVisibility && !cal.visible) continue
            addExpansion(EntryKind.EVENT, e, cal.color, e.location, cal.id)
        }

        val notesLayer = settings.layer(CalendarSettings.LAYER_NOTES)
        if (notesLayer.visible || !respectVisibility) {
            val (dated, notesProblems) = datedNotes(sources.notes)
            problems.addAll(notesProblems)
            for (e in dated) addExpansion(EntryKind.NOTE, e, notesLayer.color, progressText(e.body), null)
            val datedIds = dated.map { it.id }.toSet()
            for (n in sources.notes) {
                val r = n.reminder ?: continue
                if (n.conflict || n.id in datedIds) continue
                val at = Instant.ofEpochMilli(r)
                if (at.isBefore(from) || !at.isBefore(to)) continue
                val d = localDate(at, device)
                entries.add(Entry(EntryKind.NOTE, "${n.id}|reminder|$r", n.title, progressText(n.body), notesLayer.color,
                    false, at, at, d, d, n.id, n.path.ifEmpty { null }))
            }
        }

        val acts = settings.layer(CalendarSettings.LAYER_ACTIVITIES)
        if (acts.visible || !respectVisibility) {
            for (n in sources.activities) {
                val info = n.activity ?: continue
                if (n.conflict) continue
                if (respectVisibility && acts.sports != null && info.sport.key !in acts.sports) continue
                val start = Instant.ofEpochMilli(info.start)
                val end = start.plusSeconds(Math.max(0L, info.elapsed))
                val inside = if (end == start) !start.isBefore(from) && start.isBefore(to) else start.isBefore(to) && end.isAfter(from)
                if (!inside) continue
                val d = localDate(start, device)
                entries.add(Entry(EntryKind.ACTIVITY, "activity|${n.id}", n.title, activitySubtitle(info.distance, info.elapsed),
                    acts.color, false, start, end, d, d, n.id, n.path.ifEmpty { null }, sport = info.sport.key))
            }
        }

        val bdays = settings.layer(CalendarSettings.LAYER_BIRTHDAYS)
        if (bdays.visible || !respectVisibility) {
            val first = localDate(from, device)
            val last = localDate(to.minusMillis(1), device)
            for (b in sources.birthdays) {
                val (year, month, day) = try {
                    parseBirthday(b.birthday)
                } catch (_: IllegalArgumentException) {
                    problems.add("${b.name} : anniversaire illisible ('${b.birthday}')"); continue
                }
                for (y in first.year..last.year) {
                    if (year != null && y < year) continue
                    val d = try { LocalDate.of(y, month, day) } catch (_: java.time.DateTimeException) { LocalDate.of(y, 2, 28) }
                    val s = Zones.dayStart(d, device)
                    val e = Zones.dayStart(d.plusDays(1), device)
                    if (!(s.isBefore(to) && e.isAfter(from))) continue
                    val age = if (year != null) y - year else 0
                    val subtitle = if (age >= 1) (if (age == 1) "(1 an)" else "($age ans)") else null
                    entries.add(Entry(EntryKind.BIRTHDAY, "birthday|${b.contactId}|$y", "Anniversaire de ${b.name}", subtitle,
                        bdays.color, true, s, e, d, d, b.contactId, b.path))
                }
            }
        }
        entries.sortWith(ORDER)
        return EntriesResult(entries, problems)
    }

    /**
     * The reminder [notifyKey] ([Alarm.key]) still exists: its event or dated note, that occurrence and that alert
     * offset, or the note's `reminder` at that instant. A snoozed reminder of something gone is not shown again.
     */
    fun alarmAlive(sources: AgendaSources, notifyKey: String, device: ZoneId): Boolean {
        val parts = notifyKey.split('|')
        if (parts.size != 3) return false
        val id = parts[0]
        if (parts[1] == "reminder") {
            val ms = parts[2].toLongOrNull() ?: return false
            return sources.notes.any { it.id == id && !it.conflict && it.reminder == ms }
        }
        val offset = parts[2].toIntOrNull() ?: return false
        val wall = try { Stamp.parse(parts[1]) } catch (_: IllegalArgumentException) { return false }
        val e = sources.events.firstOrNull { it.id == id && !it.note.conflict }
            ?: datedNotes(sources.notes.filter { it.id == id }).first.firstOrNull()
            ?: return false
        if (offset !in e.alerts) return false
        val at = if (wall.allDay) Zones.dayStart(wall.date, device) else Zones.toInstant(wall.dateTime(), Zones.zoneOr(e.tz, device))
        val sub = AgendaSources(events = sources.events.filter { it.id == id || it.recurrenceOf == id }, notes = sources.notes.filter { it.id == id })
        return entries(sub, at, at.plusSeconds(1), device, respectVisibility = false).entries.any { it.key == "$id|${parts[1]}" }
    }

    /**
     * Alarms falling in [now, now + horizon): event and dated-note `alerts`, note `reminder`s; an all-day alert is
     * a wall time of the device zone (00:00 of the day + offset), so "2 jours avant à 09:00" stays at 09:00 across
     * a daylight-saving change. Visibility does not
     * silence them (hiding is a view filter); cancelled events have none. Sorted by instant then key.
     */
    fun alarms(sources: AgendaSources, now: Instant, device: ZoneId, horizon: Duration = ALARM_HORIZON): List<Alarm> {
        val out = ArrayList<Alarm>()
        val end = now.plus(horizon)
        val over = overrides(sources.events)
        val dated = datedNotes(sources.notes).first
        val candidates = sources.events.filter { !it.note.conflict }.map { EntryKind.EVENT to it } + dated.map { EntryKind.NOTE to it }
        for ((kind, e) in candidates) {
            if (e.alerts.isEmpty()) continue
            val lead = e.alerts.filter { it < 0 }.maxOfOrNull { -it } ?: 0
            val lag = e.alerts.filter { it > 0 }.maxOrNull() ?: 0
            // all-day alerts are wall times ("2 jours avant à 09:00"): a day of slack covers a DST change in between
            val slack = if (e.allDay) 86_400L else 0L
            val x = RecurrenceExpander.expand(e, now.minusSeconds(lag * 60L + slack), end.plusSeconds(lead * 60L + slack), device, over[e.id].orEmpty())
            for (o in x.occurrences) {
                for (offset in e.alerts.toSortedSet()) {
                    val at = if (e.allDay) Zones.toInstant(o.start.date.atStartOfDay().plusMinutes(offset.toLong()), device)
                    else o.startInstant.plusSeconds(offset * 60L)
                    if (!at.isBefore(now) && at.isBefore(end)) {
                        out.add(Alarm("${o.key}|$offset", at, kind, e.title, e.id, e.path.ifEmpty { null }, o.startInstant, e.allDay, offset, e.location))
                    }
                }
            }
        }
        for (n in sources.notes) {
            val r = n.reminder ?: continue
            if (n.conflict) continue
            val at = Instant.ofEpochMilli(r)
            if (!at.isBefore(now) && at.isBefore(end)) {
                out.add(Alarm("${n.id}|reminder|$r", at, EntryKind.NOTE, n.title, n.id, n.path.ifEmpty { null }, at, false, 0))
            }
        }
        out.sortWith(compareBy({ it.at }, { it.key }))
        return out
    }
}
