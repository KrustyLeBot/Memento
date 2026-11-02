package app.memento

import app.memento.suite.SuiteUpdater
import app.memento.suite.SuiteUpdates
import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import app.memento.scan.ScanFormat
import app.memento.scan.ScanImages
import app.memento.scan.ScanPdf
import app.memento.scan.PdfPages
import app.memento.activity.ActivityController
import app.memento.contacts.ContactsController
import app.memento.agenda.AgendaController
import app.memento.record.Recorder
import app.memento.ui.ListMemory
import app.memento.sync.SkipReason
import app.memento.sync.SyncState
import app.memento.sync.SynchrotronClient

/** State of the one-time import from a former folder (Scriptorium, Griffonote). */
sealed class LegacyImport {
    data class Offer(val files: Int, val bytes: Long) : LegacyImport()
    data class Copying(val done: Int, val total: Int) : LegacyImport()
    data class Copied(val files: Int) : LegacyImport()
    data class Failed(val message: String) : LegacyImport()
}

/** Everything the editor can change, for change detection and autosave. */
data class EditorSnapshot(
    val title: String,
    val text: String,
    val items: List<CheckItem>,
    val after: String,
    val color: String,
    val pinned: Boolean,
    val labels: List<String>,
    val attachments: List<String>,
)

class EditorState(note: Note?, val isNew: Boolean) {
    /** Last version known to be on disk (carries id, dates, extra frontmatter, reminder...). */
    var base: Note? = note

    var title by mutableStateOf(note?.title.orEmpty())
    var text by mutableStateOf(TextFieldValue(""))
    val items = mutableStateListOf<CheckItem>()
    var after by mutableStateOf("")
    var color by mutableStateOf(note?.color ?: DEFAULT_COLOR)
    var pinned by mutableStateOf(note?.pinned ?: false)
    var labels by mutableStateOf(note?.labels.orEmpty())
    val attachments = mutableStateListOf<String>().apply { addAll(note?.attachments.orEmpty()) }
    var checkedExpanded by mutableStateOf(false)
    var pendingFocusId by mutableStateOf<Long?>(null)
    var focusTitle by mutableStateOf(false)
    var externalConflict by mutableStateOf(false)
    /** Bumped when [base]'s date (agenda keys) changes, so the editor redraws its date chip. */
    var dateVersion by mutableStateOf(0)

    var lastSnapshot: EditorSnapshot? = null
    var loadedRaw: String? = null

    val conflict: Boolean get() = base?.conflict == true

    init {
        if (note != null) applyBody(note.body)
        lastSnapshot = if (note != null) snapshot() else null
    }

    fun applyBody(body: String) {
        val parts = BodyFormat.parse(body)
        text = TextFieldValue(parts.before, TextRange(parts.before.length))
        items.clear()
        items.addAll(parts.items)
        after = parts.after
    }

    fun body(): String = BodyFormat.build(BodyParts(text.text, items.toList(), after))

    fun snapshot() = EditorSnapshot(title, text.text, items.toList(), after, color, pinned, labels, attachments.toList())

    fun reloadFrom(note: Note) {
        base = note
        title = note.title
        color = note.color
        pinned = note.pinned
        labels = note.labels
        attachments.clear()
        attachments.addAll(note.attachments)
        applyBody(note.body)
        lastSnapshot = snapshot()
    }

    val isEmpty: Boolean get() = title.isBlank() && body().isBlank() && attachments.isEmpty()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    val repo = NoteRepository(app, settings)
    val notes = repo.notes

    private val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val persistLock = Mutex()

    /** The "Activités" folder (import, recording, detail). */
    /** Where back goes: the screens the current one was opened from (see [Origins]). */
    val origins = Origins()

    private var shown by mutableStateOf(rootOf(settings.lastModule.takeUnless { it in settings.hiddenModules } ?: Module.NOTES))

    /** The screen shown; every change is remembered as an origin, so back returns where the user came from. */
    var screen: Screen
        get() = shown
        set(value) {
            origins.moved(shown, value)
            shown = value
        }

    /** Scroll positions, filters and tapped items of the module lists, kept while their screen is not shown. */
    val lists = ListMemory()

    /** The app-level snackbar's messages, shown one at a time by MainActivity's frame (ui/AppFrame). */
    val snacks = SnackQueue()

    fun say(snack: Snack) = snacks.post(snack)

