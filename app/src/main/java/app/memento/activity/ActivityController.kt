package app.memento.activity

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.memento.Note
import app.memento.NoteFormat
import app.memento.NoteRepository
import app.memento.SafePath
import app.memento.Snack
import app.memento.record.Recorder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A file read from the picker, waiting for the user to confirm its name and sport. */
class PendingImport(
    val bytes: ByteArray, val extension: String, val track: Track, val sport: Sport, val title: String,
    /** The same recording was imported before: confirming replaces it (name, description and photos are kept). */
    val existing: Note? = null,
    /** The file has no altitude: offer to look it up in the terrain model. */
    val needsAltitude: Boolean = false,
)

/** An opened activity: its note and what the track file says. */
class ActivityDetail(val note: Note, val track: Track?, val analysis: Analysis?, val error: String? = null) {
    val sport: Sport get() = note.activity?.sport ?: Sport.GPS
}

/** Everything the "Activités" folder does besides drawing: import, open, edit, delete, save a recording. */
class ActivityController(
    private val app: Application,
    private val repo: NoteRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val say: (Snack) -> Unit,
    private val copyIn: (Uri) -> String,
    private val refineAltitude: () -> Boolean = { false },
    /** A sync is running: the folder is read-only until it ends (the banner says why). Compose state. */
    private val syncing: () -> Boolean = { false },
) {
    private fun readOnly(): Boolean {
        if (syncing()) say(Snack("Synchronisation en cours : réessaie dans un instant"))
        return syncing()
    }

    val activities = repo.activities

    var pending by mutableStateOf<PendingImport?>(null)
        private set
    var detail by mutableStateOf<ActivityDetail?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /** Called when an activity is opened, so the app can show its screen. */
    var onOpen: (() -> Unit)? = null

    // ---- import ----

    /** Reads a FIT or GPX file and asks the user to confirm. Nothing is written yet. */
    fun startImport(uri: Uri) {
        if (busy || readOnly()) return
        busy = true
        scope.launch {
            try {
                val p = withContext(io) {
                    val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw java.io.IOException("Fichier illisible")
                    if (bytes.size > 50_000_000) throw java.io.IOException("Fichier trop gros")
                    val track: Track
                    val ext: String
                    if (Fit.looksLikeFit(bytes)) {
                        track = Fit.read(bytes); ext = "fit"
                    } else {
                        val text = String(bytes, Charsets.UTF_8)
                        if (!Gpx.looksLikeGpx(text)) throw Fit.FitException("Format non reconnu : il faut un fichier FIT ou GPX")
                        track = Gpx.read(text); ext = "gpx"
                    }
                    if (track.points.isEmpty() && track.sessionDistance == null) throw Fit.FitException("Aucune position dans ce fichier")
                    val existing = track.startTime?.let { st ->
                        repo.activities.value.firstOrNull { n -> n.activity?.let { abs(it.start - st) < 2000 } == true }
                    }
                    var sport = Sport.guess(track.fitSport)
                    if (existing != null && sport == Sport.GPS) sport = existing.activity?.sport ?: sport
                    val title = existing?.title ?: defaultTitle(sport, track.startTime ?: System.currentTimeMillis(), track.name)
                    PendingImport(bytes, ext, track, sport, title, existing, Elevation.missing(track) && sport.hasGps)
                }
                pending = p
            } catch (e: Exception) {
                say(Snack("Import impossible : ${e.message}"))
            } finally {
                busy = false
            }
        }
    }

    fun cancelImport() { pending = null }

    fun confirmImport(title: String, sport: Sport, useRelief: Boolean) {
        val p = pending ?: return
        if (readOnly()) return
        pending = null
        busy = true
        scope.launch {
            try {
                var note = withContext(io) { saveNew(p.track, sport, title.trim().ifEmpty { p.title }, p.bytes, p.extension, p.existing) }
                if (useRelief && p.needsAltitude) note = lookUpAltitude(note, quiet = false)
                open(note)
            } catch (e: Exception) {
                say(Snack("Import impossible : ${e.message}"))
            } finally {
                busy = false
            }
        }
    }

    /** Writes the track file and the note. Blocking: call from the IO dispatcher. */
    fun saveNew(track: Track, sport: Sport, title: String, bytes: ByteArray, extension: String, replace: Note? = null, altMarker: String? = null): Note {
        val a = Analyzer.analyze(track, sport)
        val now = System.currentTimeMillis()
        val ids = (repo.notes.value + repo.activities.value).map { it.id }.toSet()
        var id = replace?.id ?: NoteFormat.generateId()
        if (replace == null) while (id in ids) id = NoteFormat.generateId()
        val file = "attachments/" + NoteFormat.slug(title) + "-$id.$extension"
        repo.fs.writeBytes(file, bytes)
        val info = ActivityInfo.from(sport, a, track, file, altMarker)
        if (replace != null) {
            // the old track and its altitude file go; photos and the description stay
            val old = listOfNotNull(replace.activity?.file, replace.activity?.alt).filter { SafePath.owned(it) }
            repo.deleteOwned(old.filter { it != file }, replace.path)
            return repo.save(replace.copy(title = title, activity = info, attachments = listOf(file) + replace.attachments.filter { it !in old }, updated = now))
        }
        return repo.save(
            Note(
                path = NoteRepository.ACTIVITIES_DIR + "/" + NoteFormat.fileNameFor(title, id), id = id, title = title,
                created = now, updated = now, activity = info, attachments = listOf(file),
            ),
        )
    }

    /** A time-only activity (treadmill, gym…), possibly with a distance typed by hand. Blocking. */
    private fun saveManualNow(title: String, sport: Sport, start: Long, seconds: Long, distance: Double): Note {
        val now = System.currentTimeMillis()
        val ids = (repo.notes.value + repo.activities.value).map { it.id }.toSet()
        var id = NoteFormat.generateId()
        while (id in ids) id = NoteFormat.generateId()
        return repo.save(
            Note(
                path = NoteRepository.ACTIVITIES_DIR + "/" + NoteFormat.fileNameFor(title, id), id = id, title = title,
                created = now, updated = now,
                activity = ActivityInfo(sport, start, seconds, seconds, distance),
            ),
        )
    }

    /** Edits the distance of an activity without a track (typed after the fact). */
    fun setManualDistance(note: Note, meters: Double) {
        val info = note.activity ?: return
        if (info.file != null || readOnly()) return
        scope.launch {
            val saved = withContext(io) {
                repo.save(note.copy(activity = info.copy(distance = meters), updated = System.currentTimeMillis()))
            }
            detail = ActivityDetail(saved, null, null)
        }
    }

    // ---- recordings ----

    /** Turns the recording log into an activity (FIT file + note). [distance] is for activities without GPS. */
    fun saveRecording(log: String, title: String, distance: Double?) {
        if (busy) return
        busy = true
        scope.launch {
            if (syncing()) {
                // a finished recording is never refused: it waits (its log stays on disk) and is written after the sync
                say(Snack("Synchronisation en cours : l'activité sera enregistrée dès la fin"))
                androidx.compose.runtime.snapshotFlow { syncing() }.first { !it }
            }
            try {
                val note = withContext(io) {
                    val rec = RecordingLog.parse(log) ?: throw java.io.IOException("Enregistrement illisible")
                    val name = title.trim().ifEmpty { defaultTitle(rec.sport, rec.start) }
                    val end = rec.track.points.lastOrNull()?.time ?: System.currentTimeMillis()
                    if (rec.sport.hasGps && rec.track.points.size >= 2) {
                        var track = rec.track
                        var marker: String? = null
                        if (refineAltitude() && !Elevation.missing(track)) {
                            // the barometer's altitude, corrected for its drift with the terrain model
                            reliefProgress = "Relief…"
                            try {
                                val dem = Elevation.query(
                                    Elevation.pick(Analyzer.clean(track)), get = ::httpGet, pause = { Thread.sleep(1100) },
                                    progress = { n, total -> reliefProgress = "Relief ${n + 1}/$total…" },
                                )
                                track = Elevation.refine(track, dem)
                                marker = ActivityInfo.ALT_REFINED
                            } catch (e: Exception) {
                                say(Snack("Relief indisponible : l'altitude du baromètre est conservée (${e.message})"))
                            } finally {
                                reliefProgress = null
                            }
                        }
                        val analysis = Analyzer.analyze(track, rec.sport)
                        val bytes = Fit.write(track, rec.sport, analysis.stats.distance)
                        saveNew(track, rec.sport, name, bytes, "fit", null, marker)
                    } else {
                        val seconds = ((end - rec.start - rec.track.pauses.sumOf { it.end - it.start }) / 1000).coerceAtLeast(0)
                        saveManualNow(name, rec.sport, rec.start, seconds, distance ?: 0.0)
                    }
                }
                Recorder.discard()
                open(note)
            } catch (e: Exception) {
                say(Snack("Enregistrement impossible : ${e.message}. L'activité reste à enregistrer."))
            } finally {
                busy = false
            }
        }
    }

    // ---- altitude from the terrain model ----

    /** Says what the lookup is doing ("Relief 3/12…"), null when idle. */
    var reliefProgress by mutableStateOf<String?>(null)
        private set

    /** Looks the altitude of a track without one up online (positions go to opentopodata.org). */
    fun fetchAltitude(note: Note) {
        if (readOnly()) return
        scope.launch {
            val saved = lookUpAltitude(note, quiet = false)
            open(saved)
        }
    }

    private enum class Outcome { DONE, SKIPPED, FAILED }

    private suspend fun lookUpAltitude(note: Note, quiet: Boolean): Note {
        val (saved, outcome, message) = lookUp(note)
        if (outcome == Outcome.FAILED && !quiet) say(Snack("Altitude indisponible : $message"))
        if (outcome == Outcome.SKIPPED && !quiet) say(Snack(message ?: "Rien à compléter"))
        return saved
    }

    private data class LookUp(val note: Note, val outcome: Outcome, val message: String?)

    /** One activity: reads its file, and if it has no altitude looks it up and keeps it in an .alt file. */
    private suspend fun lookUp(note: Note): LookUp {
        val info = note.activity ?: return LookUp(note, Outcome.SKIPPED, null)
        val file = info.file ?: return LookUp(note, Outcome.SKIPPED, null)
        if (info.alt != null || !info.sport.hasGps) return LookUp(note, Outcome.SKIPPED, "Cette activité a déjà une altitude")
        return try {
            withContext(io) {
                // the .alt is written next to the track: only in attachments/, whatever the frontmatter says
                if (!SafePath.owned(file)) throw java.io.IOException("Fichier introuvable")
                val bytes = repo.fs.readBytes(file) ?: throw java.io.IOException("Fichier introuvable")
                val track = if (Fit.looksLikeFit(bytes)) Fit.read(bytes) else Gpx.read(String(bytes, Charsets.UTF_8))
                if (!Elevation.missing(track)) return@withContext LookUp(note, Outcome.SKIPPED, "Cette activité a déjà une altitude")
                val alts = Elevation.query(
                    Elevation.pick(Analyzer.clean(track)), get = ::httpGet, pause = { Thread.sleep(1100) },
                    progress = { n, total -> reliefProgress = "$reliefPrefix${n + 1}/$total…" },
                )
                val sidecar = file.substringBeforeLast('.') + ".alt"
                repo.fs.writeText(sidecar, Elevation.encode(alts))
                val filled = Elevation.apply(track, alts)
                val newInfo = ActivityInfo.from(info.sport, Analyzer.analyze(filled, info.sport), filled, file, sidecar)
                LookUp(
                    repo.save(note.copy(activity = newInfo, attachments = if (sidecar in note.attachments) note.attachments else note.attachments + sidecar, updated = System.currentTimeMillis())),
                    Outcome.DONE, null,
                )
            }
        } catch (e: Exception) {
            LookUp(note, Outcome.FAILED, e.message)
        }
    }

    private var reliefPrefix = "Relief "

    /** Completes several activities one after the other; stops at the first service failure (quota, offline). */
    fun fillAltitudes(notes: List<Note>) {
        if (reliefProgress != null || readOnly()) return
        scope.launch {
            var done = 0
            var skipped = 0
            var failure: String? = null
            reliefProgress = "Relief…"
            try {
                for ((i, n) in notes.withIndex()) {
                    reliefPrefix = "Activité ${i + 1}/${notes.size} · "
                    val fresh = repo.activities.value.firstOrNull { it.path == n.path } ?: n
                    val r = lookUp(fresh)
                    when (r.outcome) {
                        Outcome.DONE -> done++
                        Outcome.SKIPPED -> skipped++
                        Outcome.FAILED -> { failure = r.message; break }
                    }
                }
            } finally {
                reliefPrefix = "Relief "
                reliefProgress = null
            }
            say(
                Snack(
                    "Altitude : $done complétée${if (done > 1) "s" else ""}, $skipped ignorée${if (skipped > 1) "s" else ""}" +
                        (failure?.let { ". Arrêté : $it" } ?: ""),
                ),
            )
            detail?.let { d -> repo.activities.value.firstOrNull { it.path == d.note.path }?.let { open(it) } }
        }
    }

    private fun httpGet(url: String): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "Memento (https://github.com/KrustyLeBot/Memento)")
        try {
            if (c.responseCode != 200) throw Elevation.ElevationException("Service d'altitude : HTTP ${c.responseCode} (limite atteinte ?)")
            return c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    // ---- open / edit / delete ----

    fun open(note: Note) {
        detail = ActivityDetail(note, null, null)
        onOpen?.invoke()
        val info = note.activity ?: return
        val file = info.file ?: return
        scope.launch {
            val loaded = withContext(io) {
                try {
                    val bytes = repo.fs.readBytes(file) ?: throw java.io.IOException("Fichier introuvable (pas encore synchronisé ?)")
                    var track = if (Fit.looksLikeFit(bytes)) Fit.read(bytes) else Gpx.read(String(bytes, Charsets.UTF_8))
                    info.alt?.takeIf { it.startsWith("attachments/") }?.let { alt ->
                        if (Elevation.missing(track)) repo.fs.readText(alt)?.let { track = Elevation.apply(track, Elevation.parse(it)) }
                    }
                    ActivityDetail(note, track, Analyzer.analyze(track, info.sport))
                } catch (e: Exception) {
                    ActivityDetail(note, null, null, e.message ?: "Lecture impossible")
                }
            }
            if (detail?.note?.path == note.path) detail = loaded
        }
    }

    fun close() { detail = null }

    /** Title, description and sport; a new sport recomputes the figures from the track. */
    fun update(note: Note, title: String, description: String, sport: Sport) {
        if (readOnly()) return
        scope.launch {
            val saved = withContext(io) {
                var info = note.activity
                val current = detail?.takeIf { it.note.path == note.path }
                if (info != null && info.sport != sport) {
                    val track = current?.track
                    info = if (track != null) ActivityInfo.from(sport, Analyzer.analyze(track, sport), track, info.file, info.alt) else info.copy(sport = sport)
                }
                repo.save(note.copy(title = title.trim().ifEmpty { note.title }, body = description.trim(), activity = info, updated = System.currentTimeMillis()))
            }
            val track = detail?.track
            detail = ActivityDetail(saved, track, track?.let { Analyzer.analyze(it, saved.activity?.sport ?: sport) }, detail?.error)
        }
    }

    fun delete(note: Note) {
        if (readOnly()) return
        detail = null
        scope.launch { withContext(io) { repo.delete(note) } }
    }

    fun addPhoto(note: Note, uri: Uri) {
        if (readOnly()) return
        scope.launch {
            try {
                val saved = withContext(io) {
                    val fresh = repo.activities.value.firstOrNull { it.path == note.path } ?: note
                    repo.save(fresh.copy(attachments = fresh.attachments + copyIn(uri), updated = System.currentTimeMillis()))
                }
                detail = detail?.let { ActivityDetail(saved, it.track, it.analysis, it.error) }
            } catch (e: Exception) {
                say(Snack("Photo impossible : ${e.message}"))
            }
        }
    }

    fun removePhoto(note: Note, path: String) {
        if (readOnly()) return
        scope.launch {
            val saved = withContext(io) {
                val fresh = repo.activities.value.firstOrNull { it.path == note.path } ?: note
                repo.deleteOwned(listOf(path), fresh.path)
                repo.save(fresh.copy(attachments = fresh.attachments - path, updated = System.currentTimeMillis()))
            }
            detail = detail?.let { ActivityDetail(saved, it.track, it.analysis, it.error) }
        }
    }

    companion object {
        fun defaultTitle(sport: Sport, time: Long, name: String? = null): String {
            if (!name.isNullOrBlank()) return name.trim()
            val date = SimpleDateFormat("d MMM yyyy", Locale.FRANCE).format(Date(time))
            return "${sport.label.substringBefore(" (")} du $date"
        }
    }
}
