package app.scriptorium.agenda

import app.scriptorium.CHECK_REGEX
import app.scriptorium.Note
import app.scriptorium.NoteFormat

/**
 * An event is a note: the agenda keys of its frontmatter, parsed into an [Event] and written back
 * (README, "Agenda"; Python `core/agenda/model.py`, same rules).
 *
 * ```
 * start: 2026-11-12T14:30        # a date alone means all-day
 * end: 2026-11-12T15:15          # all-day: last day INCLUDED
 * tz: Europe/Paris               # absent = floating (the device zone)
 * end_tz: America/New_York      # the end's own zone (a flight); absent = tz. Timed events with tz only
 * calendar: perso
 * rrule: FREQ=WEEKLY;BYDAY=TH
 * exdate: [2026-11-19T14:30]     # same form as start
 * alerts: [-15m, -1d]
 * location: …
 * status: confirmed              # confirmed | tentative | cancelled
 * uid: 0a1b2c@google.com
 * recurrence_of: <series id>     # an edited occurrence of a series…
 * recurrence_at: 2026-11-19T14:30  # …and the original start of that occurrence
 * import: <import id>            # every file an ICS import created
 * ical_hash: 1a2b3c4d            # hash of the body the import wrote (re-import: was the description edited?)
 * ical_extra:                    # ICS properties the app does not model, verbatim
 *   - "CATEGORIES:Santé"
 * ```
 * The same keys on a regular note (`start`, `end`, `tz`, `alerts`) make it a dated note.
 */
data class Event(
    /** Everything that is not an agenda key (id, path, title, body, created, attachments, unknown keys). */
    val note: Note,
    val start: Stamp,
    val end: Stamp? = null,
    val tz: String? = null,
    /** The end's own zone (`end_tz`): [end] is a wall time there. Only on timed events with [tz]; null = [tz]. */
    val endTz: String? = null,
    val calendar: String? = null,
    val rrule: String? = null,
    val exdate: List<Stamp> = emptyList(),
    /** Signed minutes relative to the start. */
    val alerts: List<Int> = emptyList(),
    val location: String? = null,
    val status: String? = null,
    val uid: String? = null,
    val recurrenceOf: String? = null,
    val recurrenceAt: Stamp? = null,
    val importId: String? = null,
    val icalHash: String? = null,
    val icalExtra: List<String> = emptyList(),
    /** Non-fatal problems met while reading the file (French), for the UI. */
    val warnings: List<String> = emptyList(),
) {
    val id: String get() = note.id
    val path: String get() = note.path
    val title: String get() = note.title
    val body: String get() = note.body
    val allDay: Boolean get() = start.allDay
    val isSeries: Boolean get() = !rrule.isNullOrEmpty()
    val isException: Boolean get() = !recurrenceOf.isNullOrEmpty()
    val cancelled: Boolean get() = status == "cancelled"

    fun withNote(transform: (Note) -> Note): Event = copy(note = transform(note))
}

/** A file that cannot be an event (unreadable `start`). The message is French, for the UI. */
class AgendaFormatException(message: String) : Exception(message)

object EventFormat {
    const val AGENDA_DIR = "agenda"
    val AGENDA_KEYS = listOf(
        "start", "end", "tz", "end_tz", "calendar", "rrule", "exdate", "alerts", "location", "status", "uid",
        "recurrence_of", "recurrence_at", "import", "ical_hash", "ical_extra",
    )
    private val KEY = Regex("""^([A-Za-z_][\w-]*):[ \t]*(.*)$""")

    private fun blocks(extra: List<String>): List<Pair<String?, MutableList<String>>> {
        val out = ArrayList<Pair<String?, MutableList<String>>>()
        for (line in extra) {
            val m = KEY.matchEntire(line)
            if (m != null && !line.startsWith(" ") && !line.startsWith("-")) out.add(m.groupValues[1] to mutableListOf(line))
            else if (out.isNotEmpty()) out.last().second.add(line)
            else out.add(null to mutableListOf(line))
        }
        return out
    }

