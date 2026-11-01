package app.scriptorium

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
    private val _activitySettings = MutableStateFlow(app.scriptorium.activity.ActivitySettings())
    val activitySettings: StateFlow<app.scriptorium.activity.ActivitySettings> = _activitySettings

    fun saveActivitySettings(s: app.scriptorium.activity.ActivitySettings) {
        fs.writeText(app.scriptorium.activity.ActivitySettings.PATH, s.toJson())
        _activitySettings.value = s
    }

    private val parsed = HashMap<String, Pair<Long, Note>>() // path -> (lastModified, note)
    private var observers: List<FileObserver> = emptyList()
    var fs: Fs = buildFs(settings.storage)
        private set

    private fun buildFs(config: StorageConfig): Fs = when (config) {
        StorageConfig.Default -> FileFs(Settings.defaultDir())
        is StorageConfig.Tree -> SafFs(context, config.uri)
    }

    fun reconfigure() {
        fs = buildFs(settings.storage)
        parsed.clear()
        _notes.value = emptyList()
        _activities.value = emptyList()
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
        fs.readText(app.scriptorium.activity.ActivitySettings.PATH)?.let { _activitySettings.value = app.scriptorium.activity.ActivitySettings.parse(it) }
        _notes.value = result
        _activities.value = acts
    }

    fun startWatching() {
        stopWatching()
        val fileFs = fs as? FileFs ?: return
        val mask = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MODIFY or
            FileObserver.MOVED_FROM or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE
        val dir = fileFs.root
        if (!dir.isDirectory) return
        fun observe(d: java.io.File): FileObserver = object : FileObserver(d.absolutePath, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (path != null && isNoteFile(path)) onExternalChange?.invoke()
            }
        }.also { it.startWatching() }
        observers = listOfNotNull(
            observe(dir),
            java.io.File(dir, ACTIVITIES_DIR).takeIf { it.isDirectory }?.let { observe(it) },
        )
    }

    fun stopWatching() {
        observers.forEach { it.stopWatching() }
        observers = emptyList()
    }

    /** Called (from an arbitrary thread) when a `.md` file changed on disk. */
    var onExternalChange: (() -> Unit)? = null

    /** A note, or a conflict copy left by the sync tool (`name.md.conflict1`, `name.md.conflict2`). */
    private fun isNoteFile(name: String) = name.endsWith(".md") || CONFLICT_COPY.matches(name)

    fun existingLabels(): List<String> =
        _notes.value.flatMap { it.labels }.distinct().sortedBy { it.fold() }

    // ---- writes ----

    /** Writes [note] to disk (creating its file on first save) and updates the index. */
    fun save(note: Note): Note {
        var n = note
        if (n.path.isEmpty()) {
            val ids = (_notes.value + _activities.value).map { it.id }.toSet()
            var id = n.id
            while (id in ids || id.isEmpty()) id = NoteFormat.generateId()
            n = n.copy(id = id, path = NoteFormat.fileNameFor(n.title, id), hasId = true)
        }
        n = n.copy(hasId = true)
        fs.writeText(n.path, NoteFormat.serialize(n))
        val modified = fs.lastModified(n.path)
        parsed[n.path] = modified to n
        if (isActivityPath(n.path)) _activities.value = _activities.value.filter { it.path != n.path } + n
        else _notes.value = _notes.value.filter { it.path != n.path } + n
        return n
    }

    fun readRaw(path: String): String? = fs.readText(path)

    fun delete(note: Note) {
        fs.delete(note.path)
        note.pdf?.let { fs.delete(it) }
        note.attachments.forEach { fs.delete(it) }
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
        private val CONFLICT_COPY = Regex(""".+\.md\.conflict\d+""")
    }
}
