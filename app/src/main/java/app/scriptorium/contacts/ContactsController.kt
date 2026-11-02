package app.scriptorium.contacts

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import app.scriptorium.NoteFormat
import app.scriptorium.NoteRepository
import app.scriptorium.Screen
import app.scriptorium.Snack
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** One editable phone row of the editor. */
data class PhoneField(val number: String, val label: String?)

/** The contact editor's fields (Compose state). */
class ContactEditState(val original: Contact?) {
    var firstName by mutableStateOf(original?.firstName.orEmpty())
    var lastName by mutableStateOf(original?.lastName.orEmpty())
    val phones = mutableStateListOf<PhoneField>().apply {
        addAll(original?.phones.orEmpty().map { PhoneField(it.number, it.label) })
        if (isEmpty()) add(PhoneField("", PhoneLabels.MOBILE))
    }
    val emails = mutableStateListOf<String>().apply { addAll(original?.emails.orEmpty()); if (isEmpty()) add("") }
    var address by mutableStateOf(original?.address.orEmpty())
    var birthDay by mutableStateOf(original?.birthday?.day?.toString().orEmpty())
    var birthMonth by mutableStateOf(original?.birthday?.month)
    var birthYear by mutableStateOf(original?.birthday?.year?.toString().orEmpty())
    var notes by mutableStateOf(original?.notes.orEmpty())
    var favorite by mutableStateOf(original?.favorite ?: false)

    /** Null when no birthday is typed; an error text when what is typed is not a date. */
    fun birthday(): Result<Birthday?> {
        if (birthDay.isBlank() && birthMonth == null && birthYear.isBlank()) return Result.success(null)
        val day = birthDay.trim().toIntOrNull()
        val year = birthYear.trim().ifEmpty { null }?.let { it.toIntOrNull() ?: return Result.failure(IllegalArgumentException("Année invalide")) }
        val month = birthMonth
        if (day == null || month == null) return Result.failure(IllegalArgumentException("Jour et mois de l'anniversaire"))
        return Birthday.of(day, month, year)?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException("Date d'anniversaire invalide"))
    }

    fun toContact(): Contact {
        val base = original ?: Contact()
        return base.copy(
            firstName = firstName.trim(),
            lastName = lastName.trim(),
            phones = phones.filter { it.number.isNotBlank() }.map { Phone(it.number.trim(), it.label?.ifBlank { null }) },
            emails = emails.map { it.trim() }.filter { it.isNotEmpty() },
            address = address.trim().ifEmpty { null },
            birthday = birthday().getOrNull(),
            notes = notes.trimEnd(),
            favorite = favorite,
        )
    }

    val changed: Boolean get() = toContact() != (original ?: Contact())
}

/** The import dialog's steps. */
sealed class ContactImportState {
    data object Reading : ContactImportState()
    data class Preview(val result: VCardResult, val preview: ImportPreview, val fileName: String?) : ContactImportState()
    data class Running(val done: Int, val total: Int) : ContactImportState()
    data class Report(val outcome: ImportOutcome) : ContactImportState()
}

/**
 * Everything the Contacts module does besides drawing: list state, open, edit, delete with undo, import with
 * preview and undo, export, duplicates, and the mirror into the phone's contacts.
 */