    private fun scalar(block: List<String>): String = NoteFormat.unquote(KEY.matchEntire(block[0])!!.groupValues[2])

    private fun list(block: List<String>): List<String> {
        val first = KEY.matchEntire(block[0])!!.groupValues[2].trim()
        if (first.isNotEmpty()) return NoteFormat.parseList(first)
        return block.drop(1).map { it.trim() }.filter { it.startsWith("-") }.map { NoteFormat.unquote(it.substring(1).trim()) }
    }

    /** The agenda key blocks of a note's unknown keys, and the other lines (kept verbatim). */
    fun agendaValues(note: Note): Pair<Map<String, List<String>>, List<String>> {
        val found = LinkedHashMap<String, List<String>>()
        val rest = ArrayList<String>()
        for ((key, block) in blocks(note.extra)) {
            if (key != null && key in AGENDA_KEYS) found[key] = block else rest.addAll(block)
        }
        return found to rest
    }

    fun hasStart(note: Note): Boolean = blocks(note.extra).any { it.first == "start" }

    /**
     * Null when the note has no `start` (not in the calendar). Throws [AgendaFormatException] when `start` is
     * unreadable; smaller problems (bad end, alert, exdate) are dropped and listed in [Event.warnings].
     */
    fun fromNote(note: Note): Event? {
        val (found, rest) = agendaValues(note)
        val startBlock = found["start"] ?: return null
        val warnings = ArrayList<String>()
        val rawStart = scalar(startBlock)
        val start = try {
            Stamp.parse(rawStart)
        } catch (_: IllegalArgumentException) {
            throw AgendaFormatException("${note.path} : début illisible ('$rawStart')")
        }

        fun stamp(key: String, sameForm: Boolean = true): Stamp? {
            val raw = found[key]?.let { scalar(it) } ?: return null
            if (raw.isEmpty()) return null
            val value = try {
                Stamp.parse(raw)
            } catch (_: IllegalArgumentException) {
                warnings.add("$key illisible : '$raw'"); return null
            }
            if (sameForm && value.allDay != start.allDay) {
                warnings.add("$key ignoré : '$raw' n'a pas la forme du début"); return null
            }
            return value
        }

        fun text(key: String): String? = found[key]?.let { scalar(it) }?.ifEmpty { null }
        val tz = text("tz")
        var endTz = text("end_tz")
        if (endTz != null) when {
            start.allDay -> { warnings.add("end_tz ignoré : '$endTz' (événement sur la journée)"); endTz = null }
            tz == null -> { warnings.add("end_tz ignoré : '$endTz' (pas de fuseau tz pour le début)"); endTz = null }
            !Zones.isValid(endTz) -> warnings.add("fuseau de fin inconnu : '$endTz' (celui du début est utilisé)")
        }
        var end = stamp("end")
        if (end != null && endsBeforeStart(start, end, tz, endTz)) {
            warnings.add("fin avant le début ignorée : $end"); end = null
        }
        val exdate = ArrayList<Stamp>()
        found["exdate"]?.let { b ->
            for (raw in list(b)) try { exdate.add(Stamp.parse(raw)) } catch (_: IllegalArgumentException) { warnings.add("exdate illisible : '$raw'") }
        }
        val alerts = ArrayList<Int>()
        found["alerts"]?.let { b ->
            for (raw in list(b)) try { alerts.add(Alerts.parse(raw)) } catch (_: IllegalArgumentException) { warnings.add("rappel illisible : '$raw'") }
        }
        tz?.let { if (!Zones.isValid(it)) warnings.add("fuseau inconnu : '$it' (celui de l'appareil est utilisé)") }
        val rrule = text("rrule")?.let { if (it.uppercase().startsWith("RRULE:")) it.substring(6) else it }?.ifEmpty { null }
        return Event(
            note = note.copy(extra = rest),
            start = start, end = end, tz = tz, endTz = endTz, calendar = text("calendar"), rrule = rrule,
            exdate = exdate, alerts = alerts, location = text("location"), status = text("status")?.lowercase(),
            uid = text("uid"), recurrenceOf = text("recurrence_of"), recurrenceAt = stamp("recurrence_at", sameForm = false),
            importId = text("import"), icalHash = text("ical_hash"),
            icalExtra = found["ical_extra"]?.let { list(it) }.orEmpty(),
            warnings = warnings,
        )
    }

