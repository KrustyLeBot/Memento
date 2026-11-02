package app.scriptorium.agenda

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.scriptorium.Note
import app.scriptorium.NoteRepository
import app.scriptorium.Screen
import app.scriptorium.Snack
import app.scriptorium.contacts.Contact
import app.scriptorium.reminders.Reminders
import app.scriptorium.widget.AgendaWidgets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** How the editor writes what it holds. */
sealed class EditMode {
    /** A new file. */
    data object New : EditMode()
    /** The file itself: a single event, an exception file, or a whole series ("tous les événements"). */
    data object File : EditMode()
    /** One occurrence of [series] ("cet événement"): saved as an exception file plus an `exdate`. */
    data class Occurrence(val series: Event) : EditMode()
}

/** The event editor: the file being edited, how to write it, and the fields (Compose state). */
class EventEditor(val mode: EditMode, val base: Event, draft: EventDraft) {
    var draft by mutableStateOf(draft)
    val initial = draft
    val changed: Boolean get() = draft != initial
}

/** The ICS import dialog's steps (Part B §5.2). */
sealed class IcsImportState {
    data object Reading : IcsImportState()
    class Preview(val parsed: List<ParsedCalendar>, val previews: List<CalendarPreview>) : IcsImportState()
    data class Running(val done: Int, val total: Int) : IcsImportState()
    class Report(val report: ImportReport) : IcsImportState()
}

/** Scope of a change on a series. */
enum class SeriesScope { THIS, ALL }

/**
 * Everything the agenda module does besides drawing: the merged sources and their windows of entries, the
 * detail, the editor with the series scopes, deletes with undo, calendars, import/export, and keeping the
 * reminders and the widgets up to date whenever the data changes.
 */
