package app.memento.agenda

import app.memento.Note
import app.memento.NoteFormat
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

/**
 * A picked file: its name gives the calendar name when the file has none. [fromArchive]: one `.ics` of a `.zip`
 * (Google's export of several calendars), which makes it a calendar export ([CalendarPreview.calendarExport]).
 */
class IcsSource(val name: String, val fromArchive: Boolean = false, val open: () -> InputStream) {
    companion object {
        fun fromBytes(name: String, data: ByteArray, fromArchive: Boolean = false) = IcsSource(name, fromArchive) { ByteArrayInputStream(data) }
        fun fromFile(file: java.io.File) = IcsSource(file.name) { file.inputStream() }
    }
}

/** A time of the ICS: a date, a floating or zoned wall time, or a UTC instant. */
data class TimeValue(
    val kind: Kind,
    val date: LocalDate? = null,
    val wall: LocalDateTime? = null,
    val instant: Instant? = null,
    val tz: String? = null,
    val unresolved: Boolean = false,
) {
    enum class Kind { DATE, FLOATING, ZONED, UTC }
}

/** A RECURRENCE-ID override; [event] null when the occurrence is cancelled. */
data class Override(val uid: String?, val recurrenceId: TimeValue, val event: Event?)

/** One event of a small import as the preview lists it ([IcsImport.whenText] for [whenText]). */
data class ImportItem(val title: String, val whenText: String, val location: String?)

/** What the preview shows for one calendar (one `.ics`). */
data class CalendarPreview(
    var name: String,
    val source: String,
    /** Files that would be created (singles, series, rdates), cancelled ones excluded. */
    var events: Int = 0,
    var series: Int = 0,
    var exceptions: Int = 0,
    var cancelled: Int = 0,
    /** Other components, unusable alarms, RDATE periods. */
    var ignored: Int = 0,
    /** Events whose TZID could not be resolved (the device zone was used). */
    var unresolvedZones: Int = 0,
    var first: LocalDate? = null,
    var last: LocalDate? = null,
    /** Same UID already in the target calendar ([IcsImport.preview]). */
    var duplicates: Int = 0,
    /** Malformed events and lines, skipped (French). */
    val errors: MutableList<String> = ArrayList(),
    /**
     * Where the events go by default ([IcsImport.suggestedCalendar]): the default calendar, or for a calendar
     * export the existing calendar of the same name, else null (a new one).
     */
    var suggestedCalendar: String? = null,
    /** A real calendar export (X-WR-CALNAME, more than [IcsImport.EXPORT_MIN_EVENTS] events, or from a `.zip`). */
    var calendarExport: Boolean = false,
    /** Each event when there are at most [IcsImport.LISTED_MAX], else null. */
    var items: List<ImportItem>? = null,
)

class ParsedCalendar(
    val name: String,
    val source: String,
    /** Singles and series, uid kept, no id/path yet. */
    val events: MutableList<Event> = ArrayList(),
    val overrides: MutableList<Override> = ArrayList(),
    var preview: CalendarPreview = CalendarPreview(name, source),
)

data class ImportTarget(
    /** An existing calendar; null = a new one. */
    val calendarId: String? = null,
    /** New calendar: its name (default: X-WR-CALNAME, else the file's name). */
    val name: String? = null,
    /** New calendar: its colour (default: the next free palette colour). */
    val color: String? = null,
    val skip: Boolean = false,
)

enum class DuplicatePolicy { SKIP, UPDATE, KEEP }

data class ImportOptions(
    /** By index in the parsed list; missing = [IcsImport.suggestedCalendar], a new calendar when null. */
    val targets: Map<Int, ImportTarget> = emptyMap(),
    /** "Depuis le …": events ending before are left out. */
    val since: LocalDate? = null,
    val duplicates: DuplicatePolicy = DuplicatePolicy.UPDATE,
)

data class CalendarReport(
    val name: String,
    val calendarId: String,
    var created: Int = 0,
    var series: Int = 0,
    var exceptions: Int = 0,
    var updated: Int = 0,
    var skippedDuplicates: Int = 0,
    var beforeCutoff: Int = 0,
    val cancelled: Int = 0,
    val ignored: Int = 0,
    val unresolvedZones: Int = 0,
    val errors: List<String> = emptyList(),
)

data class ImportReport(
    val importId: String,
    val calendars: MutableList<CalendarReport> = ArrayList(),
    val newCalendars: MutableList<String> = ArrayList(),
) {
    val created: Int get() = calendars.sumOf { it.created }
    val ignored: Int get() = calendars.sumOf { it.ignored + it.cancelled + it.errors.size }

    /** "812 événements, 37 séries, 3 ignorés" (+ updates and skipped duplicates when any). */
    fun summary(): String {
        fun plural(n: Int, one: String, many: String) = "$n ${if (n <= 1) one else many}"
        val parts = arrayListOf(plural(created, "événement", "événements"), plural(calendars.sumOf { it.series }, "série", "séries"))
        val updated = calendars.sumOf { it.updated }
        if (updated > 0) parts.add(plural(updated, "mis à jour", "mis à jour"))
        val dup = calendars.sumOf { it.skippedDuplicates }
        if (dup > 0) parts.add(plural(dup, "doublon ignoré", "doublons ignorés"))
        parts.add(plural(ignored, "ignoré", "ignorés"))
        return parts.joinToString(", ")
    }
}