@OptIn(FlowPreview::class)
class ContactsController(
    private val app: Application,
    private val repo: NoteRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val say: (Snack) -> Unit,
    private val navigate: (Screen) -> Unit,
) {
    val book = ContactBook { repo.fs }
    /** Every contact file, as the agenda reads them for the birthdays. */
    val contacts = book.contacts

    private val prefs = app.getSharedPreferences("contacts", android.content.Context.MODE_PRIVATE)

    /** Sort and letters by first name ("Prénom d'abord"); local to this phone. */
    var firstNameFirst by mutableStateOf(prefs.getBoolean("first_name_first", false))
        private set

    fun changeFirstNameFirst(v: Boolean) {
        firstNameFirst = v
        prefs.edit().putBoolean("first_name_first", v).apply()
    }

    var query by mutableStateOf("")

    var openId by mutableStateOf<String?>(null)
        private set
    var editor by mutableStateOf<ContactEditState?>(null)
        private set
    var importState by mutableStateOf<ContactImportState?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    /** Groups dismissed with "Ignorer" in the duplicates tool (until the app closes). */
    val ignoredGroups = mutableStateListOf<Set<String>>()

    /** True once the folder was read: before that the list is empty only because nothing was read yet. */
    @Volatile private var scanned = false

    /** Re-reads the folder (blocking). The first read also starts the mirror, later ones run it when something changed. */
    fun scan() {
        book.scan()
        if (!scanned) {
            scanned = true
            runMirror()
        }
    }

    // ---- navigation ----

    fun open(c: Contact) {
        openId = c.id
        navigate(Screen.CONTACT)
    }

    fun current(): Contact? = openId?.let { book.byId(it) }

    fun newContact() {
        editor = ContactEditState(null)
        navigate(Screen.CONTACT_EDIT)
    }

    fun edit(c: Contact) {
        editor = ContactEditState(c)
        navigate(Screen.CONTACT_EDIT)
    }

    fun openDuplicates() = navigate(Screen.CONTACT_DUPLICATES)

    /** System back on one of the module's inner screens. */
    fun back() {
        when {
            editor != null -> closeEditor(save = true)
            openId != null -> { openId = null; navigate(Screen.CONTACTS) }
            else -> navigate(Screen.CONTACTS)
        }
    }

    // ---- editing ----

    /** Saves (when something changed and the birthday is valid) and leaves the editor. */
    fun closeEditor(save: Boolean) {
        val ed = editor ?: return
        if (save && ed.changed) {
            ed.birthday().exceptionOrNull()?.let { say(Snack(it.message ?: "Anniversaire invalide")); return }
            val c = ed.toContact()
            if (c.isEmpty) {
                if (ed.original == null) { leaveEditor(null); return }
                say(Snack("Un contact vide ne peut pas être enregistré : supprime-le plutôt"))
                return
            }
            busy = true
            scope.launch {
                try {
                    val saved = withContext(io) { book.save(c) }
                    leaveEditor(saved)
                } catch (e: Exception) {
                    say(Snack("Échec de l'enregistrement : ${e.message}"))
                } finally {
                    busy = false
                }
            }
            return
        }
        leaveEditor(ed.original)
    }

    private fun leaveEditor(shown: Contact?) {
        editor = null
        if (shown != null) { openId = shown.id; navigate(Screen.CONTACT) } else { openId = null; navigate(Screen.CONTACTS) }
    }

    fun toggleFavorite(c: Contact) {
        scope.launch {
            try {
                withContext(io) { book.save(c.copy(favorite = !c.favorite)) }
            } catch (e: Exception) {
                say(Snack("Échec de l'enregistrement : ${e.message}"))
            }
        }
    }

    /** Deletes the file; the snackbar offers to put it back exactly as it was. */
    fun delete(c: Contact) {
        openId = null
        navigate(Screen.CONTACTS)
        scope.launch {
            try {
                val raw = withContext(io) { book.delete(c) }
                say(Snack("Contact supprimé", raw?.let { text -> { restore(c.path, text) } }))
            } catch (e: Exception) {
                say(Snack("Suppression impossible : ${e.message}"))
            }
        }
    }

    private fun restore(path: String, raw: String) {
        scope.launch {
            try {
                withContext(io) { book.restore(path, raw) }
            } catch (e: Exception) {
                say(Snack("Restauration impossible : ${e.message}"))
            }
        }
    }

    // ---- duplicates ----

    fun merge(keep: Contact, others: List<Contact>) {
        scope.launch {
            try {
                val before = withContext(io) { book.merge(keep, others) }
                say(Snack("${others.size + 1} contacts fusionnés") {
                    scope.launch {
                        try { withContext(io) { book.restoreAll(before) } } catch (e: Exception) { say(Snack("Restauration impossible : ${e.message}")) }
                    }
                })
            } catch (e: Exception) {
                say(Snack("Fusion impossible : ${e.message}"))
            }
        }
    }

    // ---- import ----

    fun startImport(uri: Uri) {
        importState = ContactImportState.Reading
        navigate(Screen.CONTACTS)
        scope.launch {
            try {
                val (result, name) = withContext(io) {
                    val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw java.io.IOException("Fichier illisible")
                    if (bytes.size > 20_000_000) throw java.io.IOException("Fichier trop gros")
                    VCard.parse(bytes) to displayName(uri)
                }
                if (result.contacts.isEmpty() && result.bad == 0 && result.empty == 0) throw java.io.IOException("Aucune fiche vCard dans ce fichier")
                importState = ContactImportState.Preview(result, ContactImport.preview(result, contacts.value), name)
            } catch (e: Exception) {
                importState = null
                say(Snack("Import impossible : ${e.message}"))
            }
        }
    }

    private fun displayName(uri: Uri): String? = try {
        app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: SecurityException) {
        null
    }

    fun cancelImport() { importState = null }

    fun confirmImport(policy: DuplicatePolicy) {
        val p = importState as? ContactImportState.Preview ?: return
        importState = ContactImportState.Running(0, p.result.contacts.size)
        scope.launch {
            try {
                val outcome = withContext(io) {
                    val now = System.currentTimeMillis()
                    val id = ContactBook.newImportId(now)
                    val plan = ContactImport.plan(p.result, contacts.value, policy, id, now) { NoteFormat.generateId() }
                    book.apply(plan, id, p.result) { done, total ->
                        if (done == total || done % 10 == 0) scope.launch { importState = ContactImportState.Running(done, total) }
                    }
                }
                importState = ContactImportState.Report(outcome)
            } catch (e: Exception) {
                importState = null
                say(Snack("Import interrompu : ${e.message}"))
            }
        }
    }

    fun closeReport() { importState = null }

    fun undoImport(outcome: ImportOutcome) {
        importState = null
        scope.launch {
            try {
                val n = withContext(io) { book.undo(outcome) }
                say(Snack("Import annulé ($n fichier${if (n > 1) "s" else ""})"))
            } catch (e: Exception) {
                say(Snack("Annulation impossible : ${e.message}"))
            }
        }
    }

    // ---- export / share ----

    fun exportAll(uri: Uri) {
        scope.launch {
            try {
                val n = withContext(io) {
                    val list = ContactList.sorted(contacts.value.filter { !it.conflict }, firstNameFirst)
                    app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(VCard.write(list).toByteArray(Charsets.UTF_8)) }
                        ?: throw java.io.IOException("Fichier inaccessible")
                    list.size
                }
                say(Snack(if (n > 1) "$n contacts exportés" else "$n contact exporté"))
            } catch (e: Exception) {
                say(Snack("Export impossible : ${e.message}"))
            }
        }
    }

    /** The share sheet with the contact as a `.vcf`. */
    fun share(c: Contact) {
        scope.launch {
            try {
                val uri = withContext(io) {
                    val dir = File(app.cacheDir, "share").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val file = File(dir, NoteFormat.slug(c.displayName) + ".vcf")
                    file.writeText(VCard.write(c), Charsets.UTF_8)
                    FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                }
                val send = Intent(Intent.ACTION_SEND).setType("text/x-vcard").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                app.startActivity(Intent.createChooser(send, c.displayName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                say(Snack("Partage impossible : ${e.message}"))
            }
        }
    }

    // ---- mirror into the phone's contacts ----

    private val mirror = ContactsMirror(app)
    private val mirrorLock = Mutex()

    var mirrorEnabled by mutableStateOf(prefs.getBoolean("mirror", false))
        private set
    /** Last result or failure of the mirror, shown in the settings. */
    var mirrorStatus by mutableStateOf<String?>(null)
        private set

    init {
        scope.launch {
            contacts.debounce(1500).filter { scanned }.collect { if (mirrorEnabled) runMirror() }
        }
    }

    fun mirrorPermissionsGranted(): Boolean = mirror.hasPermissions()

    /** Called once the permissions are granted (or with false when refused). */
    fun setMirror(on: Boolean) {
        if (on) {
            if (!mirror.hasPermissions()) { mirrorStatus = "Autorisation Contacts refusée"; return }
            mirrorEnabled = true
            prefs.edit().putBoolean("mirror", true).apply()
            runMirror()
        } else {
            mirrorEnabled = false
            prefs.edit().putBoolean("mirror", false).putBoolean("mirror_account", false).apply()
            scope.launch {
                mirrorLock.withLock {
                    mirrorStatus = try {
                        if (withContext(io) { mirror.removeAccount() }) "Contacts retirés du téléphone" else "Le compte n'a pas pu être supprimé"
                    } catch (e: Exception) {
                        "Suppression du compte impossible : ${e.message}"
                    }
                }
            }
        }
    }

    /** Brings the phone's copies in line with the files. Safe to call often: an unchanged contact costs nothing. */
    fun runMirror() {
        if (!mirrorEnabled || !scanned) return
        scope.launch {
            mirrorLock.withLock {
                if (!mirrorEnabled) return@withLock
                // The account was removed from the phone's settings: the user stopped the mirror there.
                if (prefs.getBoolean("mirror_account", false) && withContext(io) { mirror.account() } == null) {
                    mirrorEnabled = false
                    prefs.edit().putBoolean("mirror", false).putBoolean("mirror_account", false).apply()
                    mirrorStatus = "Le compte a été supprimé dans les réglages du téléphone : copie désactivée"
                    return@withLock
                }
                mirrorStatus = try {
                    val r = withContext(io) { mirror.sync(contacts.value) }
                    prefs.edit().putBoolean("mirror_account", true).apply()
                    when {
                        r.keptBecauseEmpty -> "Aucun contact lu dans le dossier : rien n'a été retiré du téléphone"
                        r.inserted + r.updated + r.deleted == 0 -> "${ContactList.countLabel(r.total)} dans le téléphone, à jour"
                        else -> "${ContactList.countLabel(r.total)} dans le téléphone (${r.inserted} ajoutés, ${r.updated} mis à jour, ${r.deleted} retirés)"
                    }
                } catch (e: SecurityException) {
                    "Autorisation Contacts refusée : ouvre les autorisations de l'app"
                } catch (e: Exception) {
                    "Copie dans le téléphone impossible : ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }
}
