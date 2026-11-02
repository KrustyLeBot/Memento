package app.scriptorium.agenda

import app.scriptorium.Fs
import app.scriptorium.NoteFormat
import app.scriptorium.isTempName

data class AgendaScan(
    val events: List<Event> = emptyList(),
    /** Unreadable files (French), never silently dropped. */
    val problems: List<String> = emptyList(),
)

/**
 * `agenda/` on the storage [Fs] (plain folder, SAF, or a memory double in tests). Same layout and rules as
 * Python's `core/agenda/store.py`:
 * `agenda/<year of the start>/<slug>-<id6>.md` (a series lives in the year of its first occurrence) and
 * `agenda/calendars.json`; files directly in `agenda/` are read too. The path is fixed at creation.
 * Blocking I/O: call from Dispatchers.IO.
 */
class AgendaStore(val fs: Fs) {
    private val ids = HashSet<String>()
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Event>>()

    // ---- reading ----

    fun paths(): List<String> = entries().map { it.first }

    /** (path, last modified) of every event file. */
    private fun entries(): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        for (e in fs.list(EventFormat.AGENDA_DIR).sortedBy { it.name }) {
            if (e.isDir && !e.name.startsWith(".")) {
                val dir = "${EventFormat.AGENDA_DIR}/${e.name}"
                fs.list(dir).sortedBy { it.name }.filter { !it.isDir && isEventFile(it.name) }.forEach { out.add("$dir/${it.name}" to it.lastModified) }
            } else if (!e.isDir && isEventFile(e.name)) {
                out.add("${EventFormat.AGENDA_DIR}/${e.name}" to e.lastModified)
            }
        }
        return out
    }

    /** Throws [AgendaFormatException] (missing file, unreadable start, or no start at all). */
    fun read(path: String): Event {
        val text = fs.readText(path) ?: throw AgendaFormatException("$path : fichier introuvable ou illisible")
        val note = NoteFormat.parse(path, text, fs.lastModified(path))
        return EventFormat.fromNote(note) ?: throw AgendaFormatException("$path : pas de date de début (start)")
    }

    /** Every event file; files whose modification time did not change since the last scan are not re-read. */
    fun scan(): AgendaScan {
        val events = ArrayList<Event>()
        val problems = ArrayList<String>()
        val seen = HashSet<String>()
        for ((p, modified) in entries()) {
            seen.add(p)
            val cached = cache[p]
            if (cached != null && cached.first == modified && modified != 0L) {
                events.add(cached.second); continue
            }
            try {
                events.add(read(p).also { cache[p] = modified to it })
            } catch (e: AgendaFormatException) {
                cache.remove(p)
                problems.add(e.message ?: p)
            }
        }
        cache.keys.retainAll(seen)
        synchronized(ids) {
            ids.clear()
            events.mapTo(ids) { it.id }
        }
        return AgendaScan(events, problems)
    }

    fun calendars(): CalendarSettings = fs.readText(CalendarSettings.PATH)?.let { CalendarSettings.parse(it) } ?: CalendarSettings()

    fun saveCalendars(settings: CalendarSettings) = fs.writeText(CalendarSettings.PATH, settings.toJson())

    // ---- writing ----

    fun pathFor(title: String, id: String, start: Stamp): String =
        "${EventFormat.AGENDA_DIR}/${String.format(java.util.Locale.ROOT, "%04d", start.date.year)}/${NoteFormat.fileNameFor(title, id)}"

    /**
     * Writes the event; a new one (empty path) gets a free id and its path. [takenIds]: other ids in use
     * (notes, activities) the new id must avoid.
     */
    fun save(event: Event, takenIds: Set<String> = emptySet()): Event {
        var e = event
        if (e.path.isEmpty()) {
            var id = e.id
            synchronized(ids) { while (id.isEmpty() || id in ids || id in takenIds) id = NoteFormat.generateId() }
            val uid = if (e.uid != null && e.uid != "${e.id}@scriptorium") e.uid else "$id@scriptorium"
            e = e.copy(uid = uid, note = e.note.copy(id = id, path = pathFor(e.title, id, e.start)))
        }
        e = e.withNote { it.copy(hasId = true) }
        fs.writeText(e.path, NoteFormat.serialize(EventFormat.toNote(e)))
        cache.remove(e.path)
        synchronized(ids) { ids.add(e.id) }
        return e
    }

    /** Removes the file and its attachments (like a note). */
    fun delete(event: Event) {
        listOfNotNull(event.path.ifEmpty { null }, event.note.pdf).plus(event.note.attachments).forEach { fs.delete(it) }
        cache.remove(event.path)
        synchronized(ids) { ids.remove(event.id) }
    }

    // ---- one occurrence of a series ----

    /** "Supprimer cet événement" on a series: the occurrence goes into `exdate`. */
    fun deleteOccurrence(series: Event, occurrenceStart: Stamp): Event =
        if (occurrenceStart in series.exdate) series else save(series.copy(exdate = series.exdate + occurrenceStart))

    /**
     * An unsaved copy of the series for one occurrence ("Modifier cet événement"): no rule, own id,
     * `recurrence_of`/`recurrence_at` set, same uid (ICS overrides share the series UID).
     */
    fun makeException(series: Event, occurrenceStart: Stamp, occurrenceEnd: Stamp?): Event = series.copy(
        note = series.note.copy(path = "", id = NoteFormat.generateId(), attachments = emptyList(), pdf = null),
        start = occurrenceStart, end = occurrenceEnd, rrule = null, exdate = emptyList(),
        recurrenceOf = series.id, recurrenceAt = occurrenceStart, importId = null, warnings = emptyList(),
    )

    /** Writes the exception file and adds its occurrence to the series' `exdate`. Returns (series, exception). */
    fun saveException(series: Event, exception: Event): Pair<Event, Event> {
        val at = exception.recurrenceAt
        require(exception.recurrenceOf == series.id && at != null) { "exception d'une autre série" }
        val saved = save(exception)
        val s = if (at in series.exdate) series else save(series.copy(exdate = series.exdate + at))
        return s to saved
    }

    /** "Rétablir": deletes the exception file (if any) and removes the occurrence from `exdate`. */
    fun restoreOccurrence(series: Event, exception: Event?, occurrenceStart: Stamp): Event {
        exception?.let { delete(it) }
        return if (occurrenceStart in series.exdate) save(series.copy(exdate = series.exdate.filter { it != occurrenceStart })) else series
    }

    /** "Tous les événements": the series and its exception files. */
    fun deleteSeries(series: Event, events: List<Event>) {
        events.filter { it.recurrenceOf == series.id }.forEach { delete(it) }
        delete(series)
    }

    companion object {
        fun isEventFile(name: String) = name.endsWith(".md") && !name.startsWith(".") && !isTempName(name)
    }
}