    val activities = ActivityController(app, repo, viewModelScope, io, { say(it) }, { uri -> copyIn(uri) }, { repo.activitySettings.value.refineAltitude }, syncing = { syncing }).also { c ->
        c.onOpen = { screen = Screen.ACTIVITY }
    }

    /** The Contacts module (list, detail, editor, import, mirror into the phone). */
    val contacts = ContactsController(app, repo, viewModelScope, io, { say(it) }, { screen = it }, { goBack() }, syncing = { syncing })

    /** The Agenda module (views, event editor, import/export, reminders and widgets kept up to date). */
    val agenda = AgendaController(
        app, repo, viewModelScope, io, contacts.contacts, { say(it) }, { screen = it }, { goBack() },
        openNote = { n -> openNote(n) },
        openActivity = { activities.open(it) },
        openContact = { id ->
            // a birthday tapped in a notification or a widget at cold start: the contacts are read after the notes
            contacts.whenScanned {
                val c = contacts.book.byId(id)
                if (c == null) say(Snack("Contact introuvable")) else contacts.open(c)
            }
        },
        syncing = { syncing },
    )

    /** Back to the screen the current one was opened from, else to its module root. */
    fun goBack() {
        val fallback = shown.home ?: rootOf(settings.lastModule.takeUnless { it in hiddenModules } ?: Module.NOTES)
        val to = origins.pop(shown, fallback)
        shown = to
    }

    /** The next screen is opened by a notification or a widget at a cold start: back from it leads to its module root. */
    fun openedFromOutside() = origins.forgetNextOrigin(System.currentTimeMillis())
    var hiddenModules by mutableStateOf(settings.hiddenModules)
        private set
    var legacyImport by mutableStateOf<LegacyImport?>(null)
    var editor by mutableStateOf<EditorState?>(null)
    var labelFilter by mutableStateOf<String?>(null)
    var query by mutableStateOf("")

    var gridView by mutableStateOf(settings.gridView)
    var scanFormat by mutableStateOf(settings.scanFormat)
    /** A scan is being turned into a PDF note. */
    var savingScan by mutableStateOf(false)
        private set
    var theme by mutableStateOf(settings.theme)
    var storageLabel by mutableStateOf("")
    var storageReady by mutableStateOf(false)
    var pendingStorage by mutableStateOf<StorageConfig?>(null)
    val updates = SuiteUpdates(app, SuiteUpdater(app, "KrustyLeBot/Memento", "${app.packageName}.fileprovider"), viewModelScope)

    private var scanJob: Job? = null
    private var externalJob: Job? = null

    /** A sync with Proton Drive (via Synchrotron) is running: notes are read-only until it ends. */
    var syncing by mutableStateOf(false)
        private set
    private var stopSync: (() -> Unit)? = null

    /** Short result line of the last sync ("3 changements"), shown in the banner and gone after a few seconds. */
    var syncNotice by mutableStateOf<String?>(null)
        private set
    private var noticeJob: Job? = null