@OptIn(FlowPreview::class)
class AgendaController(
    private val app: Application,
    private val repo: NoteRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    contacts: StateFlow<List<Contact>>,
    private val say: (Snack) -> Unit,
    private val navigate: (Screen) -> Unit,
    private val openNote: (Note, Screen) -> Unit,
    private val openActivity: (Note) -> Unit,
    private val openContact: (String) -> Unit,
    /** A sync is running: the folder is read-only until it ends (the banner says why). */
    private val syncing: () -> Boolean,
) {
    val prefs = AgendaPrefs(app)

    var view by mutableStateOf(prefs.lastView)
        private set
    /** The day the views are on (the month, week or day shown contains it). */
    var selected by mutableStateOf(LocalDate.now())
    /** Bumped to make the agenda list scroll to [selected] (the "Aujourd'hui" button, jump to a date). */
    var scrollRequest by mutableStateOf(0)
        private set
    var searching by mutableStateOf(false)
    var query by mutableStateOf("")
    var weekStart by mutableStateOf(prefs.weekStart)
        private set
    var eventZoneTimes by mutableStateOf(prefs.eventZoneTimes)
        private set

    /** Detail screen: the event and the start of the occurrence shown. */
    var detailId by mutableStateOf<String?>(null)
        private set
    var detailAt by mutableStateOf<Instant?>(null)
        private set
    var editor by mutableStateOf<EventEditor?>(null)
        private set
    private var editorReturn: Screen = Screen.AGENDA
    var importState by mutableStateOf<IcsImportState?>(null)
        private set

    /** Everything the calendar reads, once the folder was read at least once. */
    val sources: StateFlow<AgendaSources> = combine(
        repo.agenda, repo.notes, repo.activities, repo.calendars, contacts,
    ) { agenda, notes, activities, calendars, people ->
        AgendaSources(agenda.events, notes, activities, AgendaData.birthdaysOf(people), calendars)
    }.stateIn(scope, SharingStarted.Eagerly, AgendaSources())

    init {
        // Reminders and widgets follow every change (edit, sync, import, file watcher), never the empty
        // state before the first scan: that would cancel every alarm.
        scope.launch {
            combine(sources, repo.scans) { s, n -> s to n }
                .filter { it.second > 0 }
                .map { it.first }
                .debounce(1_500)
                .collect { s ->
                    AgendaData.publish(s)
                    try {
                        withContext(Dispatchers.Default) { Reminders.reschedule(app, s) }
                    } catch (e: SecurityException) {
                        say(Snack("Rappels non programmés : ${e.message}"))
                    }
                    AgendaWidgets.updateAll(app)
                }
        }
    }

    val device: ZoneId get() = ZoneId.systemDefault()

    // ---- views ----

    fun changeView(v: AgendaView) {
        view = v
        prefs.lastView = v
        searching = false
    }

    fun goToday() = goTo(LocalDate.now())

    fun goTo(d: LocalDate) {
        selected = d
        scrollRequest++
    }

    fun changeWeekStart(d: java.time.DayOfWeek) { weekStart = d; prefs.weekStart = d }
    fun changeEventZoneTimes(v: Boolean) { eventZoneTimes = v; prefs.eventZoneTimes = v }

    /** Sources compared by identity: a deep hash of every note on each lookup would cost more than the window. */
    private class WindowKey(val sources: AgendaSources, val from: LocalDate, val to: LocalDate, val zone: ZoneId) {
        override fun equals(other: Any?) =
            other is WindowKey && other.sources === sources && other.from == from && other.to == to && other.zone == zone
        override fun hashCode() = ((System.identityHashCode(sources) * 31 + from.hashCode()) * 31 + to.hashCode()) * 31 + zone.hashCode()
    }

    private val cache = object : LinkedHashMap<WindowKey, EntriesResult>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<WindowKey, EntriesResult>?) = size > 24
    }

    /** Entries of the days [from, toExclusive), computed off the main thread and cached per window. */
    suspend fun entries(s: AgendaSources, from: LocalDate, toExclusive: LocalDate): EntriesResult {
        val zone = device
        val key = WindowKey(s, from, toExclusive, zone)
        synchronized(cache) { cache[key] }?.let { return it }
        val r = withContext(Dispatchers.Default) {
            AgendaIndex.entries(s, Zones.dayStart(from, zone), Zones.dayStart(toExclusive, zone), zone)
        }
        synchronized(cache) { cache[key] = r }
        return r
    }

    // ---- opening ----

    fun open(e: Entry) {
        when (e.kind) {
            EntryKind.EVENT -> {
                detailId = e.targetId
                detailAt = e.start
                navigate(Screen.EVENT)
            }
            EntryKind.NOTE -> openNoteById(e.targetId)
            EntryKind.ACTIVITY -> repo.activities.value.firstOrNull { it.id == e.targetId }?.let(openActivity)
                ?: say(Snack("Activité introuvable"))
            EntryKind.BIRTHDAY -> openContact(e.targetId)
        }
    }

    private fun openNoteById(id: String) {
        val n = repo.notes.value.firstOrNull { it.id == id && !it.conflict }
        if (n == null) say(Snack("Note introuvable (pas encore synchronisée ?)")) else openNote(n, Screen.AGENDA)
    }

    /** Runs [action] once the folder was read (an intent can arrive before the first scan). */
    fun whenLoaded(action: () -> Unit) {
        if (repo.scans.value > 0) return action()
        scope.launch {
            repo.scans.first { it > 0 }
            action()
        }
    }

    /** From a reminder notification or a widget row. */
    fun openExternal(kind: String?, targetId: String?, startMs: Long) {
        if (targetId == null) return
        if (repo.scans.value == 0) return whenLoaded { openExternal(kind, targetId, startMs) }
        when (runCatching { EntryKind.valueOf(kind ?: "EVENT") }.getOrDefault(EntryKind.EVENT)) {
            EntryKind.EVENT -> {
                detailId = targetId
                detailAt = if (startMs > 0) Instant.ofEpochMilli(startMs) else null
                navigate(Screen.EVENT)
            }
            EntryKind.NOTE -> openNoteById(targetId)
            EntryKind.ACTIVITY -> repo.activities.value.firstOrNull { it.id == targetId }?.let(openActivity) ?: navigate(Screen.AGENDA)
            EntryKind.BIRTHDAY -> openContact(targetId)
        }
    }

    fun closeDetail() {
        detailId = null
        navigate(Screen.AGENDA)
    }

    fun event(id: String?): Event? = id?.let { i -> repo.agenda.value.events.firstOrNull { it.id == i } }

    /** The description is the event's note: the note editor edits it (checklists, photos, drawings). */
    fun editDescription(e: Event) {
        openNote(EventFormat.toNote(e), Screen.EVENT)
    }

    // ---- editor ----

    private fun readOnly(): Boolean {
        if (syncing()) say(Snack("Synchronisation en cours : réessaie dans un instant"))
        return syncing()
    }

    fun newEvent(day: LocalDate = selected, at: LocalDateTime? = null, allDay: Boolean = false) {
        if (repo.scans.value == 0) return whenLoaded { newEvent(day, at, allDay) }
        if (readOnly()) return
        val settings = repo.calendars.value
        val start = at ?: AgendaLayout.defaultStart(day, LocalDateTime.now())
        val draft = EventDraft.new(start, allDay, device, settings.defaultCalendar().id, settings.defaultAlerts(allDay))
        val base = EventFormat.newEvent("", Stamp.of(start))
        editorReturn = Screen.AGENDA
        editor = EventEditor(EditMode.New, base, draft)
        navigate(Screen.EVENT_EDIT)
    }

    /** "Modifier": the file itself, or with [scope] THIS one occurrence of a series. */
    fun edit(entry: Entry, scope: SeriesScope) {
        if (readOnly()) return
        val e = event(entry.targetId) ?: return say(Snack("Événement introuvable"))
        val o = entry.occurrence
        editorReturn = Screen.EVENT
        editor = if (e.isSeries && scope == SeriesScope.THIS && o != null) {
            val exc = repo.agendaStore.makeException(e, o.start, o.end)
            EventEditor(EditMode.Occurrence(e), exc, EventDraft.fromEvent(exc))
        } else EventEditor(EditMode.File, e, EventDraft.fromEvent(e))
        navigate(Screen.EVENT_EDIT)
    }

    fun editSeriesOf(exception: Event) {
        if (readOnly()) return
        val s = event(exception.recurrenceOf) ?: return say(Snack("Série introuvable"))
        editorReturn = Screen.EVENT
        editor = EventEditor(EditMode.File, s, EventDraft.fromEvent(s))
        navigate(Screen.EVENT_EDIT)
    }

    fun cancelEditor() {
        editor = null
        navigate(editorReturn)
    }

    /** Writes the editor; [thenDescription] opens the note editor on the saved event right after. */
    fun saveEditor(thenDescription: Boolean = false) {
        val ed = editor ?: return
        val d = ed.draft
        d.problem()?.let { return say(Snack(it)) }
        if (d.title.isBlank() && ed.mode == EditMode.New && !thenDescription) return say(Snack("Donne un titre à l'événement"))
        scope.launch {
            try {
                val saved = withContext(io) {
                    when (val m = ed.mode) {
                        EditMode.New -> repo.saveEvent(d.applyTo(ed.base))
                        EditMode.File -> {
                            val updated = d.applyTo(ed.base)
                            val out = repo.saveEvent(updated.withNote { it.copy(updated = System.currentTimeMillis()) })
                            if (ed.base.isSeries) retimeExceptions(ed.base, out)
                            out
                        }
                        is EditMode.Occurrence -> {
                            val series = event(m.series.id) ?: m.series
                            val exc = d.applyTo(ed.base).copy(rrule = null)
                            val (_, saved) = repo.agendaStore.saveException(series, exc)
                            repo.rescanAgenda()
                            saved
                        }
                    }
                }
                editor = null
                // a whole series edited from one of its occurrences stays on that occurrence when it still exists
                if (!(ed.mode == EditMode.File && ed.base.isSeries && detailId == saved.id)) detailAt = startInstant(saved)
                detailId = saved.id
                if (thenDescription) editDescription(saved) else navigate(Screen.EVENT)
            } catch (e: Exception) {
                say(Snack("Échec de l'enregistrement : ${e.message}"))
            }
        }
    }

    private fun startInstant(e: Event): Instant =
        if (e.allDay) Zones.dayStart(e.start.date, device) else Zones.toInstant(e.start.dateTime(), Zones.zoneOr(e.tz, device))

    /** A series whose start time changed: its exception files keep pointing at their occurrence (blocking). */
    private fun retimeExceptions(old: Event, new: Event) {
        if (old.start == new.start) return
        for (x in repo.agenda.value.events.filter { it.recurrenceOf == old.id }) {
            val at = x.recurrenceAt ?: continue
            val moved = EventDraft.carryExdates(listOf(at), old.start, new.start).first()
            if (moved != at) repo.saveEvent(x.copy(recurrenceAt = moved))
        }
    }

    // ---- delete, restore, undo ----

    private fun backup(paths: List<String>): Map<String, ByteArray> =
        paths.distinct().mapNotNull { p -> repo.fs.readBytes(p)?.let { p to it } }.toMap()

    private fun filesOf(events: List<Event>) = events.flatMap { listOf(it.path) + listOfNotNull(it.note.pdf) + it.note.attachments }

    private fun undoWith(text: String, saved: Map<String, ByteArray>) {
        say(Snack(text) {
            scope.launch {
                try {
                    withContext(io) {
                        saved.forEach { (p, b) -> repo.fs.writeBytes(p, b) }
                        saved.keys.forEach { repo.agendaStore.forget(it) }
                        repo.rescanAgenda()
                    }
                } catch (e: Exception) {
                    say(Snack("Annulation impossible : ${e.message}"))
                }
            }
        })
    }

    /** Deletes the entry's file, or with [scope] THIS on a series its occurrence only; the snackbar undoes it. */
    fun delete(entry: Entry, scope: SeriesScope) {
        if (readOnly()) return
        val e = event(entry.targetId) ?: return
        val o = entry.occurrence
        detailId = null
        navigate(Screen.AGENDA)
        this.scope.launch {
            try {
                val text = withContext(io) {
                    if (e.isSeries && scope == SeriesScope.THIS && o != null) {
                        val saved = backup(listOf(e.path))
                        repo.saveEvent(e.copy(exdate = (e.exdate + o.start).distinct()))
                        saved to "Occurrence supprimée"
                    } else if (e.isSeries) {
                        val all = listOf(e) + repo.agenda.value.events.filter { it.recurrenceOf == e.id }
                        val saved = backup(filesOf(all))
                        repo.agendaStore.deleteSeries(e, repo.agenda.value.events)
                        repo.rescanAgenda()
                        saved to "Série supprimée"
                    } else {
                        val saved = backup(filesOf(listOf(e)))
                        repo.deleteEvent(e)
                        saved to "Événement supprimé"
                    }
                }
                undoWith(text.second, text.first)
            } catch (x: Exception) {
                say(Snack("Suppression impossible : ${x.message}"))
            }
        }
    }

    /** "Rétablir l'occurrence d'origine": the exception file goes, the series' occurrence comes back. */
    fun restoreOccurrence(exception: Event) {
        if (readOnly()) return
        val series = event(exception.recurrenceOf) ?: return say(Snack("Série introuvable"))
        val at = exception.recurrenceAt ?: return
        scope.launch {
            try {
                withContext(io) {
                    repo.agendaStore.restoreOccurrence(series, exception, at)
                    repo.rescanAgenda()
                }
                detailId = series.id
                detailAt = if (at.allDay) Zones.dayStart(at.date, device) else Zones.toInstant(at.dateTime(), Zones.zoneOr(series.tz, device))
                say(Snack("Occurrence d'origine rétablie"))
            } catch (e: Exception) {
                say(Snack("Impossible : ${e.message}"))
            }
        }
    }

    // ---- dated notes ----

    /** "Ajouter à l'agenda" / "Retirer de l'agenda" on a regular note: [start] null removes the date (and the old `reminder`). */
    fun datedNote(note: Note, start: Stamp?, alerts: List<Int>): Note {
        val plain = EventFormat.stripAgenda(note).copy(reminder = null)
        if (start == null) return plain
        val e = Event(note = plain, start = start, tz = if (start.allDay) null else device.id, alerts = alerts.distinct().sorted())
        return EventFormat.toNote(e)
    }

    // ---- calendars ----

    fun saveCalendars(s: CalendarSettings) {
        if (readOnly()) return
        scope.launch {
            try {
                withContext(io) { repo.saveCalendars(s) }
            } catch (e: Exception) {
                say(Snack("Agendas non enregistrés : ${e.message}"))
            }
        }
    }

    fun updateCalendars(transform: (CalendarSettings) -> CalendarSettings) = saveCalendars(transform(repo.calendars.value))

    /** Deletes the calendar and every event in it; the snackbar puts everything back. */
    fun deleteCalendar(id: String) {
        if (readOnly()) return
        val settings = repo.calendars.value
        val doomed = settings.eventsOf(id, repo.agenda.value.events)
        val next = try {
            settings.removeCalendar(id)
        } catch (e: IllegalArgumentException) {
            return say(Snack(e.message ?: "Impossible"))
        }
        scope.launch {
            try {
                val saved = withContext(io) {
                    val files = backup(filesOf(doomed) + CalendarSettings.PATH)
                    doomed.forEach { repo.agendaStore.delete(it) }
                    repo.saveCalendars(next)
                    repo.rescanAgenda()
                    files
                }
                undoWith("Agenda supprimé (${doomed.size} événement${if (doomed.size > 1) "s" else ""})", saved)
            } catch (e: Exception) {
                say(Snack("Suppression impossible : ${e.message}"))
            }
        }
    }

    // ---- import ----

    private fun displayName(uri: Uri): String = try {
        app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: SecurityException) {
        null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "agenda.ics"

    fun startImport(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (repo.scans.value == 0) return whenLoaded { startImport(uris) }
        importState = IcsImportState.Reading
        searching = false
        navigate(Screen.AGENDA)
        scope.launch {
            try {
                val (parsed, previews) = withContext(io) {
                    val sources = uris.map { u ->
                        IcsSource(displayName(u)) { app.contentResolver.openInputStream(u) ?: throw java.io.IOException("Fichier illisible") }
                    }
                    val parsed = IcsImport.read(sources, device)
                    parsed to IcsImport.preview(parsed, repo.agenda.value.events, repo.calendars.value)
                }
                if (parsed.isEmpty()) throw java.io.IOException("Aucun agenda dans ce fichier (.ics ou .zip attendu)")
                importState = IcsImportState.Preview(parsed, previews)
            } catch (e: Exception) {
                importState = null
                say(Snack("Import impossible : ${e.message}"))
            }
        }
    }

    fun cancelImport() { importState = null }

    fun confirmImport(options: ImportOptions) {
        val p = importState as? IcsImportState.Preview ?: return
        if (readOnly()) return
        importState = IcsImportState.Running(0, p.parsed.sumOf { it.events.size + it.overrides.size })
        scope.launch {
            try {
                val report = withContext(io) {
                    val taken = (repo.notes.value + repo.activities.value).map { it.id }.toSet()
                    val (report, _) = IcsImport.apply(
                        repo.agendaStore, p.parsed, options, repo.agenda.value.events, repo.calendars.value, device,
                        takenIds = taken,
                    ) { done, total -> if (done == total || done % 25 == 0) scope.launch { importState = IcsImportState.Running(done, total) } }
                    repo.rescanAgenda()
                    report
                }
                importState = IcsImportState.Report(report)
            } catch (e: Exception) {
                withContext(io) { repo.rescanAgenda() }
                importState = null
                say(Snack("Import interrompu : ${e.message}"))
            }
        }
    }

    fun closeReport() { importState = null }

    /** "Annuler l'import": every file carrying `import: <id>`, and the calendars it created when empty. */
    fun undoImport(importId: String) {
        if (readOnly()) return
        importState = null
        scope.launch {
            try {
                val r = withContext(io) {
                    val (r, _) = IcsImport.undo(repo.agendaStore, importId, repo.agenda.value.events, repo.calendars.value)
                    repo.rescanAgenda()
                    r
                }
                say(Snack("Import annulé (${r.deleted} fichier${if (r.deleted > 1) "s" else ""})"))
            } catch (e: Exception) {
                say(Snack("Annulation impossible : ${e.message}"))
            }
        }
    }

    // ---- export ----

    /** One calendar ([calendarId]) as `.ics`, or every calendar as a `.zip` when null. */
    fun export(uri: Uri, calendarId: String?, withLayers: Boolean) {
        prefs.exportWithLayers = withLayers
        scope.launch {
            try {
                val (count, warnings) = withContext(io) {
                    val events = repo.agenda.value.events
                    val settings = repo.calendars.value
                    val notes = if (withLayers) repo.notes.value else emptyList()
                    val acts = if (withLayers) repo.activities.value else emptyList()
                    val now = Instant.now()
                    val (bytes, count, warnings) = if (calendarId == null) {
                        val (zip, w) = IcsExport.exportAll(events, settings, now, notes, acts)
                        Triple(zip, events.count { !it.note.conflict }, w)
                    } else {
                        val r = IcsExport.exportCalendar(events, settings, calendarId, now, notes, acts)
                        Triple(r.text.toByteArray(Charsets.UTF_8), r.events, r.warnings)
                    }
                    app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw java.io.IOException("Fichier inaccessible")
                    count to warnings
                }
                say(Snack("Exporté : $count événement${if (count > 1) "s" else ""}" + if (warnings.isNotEmpty()) " (${warnings.size} avertissement${if (warnings.size > 1) "s" else ""})" else ""))
            } catch (e: Exception) {
                say(Snack("Export impossible : ${e.message}"))
            }
        }
    }
}