    /** With two known zones the instants are compared (Paris 14:00 -> New York 12:00 is fine), else the walls. */
    fun endsBeforeStart(start: Stamp, end: Stamp, tz: String?, endTz: String?): Boolean {
        if (!start.allDay && Zones.isValid(tz) && Zones.isValid(endTz)) {
            return Zones.toInstant(end.dateTime(), Zones.zone(endTz!!)) < Zones.toInstant(start.dateTime(), Zones.zone(tz!!))
        }
        return end < start
    }

    private fun icalLine(line: String): String =
        "  - \"" + line.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\""

    /** The agenda keys of the event, in the fixed order of the format. */
    fun agendaLines(e: Event): List<String> {
        val q = NoteFormat::yamlQuote
        val out = arrayListOf("start: ${e.start}")
        e.end?.let { out.add("end: $it") }
        e.tz?.let { out.add("tz: ${q(it, false)}") }
        e.endTz?.let { out.add("end_tz: ${q(it, false)}") }
        e.calendar?.let { out.add("calendar: ${q(it, false)}") }
        e.rrule?.let { out.add("rrule: ${q(it, false)}") }
        if (e.exdate.isNotEmpty()) out.add("exdate: [" + e.exdate.joinToString(", ") + "]")
        if (e.alerts.isNotEmpty()) out.add("alerts: [" + e.alerts.joinToString(", ") { Alerts.format(it) } + "]")
        e.location?.let { out.add("location: ${q(it, false)}") }
        e.status?.let { out.add("status: $it") }
        e.uid?.let { out.add("uid: ${q(it, false)}") }
        e.recurrenceOf?.let { out.add("recurrence_of: ${q(it, false)}") }
        e.recurrenceAt?.let { out.add("recurrence_at: $it") }
        e.importId?.let { out.add("import: ${q(it, false)}") }
        e.icalHash?.let { out.add("ical_hash: ${q(it, false)}") }
        if (e.icalExtra.isNotEmpty()) {
            out.add("ical_extra:")
            e.icalExtra.forEach { out.add(icalLine(it)) }
        }
        return out
    }

    /** The note to write: agenda keys first, then the other unknown keys as they were. */
    fun toNote(e: Event): Note = e.note.copy(extra = agendaLines(e) + e.note.extra)

    /** The note without any agenda key ("Retirer de l'agenda" on a dated note). */
    fun stripAgenda(note: Note): Note = note.copy(extra = agendaValues(note).second)

    /** A new, unsaved event (empty path: [AgendaStore.save] fixes it). Its uid is `<id>@scriptorium`. */
    fun newEvent(
        title: String, start: Stamp, end: Stamp? = null, tz: String? = null, calendar: String? = null,
        body: String = "", now: Long = System.currentTimeMillis(),
    ): Event {
        val id = NoteFormat.generateId()
        val note = Note(path = "", id = id, title = title, body = body, created = now, updated = now)
        return Event(note = note, start = start, end = end, tz = tz, calendar = calendar, uid = "$id@scriptorium")
    }

    /** (checked, total) of the note's checklist items, null without a checklist. */
    fun checklistProgress(body: String): Pair<Int, Int>? {
        var total = 0
        var done = 0
        for (line in body.split("\n")) {
            val m = CHECK_REGEX.matchEntire(line) ?: continue
            total++
            if (m.groupValues[1] != " ") done++
        }
        return if (total > 0) done to total else null
    }

    /** FNV-1a 32 bits of the UTF-8 text, 8 hex digits (same in Python). */
    fun fnv32(text: String): String {
        var h = 0x811C9DC5L
        for (b in text.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xFF)
            h = (h * 0x01000193L) and 0xFFFFFFFFL
        }
        return String.format(java.util.Locale.ROOT, "%08x", h)
    }
}
