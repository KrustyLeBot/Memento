package app.memento

import android.content.Context
import android.os.FileObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The storage folder is the source of truth; [notes] is only an in-memory index
 * rebuilt by [scan]. Every method here does blocking I/O: call from Dispatchers.IO.
 */
class NoteRepository(private val context: Context, private val settings: Settings) {
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes

    /** The "Activités" folder: notes of `activities/`, kept out of the regular list. */
    private val _activities = MutableStateFlow<List<Note>>(emptyList())
    val activities: StateFlow<List<Note>> = _activities

    /** `activities/settings.json`, shared with the PC through the sync. */
    private val _activitySettings = MutableStateFlow(app.memento.activity.ActivitySettings())
    val activitySettings: StateFlow<app.memento.activity.ActivitySettings> = _activitySettings

    fun saveActivitySettings(s: app.memento.activity.ActivitySettings) {
        fs.writeText(app.memento.activity.ActivitySettings.PATH, s.toJson())
        _activitySettings.value = s
    }

    /** `agenda/`: event files ([app.memento.agenda.AgendaStore.scan]), with the files that could not be read. */
    private val _agenda = MutableStateFlow(app.memento.agenda.AgendaScan())
    val agenda: StateFlow<app.memento.agenda.AgendaScan> = _agenda

    /** `agenda/calendars.json`, shared with the PC through the sync. */
    private val _calendars = MutableStateFlow(app.memento.agenda.CalendarSettings())
    val calendars: StateFlow<app.memento.agenda.CalendarSettings> = _calendars

    /** Number of completed scans since the storage was set: 0 means nothing was read yet (lists are empty, not "no data"). */
    private val _scans = MutableStateFlow(0)
    val scans: StateFlow<Int> = _scans

    private val parsed = HashMap<String, Pair<Long, Note>>() // path -> (lastModified, note)
    private var observers: List<FileObserver> = emptyList()
    var fs: Fs = buildFs(settings.storage)
        private set

    /** The agenda data layer on the current storage (rebuilt with it). */
    var agendaStore = app.memento.agenda.AgendaStore(fs)
        private set

    private fun buildFs(config: StorageConfig): Fs = when (config) {
        StorageConfig.Default -> FileFs(Settings.defaultDir())
        is StorageConfig.Tree -> SafFs(context, config.uri)
    }

    fun reconfigure() {
        fs = buildFs(settings.storage)
        agendaStore = app.memento.agenda.AgendaStore(fs)
        parsed.clear()
        _notes.value = emptyList()
        _activities.value = emptyList()
        _agenda.value = app.memento.agenda.AgendaScan()
        _calendars.value = app.memento.agenda.CalendarSettings()
        _scans.value = 0
    }

    /** Full rescan: reads only files whose modification time changed. */
    fun scan() {
        val result = ArrayList<Note>()
        val seen = HashSet<String>()
        for (entry in fs.list("")) {
            if (entry.isDir || !isNoteFile(entry.name) || entry.name.startsWith(".")) continue
            val path = entry.name
            seen.add(path)
            val cached = parsed[path]
            val note = if (cached != null && cached.first == entry.lastModified) {
                cached.second
            } else {
                val text = fs.readText(path) ?: continue
                NoteFormat.parse(path, text, entry.lastModified).also { parsed[path] = entry.lastModified to it }
            }
            result.add(note)
        }

        val acts = ArrayList<Note>()
        for (entry in fs.list(ACTIVITIES_DIR)) {
            if (entry.isDir || !entry.name.endsWith(".md") || entry.name.startsWith(".")) continue
            val path = "$ACTIVITIES_DIR/${entry.name}"
            seen.add(path)
            val cached = parsed[path]
            val note = if (cached != null && cached.first == entry.lastModified) {
                cached.second
            } else {
                val text = fs.readText(path) ?: continue
                NoteFormat.parse(path, text, entry.lastModified).also { parsed[path] = entry.lastModified to it }
            }
            acts.add(note)
        }
        parsed.keys.retainAll(seen)
        fs.readText(app.memento.activity.ActivitySettings.PATH)?.let { _activitySettings.value = app.memento.activity.ActivitySettings.parse(it) }
        _notes.value = result
        _activities.value = acts
        _agenda.value = agendaStore.scan()
        _calendars.value = agendaStore.calendars()
        _scans.value = _scans.value + 1
    }

