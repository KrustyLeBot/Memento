package app.scriptorium.agenda

import app.scriptorium.Note
import app.scriptorium.NoteFormat
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportResult(
    val text: String,
    val events: Int,
    /** French: ical_extra lines left out, unknown zones. */
    val warnings: List<String>,
)

/**
 * ICS export (README, "Agenda", export): the inverse mapping of the import. Same output, byte for byte, as
 * Python's `core/agenda/ics_export.py` (CRLF, lines folded at 75 octets).
 * - all-day: `DTSTART;VALUE=DATE`, DTEND = the day after the last day; timed with a known zone: `TZID=<IANA>`
 *   plus one VTIMEZONE per zone used (`end_tz`: DTEND in that zone, its VTIMEZONE too); floating (or an unknown zone name): no TZID;
 * - RRULE verbatim, EXDATE one line per value in DTSTART's form (a date on a timed series: that day at the
 *   series' start time), except the occurrences an exported exception file overrides; an exception file becomes
 *   an override (UID of its series, RECURRENCE-ID in the series' form);
 * - alerts -> VALARM; `ical_extra` verbatim (lines naming a property the export writes itself are left out and
 *   reported); DESCRIPTION = the note body;
 * - options: activities and dated notes as events (UTC times for activities and reminders).
 */
object IcsExport {
    const val PRODID = "-//Scriptorium//Agenda//FR"
    private val UTC_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val LOCAL_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")

    private fun utc(i: Instant) = UTC_FMT.format(LocalDateTime.ofInstant(i, ZoneOffset.UTC))

    private fun timeLine(name: String, s: Stamp, tz: String?): String = when {
        s.allDay -> "$name;VALUE=DATE:${DATE_FMT.format(s.date)}"
        tz != null -> "$name;TZID=$tz:${LOCAL_FMT.format(s.dateTime())}"
        else -> "$name:${LOCAL_FMT.format(s.dateTime())}"
    }

    private class Writer(now: Instant) {
        val lines = ArrayList<String>()
        val zones = sortedSetOf<String>()
        val warnings = ArrayList<String>()
        var count = 0
        val stamp = utc(now)

        fun zoneOf(e: Event): String? {
            val tz = e.tz ?: return null
            if (Zones.isValid(tz)) {
                if (!e.allDay) zones.add(tz)
                return tz
            }
            warnings.add("${e.title} : fuseau inconnu « $tz », exporté sans fuseau")
            return null
        }

        /** DTEND's zone: `end_tz` when known on an event exported with its zone, else the start's. */
        fun endZoneOf(e: Event, tz: String?): String? {
            val end = e.endTz ?: return tz
            if (tz == null || e.allDay) return tz
            if (!Zones.isValid(end)) {
                warnings.add("${e.title} : fuseau de fin inconnu « $end », exporté dans celui du début"); return tz
            }
            zones.add(end)
            return end
        }

        fun alarms(title: String, alerts: List<Int>) {
            for (m in alerts) lines += listOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:${Ics.escapeText(title)}",
                "TRIGGER:${Ics.formatAlertTrigger(m)}", "END:VALARM")
        }

        fun event(e: Event, series: Event?, uidSuffix: String, overridden: Set<Stamp> = emptySet()) {
            val tz = zoneOf(e)
            count++
            lines.add("BEGIN:VEVENT")
            val uid = series?.uid ?: e.uid ?: "${(series ?: e).id}@$uidSuffix"
            lines.add("UID:$uid")
            lines.add("DTSTAMP:$stamp")
            lines.add("SUMMARY:${Ics.escapeText(e.title)}")
            lines.add(timeLine("DTSTART", e.start, tz))
            if (e.allDay) lines.add(timeLine("DTEND", (e.end ?: e.start).plusDays(1), null))
            else e.end?.let { lines.add(timeLine("DTEND", it, endZoneOf(e, tz))) }
            e.rrule?.let { lines.add("RRULE:$it") }
            // an overridden occurrence is replaced by its override, never excluded too (Google drops such overrides)
            for (x in e.exdate.map { exdateStamp(e, it) }.distinct()) if (x !in overridden) lines.add(timeLine("EXDATE", x, tz))
            e.recurrenceAt?.let { lines.add(timeLine("RECURRENCE-ID", if (series != null) exdateStamp(series, it) else it, if (series != null) zoneOf(series) else tz)) }
            if (e.status in setOf("confirmed", "tentative", "cancelled")) lines.add("STATUS:${e.status!!.uppercase()}")
            e.location?.let { lines.add("LOCATION:${Ics.escapeText(it)}") }
            if (e.body.isNotBlank()) lines.add("DESCRIPTION:${Ics.escapeText(e.body.trim())}")
            for (raw in e.icalExtra) {
                val name = try {
                    Ics.parseLine(raw).name
                } catch (_: IcsSyntaxException) {
                    warnings.add("${e.title} : ligne ical_extra illisible ignorée : ${raw.take(60)}"); continue
                }
                if (name in IcsImport.CONSUMED || name == "BEGIN" || name == "END") {
                    warnings.add("${e.title} : ligne ical_extra $name ignorée (écrite par l'export)"); continue
                }
                lines.add(raw)
            }
            alarms(e.title, e.alerts)
            lines.add("END:VEVENT")
        }