data class UndoReport(val deleted: Int, val exdatesRemoved: Int, val calendarsRemoved: List<String>)

/**
 * ICS import (README, "Agenda", import), same mapping as Python's `core/agenda/ics_import.py`:
 * [read] parses `.ics` files and `.zip`s of them (no write) -> [preview] -> [apply] writes the files (each with
 * `import: <id>`) and the new calendars -> [undo] deletes them again.
 *
 * - SUMMARY -> title; LOCATION ("null", "undefined", "None", blank: none); UID -> uid; STATUS TENTATIVE/CONFIRMED -> status; CANCELLED: not imported (a
 *   cancelled override becomes an exdate of its series).
 * - DTSTART/DTEND/DURATION: VALUE=DATE -> all-day (exclusive DTEND -> inclusive end); TZID -> wall time + IANA zone
 *   ([Zones.resolveTzid]; unresolved -> device zone, counted); `Z` -> wall time of the device zone + that zone;
 *   neither -> floating. A DTEND with another TZID than DTSTART's is kept as `end_tz` (its wall time there, no
 *   conversion); any other DTEND (UTC, or a zone on a start without one) is converted to the start's zone.
 * - RRULE verbatim; EXDATE converted to the series' form; RDATE: one extra single event per date; RECURRENCE-ID
 *   overrides -> exception files + exdate on the series.
 * - VALARM relative to the start (not EMAIL) -> alerts; others ignored (counted). A new event also gets the
 *   default reminders ([CalendarSettings.defaultAlerts]); updating an existing one keeps the reminders set here.
 * - DESCRIPTION -> body (HTML -> text); X-GOOGLE-CONFERENCE not in the description -> first line "Visio : …";
 *   without it, a meeting link of the description ([Links.meetingLink]: Proton Meet, Meet, Teams, Zoom) not already on
 *   its first line -> "Visio : …"; else a URL not in the description -> "Lien : …"; ORGANIZER/ATTENDEE ->
 *   "Participants :" at the end.
 * - Target: a mail invitation goes to the default calendar; only a calendar export ([CalendarPreview.calendarExport])
 *   proposes a calendar named after it (X-WR-CALNAME, else the file name).
 * - Every other VEVENT property verbatim in `ical_extra` (DTSTAMP, X-ALT-DESC excepted).
 */
object IcsImport {
    val CONSUMED = setOf(
        "UID", "SUMMARY", "DESCRIPTION", "LOCATION", "STATUS", "DTSTART", "DTEND", "DURATION", "RRULE",
        "EXDATE", "RDATE", "RECURRENCE-ID", "DTSTAMP", "X-ALT-DESC",
    )
    const val UNTITLED = "(Sans titre)"
    /** More events than this make a file without X-WR-CALNAME a calendar export. */
    const val EXPORT_MIN_EVENTS = 20
    /** Up to this many events, the preview lists each one. */
    const val LISTED_MAX = 5
    private val NO_VALUE = setOf("null", "undefined", "none")

    /** A LOCATION as written by the sender; the placeholders some generators write for "none" are no location. */
    fun cleanLocation(raw: String?): String? = raw?.trim()?.takeUnless { it.isEmpty() || it.lowercase() in NO_VALUE }