    @Synchronized
    fun startWatching() {
        stopWatching()
        val fileFs = fs as? FileFs ?: return
        val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MODIFY or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE
        val dir = fileFs.root
        if (!dir.isDirectory && !dir.mkdirs()) return
        fun observe(d: java.io.File, folders: (String) -> Boolean = { false }, accept: (String) -> Boolean = ::isNoteFile): FileObserver =
            object : FileObserver(d.absolutePath, mask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == null) return
                    val created = event and (CREATE or MOVED_TO) != 0
                    if (created && folders(path) && java.io.File(d, path).isDirectory) {
                        // a folder watched below appeared (activities/, contacts/, agenda/<year>/): watch it, read it
                        onFolderAdded?.invoke()
                        onExternalChange?.invoke()
                    } else if (accept(path)) onExternalChange?.invoke()
                }
            }.also { it.startWatching() }
        // agenda/ and its year folders; a watched folder created later restarts the watch (onFolderAdded)
        val agendaDir = java.io.File(dir, app.memento.agenda.EventFormat.AGENDA_DIR)
        val agendaDirs = listOf(agendaDir) + agendaDir.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }
        observers = listOfNotNull(
            observe(dir, folders = { it in WATCHED_FOLDERS }),
            java.io.File(dir, ACTIVITIES_DIR).takeIf { it.isDirectory }?.let { observe(it) },
            java.io.File(dir, app.memento.contacts.ContactFormat.DIR).takeIf { it.isDirectory }?.let { observe(it) },
        ) + agendaDirs.filter { it.isDirectory }.map { d ->
            observe(d, folders = { d == agendaDir && !it.startsWith(".") }) { name -> !isTempName(name) && (isNoteFile(name) || name.endsWith(".json") || '.' !in name) }
        }
    }

    /** Watches again (a folder was added), unless the watch was stopped meanwhile (app in the background). */
    @Synchronized
    fun rewatch() {
        if (observers.isNotEmpty()) startWatching()
    }

    @Synchronized
    fun stopWatching() {
        observers.forEach { it.stopWatching() }
        observers = emptyList()
    }

    /** Called (from an arbitrary thread) when a `.md` file changed on disk. */
    var onExternalChange: (() -> Unit)? = null

    /** Called (from an arbitrary thread) when a folder appeared where [startWatching] must watch it too. */
    var onFolderAdded: (() -> Unit)? = null

    /** A note, or a conflict copy left by the sync tool (`name.md.conflict1`, `name.md.conflict2`). */
    private fun isNoteFile(name: String) = name.endsWith(".md") || CONFLICT_COPY.matches(name)

    fun existingLabels(): List<String> =
        _notes.value.flatMap { it.labels }.distinct().sortedBy { it.fold() }

    // ---- writes ----

    /** Writes [note] to disk (creating its file on first save) and updates the index. */
    fun save(note: Note): Note {
        var n = note
        if (n.path.isEmpty()) {
            val ids = (_notes.value + _activities.value).map { it.id }.toSet() + _agenda.value.events.map { it.id }
            var id = n.id
            while (id in ids || id.isEmpty()) id = NoteFormat.generateId()
            n = n.copy(id = id, path = NoteFormat.fileNameFor(n.title, id), hasId = true)
        }
        n = n.copy(hasId = true)
        fs.writeText(n.path, NoteFormat.serialize(n))
        if (isAgendaPath(n.path)) {
            // an event's description edited in the note editor: the agenda index holds it, not the notes list
            agendaStore.forget(n.path)
            _agenda.value = agendaStore.scan()
            return n
        }
        val modified = fs.lastModified(n.path)
        parsed[n.path] = modified to n
        if (isActivityPath(n.path)) _activities.value = _activities.value.filter { it.path != n.path } + n
        else _notes.value = _notes.value.filter { it.path != n.path } + n
        return n
    }

    fun readRaw(path: String): String? = fs.readText(path)

    // ---- agenda ----

    /** Writes an event (a new one gets its id and `agenda/<year>/` path) and updates [agenda]. */
    fun saveEvent(event: app.memento.agenda.Event): app.memento.agenda.Event {
        val taken = (_notes.value + _activities.value).map { it.id }.toSet()
        val saved = agendaStore.save(event, taken)
        val scan = _agenda.value
        _agenda.value = scan.copy(events = scan.events.filter { it.path != saved.path } + saved)
        return saved
    }

    fun deleteEvent(event: app.memento.agenda.Event) {
        agendaStore.delete(event)
        val scan = _agenda.value
        _agenda.value = scan.copy(events = scan.events.filter { it.path != event.path })
    }

    fun saveCalendars(settings: app.memento.agenda.CalendarSettings) {
        agendaStore.saveCalendars(settings)
        _calendars.value = settings
    }

    /** After a bulk change (import, undo): re-reads `agenda/` and `calendars.json`. */
    fun rescanAgenda() {
        _agenda.value = agendaStore.scan()
        _calendars.value = agendaStore.calendars()
    }

    fun delete(note: Note) {
        if (isAgendaPath(note.path)) {
            app.memento.agenda.EventFormat.fromNote(note)?.let { deleteEvent(it) }
            return
        }
        fs.delete(note.path)
        ownedFiles(note, _notes.value + _activities.value + _agenda.value.events.map { it.note }).forEach { fs.delete(it) }
        parsed.remove(note.path)
        if (isActivityPath(note.path)) _activities.value = _activities.value.filter { it.path != note.path }
        else _notes.value = _notes.value.filter { it.path != note.path }
    }

    /**
     * Conflict resolution: this copy replaces the original (same id, no "conflict" in its name).
     */
    fun keepConflictCopy(copy: Note): Note {
        val original = _notes.value.firstOrNull { it.id == copy.id && !it.conflict && it.path != copy.path }
        val targetPath = original?.path ?: copy.path.replace(Regex("(?i)[ ._-]*conflict[^.]*"), "")
        val kept = copy.copy(path = targetPath, conflict = false, updated = System.currentTimeMillis())
        fs.writeText(targetPath, NoteFormat.serialize(kept))
        if (targetPath != copy.path) fs.delete(copy.path)
        // Synchrotron leaves one copy per side (.conflict1 / .conflict2): resolving keeps one, drops the others.
        _notes.value.filter { it.conflict && it.id == copy.id && it.path != copy.path }.forEach {
            fs.delete(it.path)
            parsed.remove(it.path)
        }
        parsed.remove(copy.path)
        parsed[targetPath] = fs.lastModified(targetPath) to kept
        _notes.value = _notes.value.filter { it.path != copy.path && it.path != targetPath && !(it.conflict && it.id == copy.id) } + kept
        return kept
    }

    /** Copies every file of the current storage into [target] and optionally empties the source. */
    fun migrateTo(target: Fs, deleteSource: Boolean) {
        fun walk(dir: String) {
            for (e in fs.list(dir)) {
                val p = if (dir.isEmpty()) e.name else "$dir/${e.name}"
                if (e.isDir) walk(p)
                else if (!isTempName(e.name)) {
                    fs.readBytes(p)?.let { target.writeBytes(p, it) }
                    if (deleteSource) fs.delete(p)
                }
            }
        }
        walk("")
    }

    fun buildFsFor(config: StorageConfig): Fs = buildFs(config)

    companion object {
        const val ACTIVITIES_DIR = "activities"
        fun isActivityPath(path: String) = path.startsWith("$ACTIVITIES_DIR/")
        fun isAgendaPath(path: String) = path.startsWith("${app.memento.agenda.EventFormat.AGENDA_DIR}/")
        private val WATCHED_FOLDERS = setOf(ACTIVITIES_DIR, app.memento.contacts.ContactFormat.DIR, app.memento.agenda.EventFormat.AGENDA_DIR)
        private val CONFLICT_COPY = Regex(""".+\.md\.conflict\d+""")

        /** The files (scan, attachments) of [note] no other note of [all] lists: a sync conflict copy shares its original's. */
        fun ownedFiles(note: Note, all: List<Note>): List<String> {
            val shared = all.filter { it.path != note.path }.flatMap { listOfNotNull(it.pdf) + it.attachments }.toSet()
            return (listOfNotNull(note.pdf) + note.attachments).filter { it !in shared }
        }
    }
}