    private fun showSyncNotice(text: String) {
        syncNotice = text
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(4000)
            syncNotice = null
        }
    }

    init {
        repo.onExternalChange = {
            viewModelScope.launch {
                // a sync writes files in bursts: one rescan once they stop, not one per file
                externalJob?.cancel()
                externalJob = coroutineContext[Job]
                delay(400)
                refresh()
            }
        }
        // a folder created after the watch started (first contact, first event of a new year, synced in)
        repo.onFolderAdded = { viewModelScope.launch { repo.rewatch() } }
        storageReady = checkStorageReady()
        if (storageReady) refresh()
        restoreDrafts()
    }

    // ---- storage ----

    fun checkStorageReady(): Boolean = when (val c = settings.storage) {
        StorageConfig.Default ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
            else ContextCompat.checkSelfPermission(
                getApplication(), Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        is StorageConfig.Tree ->
            getApplication<Application>().contentResolver.persistedUriPermissions
                .any { it.uri == c.uri && it.isWritePermission }
    }

    fun onResume() {
        val ready = checkStorageReady()
        if (ready != storageReady) storageReady = ready
        if (ready) {
            Recorder.recover(getApplication<Application>().filesDir)
            refresh()
            repo.startWatching()
            checkExternal()
            syncOnOpen()
            checkLegacyFolder()
        }
    }

    // ---- Proton Drive sync through Synchrotron ----

    /**
     * Asks Synchrotron to sync now, so the notes are up to date when the app opens. The list stays
     * visible but nothing can be opened or created (read-only) until the sync ends or times out.
     * Never while a note is being edited.
     */
    private fun syncOnOpen() {
        if (syncing || screen == Screen.EDITOR || screen == Screen.EVENT_EDIT || screen == Screen.CONTACT_EDIT || settings.storage !is StorageConfig.Default) return
        stopSync?.invoke()
        stopSync = SynchrotronClient.syncOnOpen(getApplication(), Settings.defaultDir().path) { state ->
            when (state) {
                SyncState.Syncing -> { syncing = true; syncNotice = null }
                is SyncState.Done -> {
                    syncing = false
                    if (state.changes > 0) refresh()
                    when {
                        state.hasConflicts -> showSyncNotice("Conflits de synchro : ouvre les notes marquées")
                        state.changes > 0 -> showSyncNotice("Synchronisé · ${state.changes} changement${if (state.changes > 1) "s" else ""}")
                    }
                }
                is SyncState.Failed -> { syncing = false; showSyncNotice("Échec de la synchro") }
                SyncState.TimedOut -> { syncing = false; showSyncNotice("Synchro longue : elle continue en arrière-plan") }
                is SyncState.Skipped -> {
                    syncing = false
                    if (state.reason == SkipReason.NEEDS_SETUP) showSyncNotice("Termine la configuration dans Synchrotron")
                }
            }
        }
    }

    override fun onCleared() {
        stopSync?.invoke()
        contacts.close()
    }

    fun onPause() {
        repo.stopWatching()
        flushEditor()
        agenda.keepDraft()
        contacts.keepDraft()
    }

    fun refresh() {
        if (!storageReady) return
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            withContext(io) {
                (repo.fs as? FileFs)?.root?.mkdirs()
                repo.scan()
                contacts.scan()
                storageLabel = repo.fs.describe()
            }
        }
    }

    fun requestStorageChange(config: StorageConfig) {
        pendingStorage = config
    }

    fun confirmStorageChange(move: Boolean) {
        val target = pendingStorage ?: return
        pendingStorage = null
        viewModelScope.launch {
            withContext(io) {
                if (move) repo.migrateTo(repo.buildFsFor(target), deleteSource = true)
                settings.storageUri = (target as? StorageConfig.Tree)?.uri?.toString()
                repo.stopWatching()
                repo.reconfigure()
            }
            storageReady = checkStorageReady()
            refresh()
            repo.startWatching()
        }
    }

    // ---- settings ----

    fun changeGridView(v: Boolean) { gridView = v; settings.gridView = v }
    fun changeTheme(v: ThemeMode) { theme = v; settings.theme = v }
    fun changeScanFormat(v: ScanFormat) { scanFormat = v; settings.scanFormat = v }

    // ---- scans ----

    /** Turns the pages returned by the document scanner into a note holding a small PDF, then opens it. */
    fun saveScan(pages: List<Uri>) {
        if (pages.isEmpty() || savingScan) return
        savingScan = true
        viewModelScope.launch {
            try {
                val note = withContext(io) {
                    val app = getApplication<Application>()
                    val pdf = ScanPdf.build(pages.map { ScanImages.compress(app, it) }, scanFormat)
                    val now = System.currentTimeMillis()
                    val title = "Scan du " + java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.FRANCE).format(java.util.Date(now))
                    var id = NoteFormat.generateId()
                    while (notes.value.any { it.id == id }) id = NoteFormat.generateId()
                    val pdfPath = "attachments/" + NoteFormat.fileNameFor(title, id).removeSuffix(".md") + ".pdf"
                    repo.fs.writeBytes(pdfPath, pdf)
                    repo.save(Note(path = "", id = id, title = title, created = now, updated = now, pdf = pdfPath))
                }
                openNote(note)
                say(Snack("Scan enregistré (${pages.size} page${if (pages.size > 1) "s" else ""})"))
            } catch (e: Exception) {
                say(Snack("Scan impossible : ${e.message}"))
            } finally {
                savingScan = false
            }
        }
    }

    /** PDF shown by the in-app viewer ([Screen.PDF]). */
    var viewingPdf by mutableStateOf<String?>(null)
        private set

    fun showPdf(path: String) {
        flushEditor()
        viewingPdf = path
        screen = Screen.PDF
    }

    /** Opens the PDF for the in-app viewer; the caller closes it. */
    suspend fun openPdfPages(path: String): PdfPages = withContext(io) {
        val fd = when (val fs = repo.fs) {
            is FileFs -> fs.fileOf(path)?.takeIf { path.isNotEmpty() && it.isFile }?.let {
                android.os.ParcelFileDescriptor.open(it, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            }
            is SafFs -> fs.uriOf(path)?.let { getApplication<Application>().contentResolver.openFileDescriptor(it, "r") }
            else -> null
        } ?: throw java.io.FileNotFoundException("PDF introuvable (pas encore synchronisé ?)")
        PdfPages(fd)
    }

    /** Opens the note's PDF in another app (the phone's PDF viewer). */
    fun openPdf(path: String) = openFile(path)

    /** Opens a file of the notes folder in the app that handles its type. */
    fun openFile(path: String) {
        val app = getApplication<Application>()
        val uri = when (val fs = repo.fs) {
            is FileFs -> fs.fileOf(path)?.takeIf { path.isNotEmpty() && it.isFile }?.let {
                androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", it)
            }
            is SafFs -> fs.uriOf(path)
            else -> null
        }
        if (uri == null) { say(Snack("Fichier introuvable (pas encore synchronisé ?)")); return }
        try {
            app.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(path))
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: android.content.ActivityNotFoundException) {
            say(Snack("Aucune application pour ouvrir ce fichier"))
        }
    }

    // ---- attachments ----

    /** Files are being copied into the note. */
    var attaching by mutableStateOf(false)
        private set

    /**
     * Copies the files picked in the gallery or the file manager into `attachments/` and adds them
     * to the open note (saved with it by the autosave).
     */
    fun addAttachments(uris: List<Uri>) {
        val ed = editor ?: return
        if (uris.isEmpty() || attaching) return
        attaching = true
        viewModelScope.launch {
            val failed = ArrayList<String>()
            for (uri in uris) {
                try {
                    val path = withContext(io) { copyIn(uri) }
                    ed.attachments.add(path)
                } catch (e: Exception) {
                    failed.add(e.message ?: "erreur")
                }
            }
            attaching = false
            if (failed.isNotEmpty()) say(Snack("Pièce jointe impossible : ${failed.first()}"))
        }
    }

    internal fun copyIn(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        var name = "fichier"
        var size = -1L
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if (!name.contains('.')) {
            android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri))?.let { name += ".$it" }
        }
        if (size > MAX_ATTACHMENT) throw java.io.IOException("$name dépasse ${MAX_ATTACHMENT / 1_000_000} Mo")
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw java.io.IOException("$name illisible")
        if (bytes.size > MAX_ATTACHMENT) throw java.io.IOException("$name dépasse ${MAX_ATTACHMENT / 1_000_000} Mo")
        var path = NoteFormat.attachmentPath(name)
        while (repo.fs.exists(path)) path = NoteFormat.attachmentPath(name)
        repo.fs.writeBytes(path, bytes)
        return path
    }

    /** Takes the file out of the note and deletes it; the snackbar offers to put it back. */
    fun removeAttachment(path: String) {
        val ed = editor ?: return
        viewModelScope.launch {
            ed.attachments.remove(path)
            persist(ed)
            val bytes = withContext(io) {
                val owner = ed.base?.path.orEmpty()
                if (NoteRepository.deletable(listOf(path), owner, repo.allNotes()).isEmpty()) null
                else repo.fs.readBytes(path).also { repo.deleteOwned(listOf(path), owner) }
            }
            say(Snack("Pièce jointe supprimée") {
                if (bytes != null) viewModelScope.launch {
                    withContext(io) { repo.fs.writeBytes(path, bytes) }
                    if (editor === ed) {
                        if (path !in ed.attachments) ed.attachments.add(path)
                    } else {
                        val note = notes.value.firstOrNull { it.id == ed.base?.id } ?: return@launch
                        withContext(io) { repo.save(note.copy(attachments = note.attachments + path)) }
                    }
                }
            })
        }
    }

    /** A small version of an attached image, or null when it is not an image (or not there yet). */
    suspend fun thumbnail(path: String, maxPx: Int): android.graphics.Bitmap? = withContext(io) {
        if (isDrawing(path)) {
            val svg = repo.fs.readText(path) ?: return@withContext null
            return@withContext app.memento.draw.DrawingBitmap.render(app.memento.draw.Drawing.parse(svg), maxPx)
        }
        if (!isImage(path)) return@withContext null
        val bytes = repo.fs.readBytes(path) ?: return@withContext null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---- drawings ----

    /** The drawing being edited ([Screen.DRAWING]); saved into the open note when the screen is left. */
    var drawing by mutableStateOf<app.memento.draw.DrawingState?>(null)
        private set

    /** Bumped when a drawing file is rewritten, so its thumbnail is drawn again. */
    var drawingVersion by mutableStateOf(0)
        private set

    fun newDrawing() {
        if (editor == null) return
        drawing = app.memento.draw.DrawingState(app.memento.draw.Drawing(emptyList()), null)
        screen = Screen.DRAWING
    }

    fun editDrawing(path: String) {
        viewModelScope.launch {
            val svg = withContext(io) { repo.fs.readText(path) }
            if (svg == null) { say(Snack("Dessin introuvable (pas encore synchronisé ?)")); return@launch }
            drawing = app.memento.draw.DrawingState(app.memento.draw.Drawing.parse(svg), path)
            screen = Screen.DRAWING
        }
    }

    /** Back from the drawing: what was drawn goes into the note (an emptied drawing is removed from it). */
    fun closeDrawing() {
        val d = drawing
        drawing = null
        if (editor != null) screen = Screen.EDITOR else goBack()
        val ed = editor
        if (d == null || ed == null || !d.changed) return
        viewModelScope.launch {
            try {
                val existing = d.path
                if (d.strokes.isEmpty()) {
                    if (existing != null) {
                        ed.attachments.remove(existing)
                        withContext(io) { repo.deleteOwned(listOf(existing), ed.base?.path.orEmpty()) }
                    }
                } else {
                    val svg = app.memento.draw.Drawing(d.strokes).toSvg()
                    val path = withContext(io) {
                        var p = existing ?: app.memento.draw.Drawing.newPath { NoteFormat.generateId() }
                        while (existing == null && repo.fs.exists(p)) p = app.memento.draw.Drawing.newPath { NoteFormat.generateId() }
                        repo.fs.writeText(p, svg)
                        p
                    }
                    if (path !in ed.attachments) ed.attachments.add(path)
                    drawingVersion++
                }
                persist(ed)
            } catch (e: Exception) {
                say(Snack("Dessin non enregistré : ${e.message}"))
            }
        }
    }

    // ---- navigation ----

    fun selectLabel(label: String?) {
        labelFilter = label
        query = ""
        screen = Screen.LIST
    }

    fun openSettings() { checkLegacyLeft(); screen = Screen.SETTINGS }

    fun changeActivitySettings(s: app.memento.activity.ActivitySettings) {
        repo.activitySettings.value.let { if (it == s) return }
        viewModelScope.launch { withContext(io) { repo.saveActivitySettings(s) } }
    }

    fun openActivities() = openModule(Module.ACTIVITIES)

    private fun rootOf(m: Module) = when (m) {
        Module.NOTES -> Screen.LIST
        Module.AGENDA -> Screen.AGENDA
        Module.ACTIVITIES -> Screen.ACTIVITIES
        Module.CONTACTS -> Screen.CONTACTS
    }

    fun openModule(m: Module) {
        flushEditor()
        screen = rootOf(m)
        settings.lastModule = m
    }

    val visibleModules: List<Module> get() = Module.entries.filter { it !in hiddenModules }

    fun setModuleVisible(m: Module, visible: Boolean) {
        if (m !in Module.hideable) return
        hiddenModules = if (visible) hiddenModules - m else hiddenModules + m
        settings.hiddenModules = hiddenModules
    }

    // ---- one-time import from a former folder (Scriptorium, Griffonote) ----

    /** Offers the import when the old folder has files and the new one has none. */
    fun checkLegacyFolder() {
        if (legacyImport != null || settings.legacyImportDismissed || settings.storage !is StorageConfig.Default) return
        viewModelScope.launch {
            val offer = withContext(Dispatchers.IO) {
                val old = Settings.legacyDir()
                withContext(Dispatchers.Main) { legacyName = old.name }
                if (LegacyFolder.needsImport(old, Settings.defaultDir())) LegacyFolder.summary(old) else null
            }
            if (offer != null && legacyImport == null) legacyImport = LegacyImport.Offer(offer.files, offer.bytes)
        }
    }

    /** Name of the former folder offered for import ("Scriptorium" or "Griffonote"). */
    var legacyName by mutableStateOf("Scriptorium")
        private set

    /** The former folder still exists and every file of it has a copy here. */
    var legacyFolderLeft by mutableStateOf(false)
        private set

    fun checkLegacyLeft() {
        viewModelScope.launch {
            legacyFolderLeft = withContext(Dispatchers.IO) {
                val old = Settings.legacyDir()
                withContext(Dispatchers.Main) { legacyName = old.name }
                old.isDirectory && settings.storage is StorageConfig.Default && !LegacyFolder.needsImport(old, Settings.defaultDir())
            }
        }
    }

    fun dismissLegacyImport() {
        if (legacyImport is LegacyImport.Offer) settings.legacyImportDismissed = true
        legacyImport = null
    }

    fun importLegacyFolder() {
        legacyImport = LegacyImport.Copying(0, 0)
        viewModelScope.launch {
            legacyImport = try {
                val s = withContext(Dispatchers.IO) {
                    LegacyFolder.copy(Settings.legacyDir(), Settings.defaultDir()) { done, total ->
                        if (done == total || done % 20 == 0) viewModelScope.launch { legacyImport = LegacyImport.Copying(done, total) }
                    }
                }
                LegacyImport.Copied(s.files)
            } catch (e: Exception) {
                LegacyImport.Failed(e.message ?: e.toString())
            }
            refresh()
        }
    }

    fun deleteLegacyFolder() {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { LegacyFolder.deleteIfCopied(Settings.legacyDir(), Settings.defaultDir()) }.exceptionOrNull()
            }
            settings.legacyImportDismissed = true
            legacyImport = null
            checkLegacyLeft()
            say(Snack(if (error == null) "Ancien dossier supprimé" else "Suppression impossible : ${error.message}"))
        }
    }
    /** The record screen scrolls to its offline maps (the map-download notification), then clears this. */
    var showPreparedRoutes by mutableStateOf(false)

    fun openRecord() {
        flushEditor()
        screen = Screen.RECORD
    }
    fun openActivity(note: Note) = activities.open(note)

    fun back() {
        when (screen) {
            Screen.EDITOR -> closeEditor()
            Screen.SETTINGS, Screen.AGENDA_CALENDARS, Screen.RECORD -> goBack()
            Screen.ACTIVITIES, Screen.AGENDA, Screen.CONTACTS -> openModule(Module.NOTES)
            Screen.ACTIVITY -> { activities.close(); goBack() }
            Screen.EVENT -> agenda.closeDetail()
            Screen.EVENT_EDIT -> agenda.cancelEditor()
            Screen.CONTACT, Screen.CONTACT_EDIT, Screen.CONTACT_DUPLICATES -> contacts.back()
            Screen.DRAWING -> closeDrawing()
            Screen.PDF -> { viewingPdf = null; goBack() }
            Screen.LIST -> when {
                query.isNotEmpty() -> query = ""
                labelFilter != null -> selectLabel(null)
            }
        }
    }

    // ---- editor ----

    fun newNote(title: String = "", text: String = "", checklist: Boolean = false) {
        if (syncing) return // the banner already says why
        val ed = EditorState(null, isNew = true)
        ed.title = title
        if (checklist) {
            val lines = text.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) ed.items.add(CheckItem(ItemIds.next(), "", false).also { ed.pendingFocusId = it.id })
            else lines.forEach { ed.items.add(CheckItem(ItemIds.next(), it.trim(), false)) }
        } else {
            ed.text = TextFieldValue(text, TextRange(text.length))
            ed.focusTitle = title.isEmpty() && text.isEmpty()
        }
        editor = ed
        screen = Screen.EDITOR
    }

    fun openNote(note: Note) {
        if (syncing) return // the banner already says why
        val ed = EditorState(note, isNew = false)
        ed.loadedRaw = note.let { repo.fs.readText(it.path) }
        editor = ed
        screen = Screen.EDITOR
    }

    /** Text shared from another app (e.g. Google Keep's "Send"): becomes a new note. */
    fun handleShare(subject: String?, text: String?) {
        if (text.isNullOrBlank() && subject.isNullOrBlank()) return
        if (syncing) {
            // read-only while the sync runs: the shared text waits for its end instead of being lost
            viewModelScope.launch {
                androidx.compose.runtime.snapshotFlow { syncing }.first { !it }
                handleShare(subject, text)
            }
            return
        }
        flushEditor()
        val ed = EditorState(null, isNew = true)
        ed.title = subject.orEmpty().trim()
        ed.applyBody(BodyFormat.fromShared(text.orEmpty()).trim('\n'))
        editor = ed
        screen = Screen.EDITOR
    }

    private fun buildNote(ed: EditorState, snap: EditorSnapshot, body: String): Note {
        val now = System.currentTimeMillis()
        val b = ed.base
        return if (b == null) {
            Note(
                path = "", id = NoteFormat.generateId(), title = snap.title,
                color = snap.color, pinned = snap.pinned, labels = snap.labels,
                created = now, updated = now, body = body, attachments = snap.attachments,
            )
        } else {
            b.copy(
                title = snap.title, color = snap.color, pinned = snap.pinned,
                labels = snap.labels, body = body, updated = now, attachments = snap.attachments,
            )
        }
    }

    /** Writes the editor to disk if something changed. */
    suspend fun persist(ed: EditorState) {
        val snap = ed.snapshot()
        val body = ed.body()
        if (snap == ed.lastSnapshot) return
        if (ed.base == null && snap.title.isBlank() && body.isBlank() && snap.attachments.isEmpty()) return
        persistLock.withLock {
            val note = buildNote(ed, snap, body)
            try {
                val saved = withContext(io) {
                    repo.save(note).also { ed.loadedRaw = repo.readRaw(it.path) }
                }
                ed.base = saved
                ed.lastSnapshot = snap
            } catch (e: Exception) {
                say(Snack("Échec de l'enregistrement : ${e.message}"))
            }
        }
    }

    /**
     * "Ajouter à l'agenda" on the open note: [start] (a date, or a date-time in the device zone) and its
     * reminders; null removes the date (and the old `reminder` field). The note stays in the notes list.
     */
    fun setNoteDate(start: app.memento.agenda.Stamp?, alerts: List<Int>) {
        val ed = editor ?: return
        viewModelScope.launch {
            persist(ed)
            persistLock.withLock {
                try {
                    val base = ed.base ?: buildNote(ed, ed.snapshot(), ed.body())
                    val dated = agenda.datedNote(base, start, alerts).copy(updated = System.currentTimeMillis())
                    val saved = withContext(io) { repo.save(dated).also { ed.loadedRaw = repo.readRaw(it.path) } }
                    ed.base = saved
                    ed.lastSnapshot = ed.snapshot()
                    ed.dateVersion++
                    say(Snack(if (start == null) "Retirée de l'agenda" else "Ajoutée à l'agenda"))
                } catch (e: Exception) {
                    say(Snack("Échec de l'enregistrement : ${e.message}"))
                }
            }
        }
    }

    fun flushEditor() {
        val ed = editor ?: return
        if (screen != Screen.EDITOR) return
        viewModelScope.launch { persist(ed) }
    }

    /**
     * A notification, a widget or another app takes the user elsewhere: the open note (and its drawing) is
     * saved and closed first, as back would do, instead of being left unsaved behind the new screen.
     */
    fun leaveEditors() {
        when (screen) {
            Screen.DRAWING -> { closeDrawing(); if (screen == Screen.EDITOR) closeEditor() }
            Screen.EDITOR -> closeEditor()
            Screen.EVENT_EDIT -> agenda.leaveEditor()
            Screen.CONTACT_EDIT -> contacts.leaveEditorForIntent()
            else -> {}
        }
    }

    /** Event and contact editors left open when the process was killed: back, once the folder was read. */
    private fun restoreDrafts() {
        agenda.whenLoaded {
            contacts.whenScanned {
                val target = when {
                    agenda.restoreDraft() -> Screen.EVENT_EDIT
                    contacts.restoreDraft() -> Screen.CONTACT_EDIT
                    else -> return@whenScanned
                }
                // an intent (notification, widget) already took the user somewhere: offer it instead of taking over
                if (screen.module != null) screen = target
                else say(Snack("Brouillon retrouvé", action = "Reprendre") { screen = target })
            }
        }
    }

    fun closeEditor() {
        val ed = editor
        goBack()
        editor = null
        if (ed != null) viewModelScope.launch { persist(ed) }
    }

    /** Called on resume and when a file changed: reload or ask, never overwrite silently. */
    fun checkExternal() {
        val ed = editor ?: return
        val path = ed.base?.path ?: return
        viewModelScope.launch {
            val raw = withContext(io) { repo.readRaw(path) } ?: return@launch
            if (raw == ed.loadedRaw) return@launch
            val dirty = ed.snapshot() != ed.lastSnapshot
            val modified = withContext(io) { repo.fs.lastModified(path) }
            val note = NoteFormat.parse(path, raw, modified)
            if (!dirty) {
                ed.reloadFrom(note)
                ed.loadedRaw = raw
            } else {
                ed.externalConflict = true
            }
        }
    }

    fun resolveExternal(keepMine: Boolean) {
        val ed = editor ?: return
        ed.externalConflict = false
        viewModelScope.launch {
            val path = ed.base?.path ?: return@launch
            val raw = withContext(io) { repo.readRaw(path) } ?: return@launch
            if (keepMine) {
                ed.loadedRaw = raw
                ed.base = ed.base?.let { b ->
                    NoteFormat.parse(path, raw, 0L).let { disk -> b.copy(extra = disk.extra) }
                }
                ed.lastSnapshot = null // force the write
                persist(ed)
            } else {
                val modified = withContext(io) { repo.fs.lastModified(path) }
                ed.reloadFrom(NoteFormat.parse(path, raw, modified))
                ed.loadedRaw = raw
            }
        }
    }

    /** Deletes the open note for good; the snackbar offers a short-lived undo. */
    fun deleteEditorNote() {
        val ed = editor ?: return
        goBack()
        editor = null
        viewModelScope.launch {
            persistLock.withLock { }
            // Attached since the last save: no note refers to them.
            val unsaved = ed.attachments.filter { it !in ed.base?.attachments.orEmpty() }
            if (unsaved.isNotEmpty()) withContext(io) { repo.deleteOwned(unsaved, ed.base?.path.orEmpty()) }
            val note = ed.base ?: return@launch
            val files = withContext(io) {
                NoteRepository.ownedFiles(note, repo.allNotes()).mapNotNull { p -> repo.fs.readBytes(p)?.let { p to it } }
            }
            withContext(io) { repo.delete(note) }
            say(Snack("Note supprimée") {
                viewModelScope.launch {
                    withContext(io) {
                        files.forEach { (p, bytes) -> repo.fs.writeBytes(p, bytes) }
                        repo.save(note)
                    }
                }
            })
        }
    }

    fun keepConflictCopy() {
        val ed = editor ?: return
        viewModelScope.launch {
            persist(ed)
            val note = ed.base ?: return@launch
            val kept = withContext(io) { repo.keepConflictCopy(note) }
            ed.reloadFrom(kept)
            say(Snack("Cette version a remplacé l'originale"))
        }
    }

    fun convertToChecklist() {
        val ed = editor ?: return
        if (ed.items.isEmpty()) {
            val lines = ed.text.text.lines().map { it.trim().removePrefix("- ").trim() }.filter { it.isNotEmpty() }
            ed.text = TextFieldValue("")
            if (lines.isEmpty()) {
                ed.items.add(CheckItem(ItemIds.next(), "", false).also { ed.pendingFocusId = it.id })
            } else {
                lines.forEach { ed.items.add(CheckItem(ItemIds.next(), it, false)) }
            }
        } else {
            val asText = ed.items.joinToString("\n") { "- ${it.text}" }
            val merged = listOf(ed.text.text.trimEnd(), asText).filter { it.isNotEmpty() }.joinToString("\n\n")
            ed.items.clear()
            ed.text = TextFieldValue(merged, TextRange(merged.length))
        }
    }

    fun allLabels(): List<String> =
        notes.value.flatMap { it.labels }.distinct().sortedBy { it.fold() }

    fun pickedTree(uri: Uri) {
        requestStorageChange(StorageConfig.Tree(uri))
    }
}

/** Above this, a file is refused: it is copied in memory, then synced to every device. */
const val MAX_ATTACHMENT = 50_000_000L

/** A sketch made in the app: `drawings/<name>.svg`. */
fun isDrawing(path: String) = path.startsWith("drawings/") && path.endsWith(".svg")

fun isImage(path: String) = path.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")

fun mimeOf(path: String): String =
    android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(path.substringAfterLast('.', "").lowercase()) ?: "*/*"