    /** A `.zip` becomes one source per `.ics` inside (Google's export); other files pass through. */
    fun expandSources(sources: List<IcsSource>): List<IcsSource> {
        val out = ArrayList<IcsSource>()
        for (src in sources) {
            if (!src.name.lowercase().endsWith(".zip")) { out.add(src); continue }
            val entries = ArrayList<Pair<String, ByteArray>>()
            ZipInputStream(src.open()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (!e.isDirectory && e.name.lowercase().endsWith(".ics")) entries.add(e.name to zip.readBytes())
                }
            }
            for ((name, data) in entries.sortedBy { it.first }) out.add(IcsSource.fromBytes(name.substringAfterLast('/'), data, fromArchive = true))
        }
        return out
    }

    // ---- times ----

    private fun parseValue(text: String, isDate: Boolean): TimeValue {
        val t = text.trim()
        fun bad(): Nothing = throw IllegalArgumentException("date-heure illisible : '$text'")
        try {
            if (isDate || t.length == 8) {
                if (t.length < 8) bad()
                return TimeValue(TimeValue.Kind.DATE, date = LocalDate.of(t.substring(0, 4).toInt(), t.substring(4, 6).toInt(), t.substring(6, 8).toInt()))
            }
            if ((t.length != 15 && t.length != 16) || t[8] != 'T') bad()
            val utc = t.endsWith("Z")
            if (t.length == 16 && !utc) bad()
            val wall = LocalDateTime.of(t.substring(0, 4).toInt(), t.substring(4, 6).toInt(), t.substring(6, 8).toInt(),
                t.substring(9, 11).toInt(), t.substring(11, 13).toInt(), t.substring(13, 15).toInt())
            return if (utc) TimeValue(TimeValue.Kind.UTC, instant = wall.toInstant(ZoneOffset.UTC)) else TimeValue(TimeValue.Kind.FLOATING, wall = wall)
        } catch (e: NumberFormatException) {
            bad()
        } catch (e: java.time.DateTimeException) {
            bad()
        }
    }

    private class Context(val deviceZone: ZoneId, val vtimezones: Map<String, Zones.VTimezoneInfo>) {
        private val cache = HashMap<String, String?>()
        fun resolve(tzid: String): String? = cache.getOrPut(tzid) { Zones.resolveTzid(tzid, vtimezones[tzid]) }

        fun times(line: ContentLine): List<TimeValue> {
            val isDate = (line.param("VALUE") ?: "").uppercase() == "DATE"
            val tzid = line.param("TZID")
            return Ics.splitList(line.value).map { part ->
                if ('/' in part) throw IllegalArgumentException("période (RDATE)")
                val v = parseValue(part, isDate)
                if (v.kind == TimeValue.Kind.FLOATING && tzid != null) {
                    val resolved = resolve(tzid)
                    v.copy(kind = TimeValue.Kind.ZONED, tz = resolved ?: deviceZone.id, unresolved = resolved == null)
                } else v
            }
        }
    }

    private fun instantOf(tv: TimeValue, device: ZoneId): Instant = when (tv.kind) {
        TimeValue.Kind.UTC -> tv.instant!!
        TimeValue.Kind.ZONED -> Zones.toInstant(tv.wall!!, Zones.zone(tv.tz!!))
        else -> Zones.toInstant(tv.wall!!, device)
    }

    /** A time of the ICS in the frame of an event (a date for all-day; else the wall time of its zone). */
    fun toEventStamp(tv: TimeValue, allDay: Boolean, tz: String?, device: ZoneId): Stamp {
        if (tv.kind == TimeValue.Kind.DATE) return Stamp(tv.date!!)
        if (allDay) {
            if (tv.kind == TimeValue.Kind.UTC) return Stamp(Zones.toWall(tv.instant!!, device).toLocalDate())
            return Stamp(tv.wall!!.toLocalDate())
        }
        if (tv.kind == TimeValue.Kind.FLOATING) return Stamp.of(tv.wall!!)
        val target = tz ?: device.id
        if (tv.kind == TimeValue.Kind.ZONED && tv.tz == target) return Stamp.of(tv.wall!!)
        return Stamp.of(Zones.toWall(instantOf(tv, device), Zones.zone(target)))
    }

    // ---- one VEVENT ----

    private fun vtimezoneSignature(comp: Component): Zones.VTimezoneInfo {
        var std: Int? = null
        var dst: Int? = null
        var dstMonth: Int? = null
        var stdMonth: Int? = null
        for (child in comp.children) {
            val to = child.first("TZOFFSETTO") ?: continue
            val v = to.value.trim()
            val sign = if (v.startsWith("-")) -1 else 1
            val digits = v.trimStart('+', '-')
            val minutes = sign * (digits.substring(0, 2).toInt() * 60 + (digits.substring(2, 4).ifEmpty { "0" }).toInt())
            var month: Int? = null
            child.first("RRULE")?.let { r ->
                for (part in r.value.uppercase().split(";")) if (part.startsWith("BYMONTH=")) month = part.substring(8).split(",")[0].toInt()
            }
            if (child.name == "STANDARD") { std = minutes; stdMonth = month } else if (child.name == "DAYLIGHT") { dst = minutes; dstMonth = month }
        }
        if (std == null) { std = dst; dst = null; dstMonth = null }
        return Zones.VTimezoneInfo(std, dst, dstMonth, stdMonth)
    }

    private fun people(comp: Component): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (line in comp.all("ORGANIZER") + comp.all("ATTENDEE")) {
            val value = line.value.trim()
            val email = if (value.lowercase().startsWith("mailto:")) value.substring(7) else value
            val name = (line.param("CN") ?: "").trim()
            if (!seen.add(email.lowercase())) continue
            var text = if (name.isNotEmpty() && name != email) "$name <$email>" else email
            if (line.name == "ORGANIZER") text += " (organisateur)"
            out.add("- $text")
        }
        return out
    }

    fun buildBody(comp: Component): String {
        var desc = comp.first("DESCRIPTION")?.let { Ics.unescapeText(it.value) } ?: ""
        if (desc.isBlank()) {
            val alt = comp.first("X-ALT-DESC")
            if (alt != null && "html" in (alt.param("FMTTYPE") ?: "").lowercase()) desc = Ics.htmlToText(Ics.unescapeText(alt.value))
        } else if (Ics.looksHtml(desc)) {
            desc = Ics.htmlToText(desc)
        }
        desc = desc.replace("\r\n", "\n").trim()
        val parts = ArrayList<String>()
        val conference = comp.first("X-GOOGLE-CONFERENCE")?.value?.trim().orEmpty()
        val url = comp.first("URL")?.value?.trim().orEmpty()
        val described = if (conference.isEmpty()) Links.meetingLink(desc) else null
        if (conference.isNotEmpty() && conference !in desc) parts.add("Visio : $conference")
        else if (described != null && described !in desc.lineSequence().first()) parts.add("Visio : $described")
        else if (url.isNotEmpty() && url !in desc) parts.add("Lien : $url")
        if (desc.isNotEmpty()) parts.add(desc)
        val people = people(comp)
        if (people.isNotEmpty() && "Participants :" !in desc) parts.add("Participants :\n" + people.joinToString("\n"))
        return parts.joinToString("\n\n")
    }

    private fun alerts(comp: Component, preview: CalendarPreview): List<Int> {
        val out = ArrayList<Int>()
        for (alarm in comp.children) {
            if (alarm.name != "VALARM") { preview.ignored++; continue }
            val action = alarm.first("ACTION")?.value?.trim()?.uppercase() ?: "DISPLAY"
            val trigger = alarm.first("TRIGGER")
            if (action == "EMAIL" || trigger == null || (trigger.param("VALUE") ?: "").uppercase() == "DATE-TIME" ||
                (trigger.param("RELATED") ?: "START").uppercase() != "START"
            ) { preview.ignored++; continue }
            val (days, seconds) = try { Ics.parseDuration(trigger.value) } catch (_: IllegalArgumentException) { preview.ignored++; continue }
            val total = days * 86400 + seconds
            val minutes = (if (total < 0) -(Math.abs(total) / 60) else total / 60).toInt()
            if (minutes !in out) out.add(minutes)
        }
        return out
    }

    private sealed class Mapped {
        class Single(val event: Event, val rdates: List<Stamp>) : Mapped()
        class Over(val override: Override) : Mapped()
        object Cancelled : Mapped()
    }

    private fun mapEvent(comp: Component, ctx: Context, preview: CalendarPreview, now: Long): Mapped {
        val device = ctx.deviceZone
        val dtstart = comp.first("DTSTART") ?: throw IllegalArgumentException("DTSTART manquant")
        val startTv = ctx.times(dtstart).firstOrNull() ?: throw IllegalArgumentException("DTSTART vide")
        var unresolved = startTv.unresolved
        val allDay: Boolean
        val tz: String?
        val start: Stamp
        when (startTv.kind) {
            TimeValue.Kind.DATE -> { allDay = true; tz = null; start = Stamp(startTv.date!!) }
            TimeValue.Kind.UTC -> {
                // A single UTC event takes the device zone (readable file); a UTC series stays in UTC, or its
                // occurrences would move by an hour across daylight saving and its UTC EXDATEs would stop matching.
                allDay = false
                tz = if (comp.first("RRULE") != null) "UTC" else device.id
                start = Stamp.of(Zones.toWall(startTv.instant!!, Zones.zone(tz)))
            }
            TimeValue.Kind.ZONED -> { allDay = false; tz = startTv.tz; start = Stamp.of(startTv.wall!!) }
            TimeValue.Kind.FLOATING -> { allDay = false; tz = null; start = Stamp.of(startTv.wall!!) }
        }

        var end: Stamp? = null
        var endTz: String? = null
        val dtend = comp.first("DTEND")
        val duration = comp.first("DURATION")
        if (dtend != null) {
            val endTv = ctx.times(dtend).firstOrNull() ?: throw IllegalArgumentException("DTEND vide")
            unresolved = unresolved || endTv.unresolved
            if (allDay) {
                var last = toEventStamp(endTv, true, null, device)
                if (endTv.kind == TimeValue.Kind.DATE) last = last.plusDays(-1)
                end = if (last > start) last else null
            } else if (startTv.kind == TimeValue.Kind.ZONED && endTv.kind == TimeValue.Kind.ZONED && endTv.tz != tz) {
                // a flight: DTEND in the arrival zone stays a wall time of that zone
                if (instantOf(endTv, device) > instantOf(startTv, device)) { end = Stamp.of(endTv.wall!!); endTz = endTv.tz }
            } else if (endTv.kind != TimeValue.Kind.DATE) {
                val e = toEventStamp(endTv, false, tz, device)
                end = if (e > start) e else null
            }
        } else if (duration != null) {
            val (days, seconds) = Ics.parseDuration(duration.value)
            if (allDay) {
                end = if (days > 1) start.plusDays(days - 1) else null
            } else {
                val e = Stamp.of(start.dateTime().plusDays(days).plusSeconds(seconds))
                end = if (e > start) e else null
            }
        }

        val exdate = ArrayList<Stamp>()
        for (line in comp.all("EXDATE")) {
            for (tv in ctx.times(line)) {
                unresolved = unresolved || tv.unresolved
                val s = toEventStamp(tv, allDay, tz, device)
                if (s !in exdate) exdate.add(s)
            }
        }
        val statusRaw = comp.first("STATUS")?.value?.trim()?.uppercase() ?: ""
        val status = mapOf("TENTATIVE" to "tentative", "CONFIRMED" to "confirmed")[statusRaw]
        val title = comp.first("SUMMARY")?.let { Ics.unescapeText(it.value).trim() } ?: ""
        val location = cleanLocation(comp.first("LOCATION")?.let { Ics.unescapeText(it.value) })
        val uid = comp.first("UID")?.value?.trim()?.ifEmpty { null }
        val body = buildBody(comp)
        val note = Note(path = "", id = "", title = title.ifEmpty { UNTITLED }, body = body, created = now, updated = now)
        val event = Event(
            note = note, start = start, end = end, tz = tz, endTz = endTz, rrule = comp.first("RRULE")?.value?.trim(),
            exdate = exdate, alerts = alerts(comp, preview), location = location, status = status, uid = uid,
            icalHash = EventFormat.fnv32(body), icalExtra = comp.lines.filter { it.name !in CONSUMED }.map { it.raw },
        )
        if (unresolved) preview.unresolvedZones++

        comp.first("RECURRENCE-ID")?.let { rid ->
            val ridTv = ctx.times(rid).firstOrNull() ?: throw IllegalArgumentException("RECURRENCE-ID vide")
            return Mapped.Over(Override(uid, ridTv, if (statusRaw == "CANCELLED") null else event))
        }
        if (statusRaw == "CANCELLED") return Mapped.Cancelled
        val rdates = ArrayList<Stamp>()
        for (line in comp.all("RDATE")) {
            val values = try { ctx.times(line) } catch (_: IllegalArgumentException) { preview.ignored++; continue }
            values.forEach { rdates.add(toEventStamp(it, allDay, tz, device)) }
        }
        return Mapped.Single(event, rdates)
    }

    private fun rdateCopy(e: Event, s: Stamp): Event {
        val end = e.end?.let { end ->
            if (e.endTz != null && e.tz != null && !s.allDay) {
                val zone = Zones.zone(e.tz)
                val endZone = Zones.zone(e.endTz)
                val span = java.time.Duration.between(Zones.toInstant(e.start.dateTime(), zone), Zones.toInstant(end.dateTime(), endZone))
                Stamp.of(Zones.toWall(Zones.toInstant(s.dateTime(), zone).plus(span), endZone))
            } else s.plusSeconds(java.time.Duration.between(e.start.dateTime(), end.dateTime()).seconds)
        }
        return e.copy(start = s, end = end, rrule = null, exdate = emptyList(), uid = e.uid?.let { "$it#$s" })
    }

    /** Every VCALENDAR of one `.ics` (usually one). */
    fun parseCalendar(source: IcsSource, device: ZoneId, now: Long = System.currentTimeMillis()): List<ParsedCalendar> {
        val out = ArrayList<ParsedCalendar>()
        val errors = ArrayList<String>()
        var stem = source.name.substringAfterLast('/')
        if (stem.lowercase().endsWith(".ics")) stem = stem.dropLast(4)
        val calendars = source.open().use { Ics.readCalendars(it, errors) }
        if (calendars.isEmpty()) {
            val p = ParsedCalendar(stem, source.name)
            p.preview.errors.addAll(errors.ifEmpty { listOf("aucun BEGIN:VCALENDAR") })
            return listOf(p)
        }
        calendars.forEachIndexed { index, cal ->
            val nameLine = cal.first("X-WR-CALNAME")
            val name = nameLine?.value?.takeIf { it.isNotBlank() }?.let { Ics.unescapeText(it).trim() } ?: stem
            val parsed = ParsedCalendar(name, source.name)
            val preview = parsed.preview
            if (index == 0) preview.errors.addAll(errors)
            if (cal.name != "VCALENDAR") {
                preview.errors.add("composant ${cal.name} hors de VCALENDAR ignoré")
                out.add(parsed)
                return@forEachIndexed
            }
            val vtz = HashMap<String, Zones.VTimezoneInfo>()
            for (c in cal.children) {
                if (c.name == "VTIMEZONE") {
                    val tzid = c.first("TZID") ?: continue
                    try {
                        vtz[tzid.value.trim()] = vtimezoneSignature(c)
                    } catch (e: RuntimeException) {
                        if (e !is NumberFormatException && e !is IndexOutOfBoundsException) throw e
                        preview.errors.add("ligne ${c.lineNumber} : VTIMEZONE illisible")
                    }
                }
            }
            val ctx = Context(device, vtz)
            for (c in cal.children) {
                if (c.name == "VTIMEZONE") continue
                if (c.name != "VEVENT") { preview.ignored++; continue }
                val mapped = try {
                    mapEvent(c, ctx, preview, now)
                } catch (e: RuntimeException) {
                    if (e !is IllegalArgumentException && e !is IndexOutOfBoundsException) throw e
                    val uid = c.first("UID")
                    preview.errors.add("ligne ${c.lineNumber}" + (uid?.let { " (UID ${it.value.trim()})" } ?: "") + " : ${e.message}")
                    continue
                }
                when (mapped) {
                    is Mapped.Over -> parsed.overrides.add(mapped.override)
                    is Mapped.Cancelled -> preview.cancelled++
                    is Mapped.Single -> {
                        parsed.events.add(mapped.event)
                        mapped.rdates.filter { it != mapped.event.start }.forEach { parsed.events.add(rdateCopy(mapped.event, it)) }
                    }
                }
            }
            summarise(parsed, device)
            preview.calendarExport = nameLine?.value?.isNotBlank() == true || source.fromArchive || preview.events > EXPORT_MIN_EVENTS
            out.add(parsed)
        }
        return out
    }

    private fun summarise(p: ParsedCalendar, device: ZoneId) {
        val pv = p.preview
        pv.events = p.events.size
        pv.series = p.events.count { it.isSeries }
        val seriesUids = p.events.filter { it.isSeries && it.uid != null }.map { it.uid }.toSet()
        pv.exceptions = p.overrides.count { it.event != null && it.uid in seriesUids }
        pv.events += p.overrides.count { it.event != null && it.uid !in seriesUids }
        pv.cancelled += p.overrides.count { it.event == null }
        val days = p.events.map { it.start.date } + p.events.mapNotNull { it.end?.date } + p.overrides.mapNotNull { it.event?.start?.date }
        if (days.isNotEmpty()) {
            pv.first = days.minOrNull(); pv.last = days.maxOrNull()
        }
        val listed = p.events + p.overrides.mapNotNull { o -> o.event?.takeIf { o.uid !in seriesUids } }
        pv.items = if (listed.isEmpty() || listed.size > LISTED_MAX) null
        else listed.sortedWith(compareBy({ it.start.date }, { it.start.toString() })).map { ImportItem(it.title, whenText(it, device), it.location) }
    }

    private fun day(d: LocalDate) = "${AgendaText.weekdayShort(d.dayOfWeek)} ${d.dayOfMonth} ${AgendaText.MONTHS_SHORT[d.monthValue - 1]} ${d.year}"
    private fun hm(t: LocalDateTime) = AgendaText.time(t.toLocalTime(), true)

    /**
     * "lun. 26 oct. 2026, 17:00–18:00 (Zurich)", in the event's zone, + " · soit lun. 26 oct. 2026, 12:00–13:00
     * (New York)" when the device's clock reads otherwise; a flight "… 10:00 (Paris) → 13:00 (New York)"; all-day
     * "du … au …"; " · série" for a series. Same text as Python's `ics_import.when_text`.
     */
    fun whenText(e: Event, device: ZoneId): String {
        val s = e.start
        var text: String
        if (s.allDay) {
            val last = e.end?.date
            text = if (last == null || last == s.date) day(s.date) else "du ${day(s.date)} au ${day(last)}"
        } else {
            val start = s.dateTime()
            val end = e.end?.dateTime()
            fun endText(at: LocalDateTime, from: LocalDateTime) = (if (at.toLocalDate() != from.toLocalDate()) day(at.toLocalDate()) + ", " else "") + hm(at)
            text = "${day(start.toLocalDate())}, ${hm(start)}"
            val tz = e.tz
            val endTz = e.endTz
            if (tz != null && endTz != null && end != null) {
                text += " (${AgendaText.city(tz)}) → ${endText(end, start)} (${AgendaText.city(endTz)})"
            } else {
                if (end != null) text += "–" + endText(end, start)
                if (tz != null) text += " (${AgendaText.city(tz)})"
            }
            if (tz != null && Zones.isValid(tz) && tz != device.id) {
                val here = Zones.toWall(Zones.toInstant(start, Zones.zone(tz)), device)
                if (here != start) {
                    text += " · soit ${day(here.toLocalDate())}, ${hm(here)}"
                    if (end != null) {
                        val endZone = Zones.zone(endTz?.takeIf { Zones.isValid(it) } ?: tz)
                        text += "–" + endText(Zones.toWall(Zones.toInstant(end, endZone), device), here)
                    }
                    text += " (${AgendaText.city(device.id)})"
                }
            }
        }
        if (e.isSeries) text += " · série"
        return text
    }

    /** All calendars of the picked files (zips expanded), parsed, nothing written. */
    fun read(sources: List<IcsSource>, device: ZoneId, now: Long = System.currentTimeMillis()): List<ParsedCalendar> =
        expandSources(sources).flatMap { parseCalendar(it, device, now) }

    /**
     * Where [p] goes when the user does not choose: the default calendar (a mail invitation is not a calendar);
     * for a calendar export, the existing calendar of the same name, else null (a new one named after it).
     */
    fun suggestedCalendar(p: ParsedCalendar, settings: CalendarSettings): String? =
        if (p.preview.calendarExport) CalendarSettings.byName(p.name, settings)?.id
        else settings.defaultCalendar().id.takeIf { id -> settings.calendars.any { it.id == id } }

    /** Fills `suggestedCalendar` ([suggestedCalendar]) and `duplicates` (same UID already there). */
    fun preview(parsed: List<ParsedCalendar>, existing: List<Event>, settings: CalendarSettings): List<CalendarPreview> =
        parsed.map { p ->
            val pv = p.preview
            val target = suggestedCalendar(p, settings)
            pv.suggestedCalendar = target
            pv.duplicates = 0
            if (target != null) {
                val uids = existing.filter { it.uid != null && settings.calendar(it.calendar).id == target && !it.isException }.map { it.uid }.toSet()
                pv.duplicates = p.events.count { it.uid in uids }
            }
            pv
        }

    fun newImportId(now: Long = System.currentTimeMillis()): String =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").format(LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC)) +
            "-" + NoteFormat.generateId()

    private fun hasOccurrenceSince(e: Event, since: LocalDate, device: ZoneId): Boolean {
        val last = e.end ?: e.start
        if (!e.isSeries) return !last.date.isBefore(since)
        val span = java.time.temporal.ChronoUnit.DAYS.between(e.start.date, last.date)
        return try {
            var steps = 0
            for (s in RecurrenceExpander.seriesStarts(e, device)) {
                if (!s.date.plusDays(span).isBefore(since) || steps++ > RecurrenceExpander.MAX_ITERATIONS) return true
            }
            false
        } catch (_: IllegalArgumentException) {
            true
        } catch (_: java.time.DateTimeException) {
            true
        } catch (_: AssertionError) {
            true
        }
    }

    /**
     * Writes the files of the import and `calendars.json` (when calendars are created). [existing]: the events of
     * `agenda/` now. Returns the report and the updated settings.
     */
    fun apply(
        store: AgendaStore, parsed: List<ParsedCalendar>, options: ImportOptions, existing: List<Event>,
        settings: CalendarSettings, device: ZoneId, importId: String = newImportId(), now: Long = System.currentTimeMillis(),
        takenIds: Set<String> = emptySet(), progress: ((Int, Int) -> Unit)? = null,
    ): Pair<ImportReport, CalendarSettings> {
        var cals = settings
        val report = ImportReport(importId)
        val total = parsed.sumOf { it.events.size + it.overrides.size }
        var done = 0
        val byId = LinkedHashMap<String, Event>().also { m -> existing.forEach { m[it.id] = it } }

        parsed.forEachIndexed { index, p ->
            val target = options.targets[index] ?: ImportTarget(calendarId = suggestedCalendar(p, settings))
            if (target.skip) return@forEachIndexed
            val calId: String
            if (target.calendarId != null && cals.calendars.any { it.id == target.calendarId }) {
                calId = target.calendarId
            } else {
                val name = (target.name ?: p.name).trim().ifEmpty { p.name }
                val cal = Calendar(CalendarSettings.uniqueCalendarId(name, cals), name, target.color ?: CalendarSettings.nextColor(cals), true, importId)
                cals = cals.copy(calendars = cals.calendars + cal)
                report.newCalendars.add(cal.id)
                calId = cal.id
            }
            val pv = p.preview
            val rep = CalendarReport(p.name, calId, cancelled = pv.cancelled, ignored = pv.ignored,
                unresolvedZones = pv.unresolvedZones, errors = pv.errors.toList())
            report.calendars.add(rep)
            // A new event gets its VALARMs plus the default reminders ("Rappels par défaut"), like one made here.
            fun withDefaults(e: Event): Event = e.copy(alerts = (cals.defaultAlerts(e.allDay) + e.alerts).distinct().sorted())
            val inTarget = byId.values.filter { cals.calendar(it.calendar).id == calId }
            val existingByKey = HashMap<Pair<String?, Stamp?>, Event>()
            inTarget.filter { it.uid != null }.forEach { existingByKey[it.uid to it.recurrenceAt] = it }
            val seriesByUid = HashMap<String, Event>()

            fun write(e: Event): Event = store.save(e, takenIds).also { byId[it.id] = it }

            for (raw in p.events) {
                done++
                if (options.since != null && !hasOccurrenceSince(raw, options.since, device)) { rep.beforeCutoff++; continue }
                val e = raw.copy(calendar = calId)
                val old = e.uid?.let { existingByKey[it to null] }
                if (old != null && options.duplicates == DuplicatePolicy.SKIP) {
                    rep.skippedDuplicates++
                    if (e.isSeries) seriesByUid[e.uid!!] = old
                } else if (old != null && options.duplicates == DuplicatePolicy.UPDATE) {
                    val keepBody = old.icalHash != null && old.icalHash == e.icalHash
                    val merged = e.copy(
                        note = old.note.copy(title = e.title, body = if (keepBody) old.body else e.body, updated = now),
                        calendar = old.calendar, importId = old.importId, alerts = old.alerts,
                    )
                    val saved = write(merged)
                    rep.updated++
                    if (saved.isSeries) seriesByUid[saved.uid!!] = saved
                } else {
                    val saved = write(withDefaults(e).copy(importId = importId))
                    rep.created++
                    if (saved.isSeries) {
                        rep.series++
                        if (saved.uid != null && saved.uid !in seriesByUid) seriesByUid[saved.uid] = saved
                    }
                }
                progress?.invoke(done, total)
            }

            for (ov in p.overrides) {
                done++
                var series = ov.uid?.let { seriesByUid[it] }
                if (series == null && ov.uid != null) series = inTarget.firstOrNull { it.uid == ov.uid && it.isSeries && !it.isException }
                if (series == null) {
                    ov.event?.let { ev ->
                        if (options.since == null || hasOccurrenceSince(ev, options.since, device)) {
                            write(withDefaults(ev).copy(calendar = calId, importId = importId)); rep.created++
                        } else rep.beforeCutoff++
                    }
                    continue
                }
                series = byId[series.id] ?: series
                val at = toEventStamp(ov.recurrenceId, series.allDay, series.tz, device)
                if (at !in series.exdate) {
                    series = write(series.copy(exdate = series.exdate + at))
                    seriesByUid[ov.uid!!] = series
                }
                val ev = ov.event ?: continue
                val exc = ev.copy(calendar = series.calendar, recurrenceOf = series.id, recurrenceAt = at)
                if (options.since != null && !hasOccurrenceSince(exc, options.since, device)) { rep.beforeCutoff++; continue }
                val old = existingByKey[ov.uid to at]
                if (old != null && options.duplicates == DuplicatePolicy.SKIP) {
                    rep.skippedDuplicates++
                } else if (old != null && options.duplicates == DuplicatePolicy.UPDATE) {
                    val keepBody = old.icalHash != null && old.icalHash == exc.icalHash
                    write(exc.copy(note = old.note.copy(title = exc.title, body = if (keepBody) old.body else exc.body, updated = now),
                        importId = old.importId, recurrenceOf = series.id, alerts = old.alerts))
                    rep.updated++
                } else {
                    write(withDefaults(exc).copy(importId = importId))
                    rep.exceptions++
                    rep.created++
                }
                progress?.invoke(done, total)
            }
        }
        if (report.newCalendars.isNotEmpty()) store.saveCalendars(cals)
        return report to cals
    }

    /**
     * "Annuler l'import": deletes the files carrying `import: <id>`, removes from the surviving series the
     * exdates of the deleted exception files, and the calendars the import created when nothing uses them.
     * Returns the report and the settings (saved when they changed).
     */
    fun undo(store: AgendaStore, importId: String, events: List<Event>, settings: CalendarSettings): Pair<UndoReport, CalendarSettings> {
        val doomed = events.filter { it.importId == importId }
        val doomedIds = doomed.map { it.id }.toSet()
        val survivors = LinkedHashMap<String, Event>().also { m -> events.filter { it.id !in doomedIds }.forEach { m[it.id] = it } }
        doomed.forEach { store.delete(it) }
        var exdatesRemoved = 0
        for (e in doomed) {
            val of = e.recurrenceOf ?: continue
            val at = e.recurrenceAt ?: continue
            val series = survivors[of] ?: continue
            if (at !in series.exdate) continue
            if (survivors.values.any { it.recurrenceOf == series.id && it.recurrenceAt == at }) continue
            survivors[series.id] = store.save(series.copy(exdate = series.exdate.filter { it != at }))
            exdatesRemoved++
        }
        val used = survivors.values.map { settings.calendar(it.calendar).id }.toSet()
        val keep = settings.calendars.filter { it.importId != importId || it.id in used }
        var out = settings
        var removed = emptyList<String>()
        if (keep.size != settings.calendars.size) {
            removed = settings.calendars.filter { it !in keep }.map { it.id }
            val cals = keep.ifEmpty { settings.calendars.take(1) }
            out = settings.copy(calendars = cals, default = if (settings.default in removed) cals[0].id else settings.default)
            store.saveCalendars(out)
        }
        return UndoReport(doomed.size, exdatesRemoved, removed) to out
    }
}