        fun plain(uid: String, title: String, start: Instant, end: Instant?, description: String?, alerts: List<Int>) {
            count++
            lines += listOf("BEGIN:VEVENT", "UID:$uid", "DTSTAMP:$stamp", "SUMMARY:${Ics.escapeText(title)}", "DTSTART:${utc(start)}")
            if (end != null && end.isAfter(start)) lines.add("DTEND:${utc(end)}")
            description?.let { lines.add("DESCRIPTION:${Ics.escapeText(it)}") }
            alarms(title, alerts)
            lines.add("END:VEVENT")
        }

        fun text(name: String, year: Int): String {
            val head = arrayListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:$PRODID", "CALSCALE:GREGORIAN", "X-WR-CALNAME:${Ics.escapeText(name)}")
            zones.forEach { head += Zones.vtimezoneLines(it, year) }
            val sb = StringBuilder()
            (head + lines + "END:VCALENDAR").forEach { sb.append(Ics.fold(it)) }
            return sb.toString()
        }
    }

    /** EXDATE follows DTSTART's form: a date alone on a timed series is its occurrence at the series' start time. */
    private fun exdateStamp(series: Event, x: Stamp): Stamp =
        if (x.allDay && !series.allDay) Stamp(x.date, series.start.time) else if (!x.allDay && series.allDay) Stamp(x.date) else x

    private fun sorted(events: List<Event>) = events.sortedWith(compareBy({ it.start.toString() }, { it.title }, { it.id }))

    /**
     * One `.ics`: the events of [calendarId] (null: every calendar), plus the dated [notes] and [activities]
     * (pass them only when "Inclure les activités et les notes datées" is ticked).
     */
    fun exportCalendar(
        events: List<Event>, settings: CalendarSettings, calendarId: String?, now: Instant,
        notes: List<Note> = emptyList(), activities: List<Note> = emptyList(), name: String? = null,
    ): ExportResult {
        val w = Writer(now)
        val byId = events.associateBy { it.id }
        val chosen = events.filter { !it.note.conflict && (calendarId == null || settings.calendar(it.calendar).id == calendarId) }
        val overridden = HashMap<String, MutableSet<Stamp>>()
        for (x in chosen) {
            val series = x.recurrenceOf?.let { byId[it] } ?: continue
            val at = x.recurrenceAt ?: continue
            overridden.getOrPut(series.id) { HashSet() }.add(exdateStamp(series, at))
        }
        for (e in sorted(chosen)) w.event(e, e.recurrenceOf?.let { byId[it] }, "scriptorium", overridden[e.id].orEmpty())
        if (notes.isNotEmpty()) {
            val (dated, problems) = AgendaIndex.datedNotes(notes)
            w.warnings += problems
            val datedIds = dated.map { it.id }.toSet()
            for (e in sorted(dated)) w.event(e, null, "scriptorium-note")
            for (n in notes.sortedWith(compareBy({ it.reminder ?: 0L }, { it.id }))) {
                val r = n.reminder ?: continue
                if (n.conflict || n.id in datedIds) continue
                w.plain("${n.id}@scriptorium-note", n.title, Instant.ofEpochMilli(r), null, n.body.trim().ifEmpty { null }, listOf(0))
            }
        }
        for (n in activities.sortedWith(compareBy({ it.activity?.start ?: 0L }, { it.id }))) {
            val info = n.activity ?: continue
            if (n.conflict) continue
            val start = Instant.ofEpochMilli(info.start)
            w.plain("${n.id}@scriptorium-activite", n.title, start, start.plusSeconds(Math.max(0L, info.elapsed)),
                AgendaIndex.activitySubtitle(info.distance, info.elapsed), emptyList())
        }
        val title = name ?: if (calendarId != null) settings.calendar(calendarId).name else "Agenda"
        return ExportResult(w.text(title, LocalDateTime.ofInstant(now, ZoneOffset.UTC).year), w.count, w.warnings)
    }

    /** A `.zip` with one `.ics` per calendar (`<slug>.ics`), plus `activites.ics` and `notes.ics` when given. */
    fun exportAll(
        events: List<Event>, settings: CalendarSettings, now: Instant,
        notes: List<Note> = emptyList(), activities: List<Note> = emptyList(),
    ): Pair<ByteArray, List<String>> {
        val buffer = ByteArrayOutputStream()
        val warnings = ArrayList<String>()
        val used = HashSet<String>()
        ZipOutputStream(buffer).use { zip ->
            fun put(base: String, r: ExportResult) {
                var name = base
                var n = 2
                while (name in used) name = "$base-${n++}"
                used.add(name)
                warnings += r.warnings
                val entry = ZipEntry("$name.ics")
                entry.time = now.toEpochMilli()
                zip.putNextEntry(entry)
                zip.write(r.text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            for (c in settings.calendars) put(NoteFormat.slug(c.name), exportCalendar(events, settings, c.id, now))
            if (settings.calendars.isEmpty()) put("agenda", exportCalendar(events, settings, null, now))
            if (notes.isNotEmpty()) put("notes", exportCalendar(emptyList(), settings, null, now, notes = notes, name = "Notes"))
            if (activities.isNotEmpty()) put("activites", exportCalendar(emptyList(), settings, null, now, activities = activities, name = "Activités"))
        }
        return buffer.toByteArray() to warnings
    }
}
