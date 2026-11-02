package app.memento.contacts

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.memento.Snack
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the phone's provider says about a contact another app opened. */
sealed class Resolution {
    /** It has a raw contact of the mirror's account: [sourceId] is the Mémento contact id. */
    data class Mine(val sourceId: String) : Resolution()
    /** A contact of another account only, read into a draft for an import. */
    data class Foreign(val draft: Contact) : Resolution()
    data object NotFound : Resolution()
    data object NoPermission : Resolution()
}

/** The dialog the flow shows over the current screen. */
sealed class RequestDialog {
    /** `INSERT_OR_EDIT`: a new contact, or added to one already in the app. */
    data class Choose(val draft: Contact) : RequestDialog()
    data class Pick(val draft: Contact) : RequestDialog()
    /** A contact of another account was opened: offer to import it. */
    data class Import(val draft: Contact, val edit: Boolean) : RequestDialog()
    /** READ_CONTACTS is needed to find out which contact was opened. */
    data class Permission(val request: ContactRequest) : RequestDialog()
}

/**
 * The contacts intents of other apps (dialer, messaging, the phone's contacts app): Mémento's editor, prefilled,
 * for "Créer un contact" / "Ajouter aux contacts", and its detail or editor for a contact opened elsewhere.
 * Waits for the folder's first read and, before any edit, for the end of a running sync (read-only meanwhile).
 */
class ContactRequestFlow(
    private val app: Application,
    private val contacts: ContactsController,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val say: (Snack) -> Unit,
    private val syncing: () -> Boolean,
) {
    var dialog by mutableStateOf<RequestDialog?>(null)
        private set

    private var job: Job? = null

    /** The activity was started for a request: once it is done the activity finishes, back to the caller. */
    var finishWhenDone = false

    fun handle(request: ContactRequest) {
        job?.cancel()
        dialog = null
        job = scope.launch {
            contacts.awaitScanned()
            when (request) {
                is ContactRequest.Insert -> {
                    writable()
                    if (request.offerExisting && contacts.contacts.value.any { !it.conflict }) dialog = RequestDialog.Choose(request.draft)
                    else editDraft(null, request.draft)
                }
                is ContactRequest.View, is ContactRequest.Edit, is ContactRequest.Invite -> resolveAndOpen(request)
                ContactRequest.AllContacts -> contacts.showList()
            }
        }
    }

    private suspend fun resolveAndOpen(request: ContactRequest) {
        val ref = when (request) {
            is ContactRequest.View -> request.ref
            is ContactRequest.Edit -> request.ref
            is ContactRequest.Invite -> request.ref
            else -> return
        }
        val edit = request is ContactRequest.Edit
        when (val r = withContext(io) { resolve(ref) }) {
            is Resolution.Mine -> {
                val c = contacts.book.byId(r.sourceId)
                if (c == null) {
                    // mirrored from a file deleted since (the next mirror run removes it)
                    contacts.showList()
                    say(Snack("Ce contact n'existe plus dans Mémento"))
                    return
                }
                if (request is ContactRequest.Invite) say(Snack("Ce contact est déjà dans Mémento"))
                if (edit) {
                    writable()
                    contacts.edit(c)
                } else {
                    contacts.open(c)
                }
                contacts.returnToCaller = true
            }
            is Resolution.Foreign -> {
                if (request is ContactRequest.Invite) {
                    writable()
                    editDraft(null, r.draft)
                } else {
                    contacts.showList()
                    dialog = RequestDialog.Import(r.draft, edit)
                }
            }
            Resolution.NotFound -> {
                contacts.showList()
                say(Snack("Contact introuvable dans le téléphone"))
            }
            Resolution.NoPermission -> {
                contacts.showList()
                dialog = RequestDialog.Permission(request)
            }
        }
    }

    private suspend fun writable() {
        if (!syncing()) return
        say(Snack("Synchronisation en cours : le contact s'ouvre dès qu'elle est finie"))
        snapshotFlow { syncing() }.first { !it }
    }

    private fun editDraft(base: Contact?, draft: Contact) {
        contacts.editDraft(base, draft)
        contacts.returnToCaller = true
    }

    // ---- dialog answers ----

    fun createNew(draft: Contact) {
        dialog = null
        job = scope.launch { writable(); editDraft(null, draft) }
    }

    fun pickExisting(draft: Contact) { dialog = RequestDialog.Pick(draft) }

    fun addTo(existing: Contact, draft: Contact) {
        dialog = null
        job = scope.launch { writable(); editDraft(existing, draft) }
    }

    /** READ_CONTACTS answered: try again when granted. */
    fun permissionResult(granted: Boolean) {
        val d = dialog as? RequestDialog.Permission ?: return
        if (granted) handle(d.request)
    }

    /** "Annuler": back to the app that asked, like the stock contacts app. */
    fun cancel() {
        dialog = null
        job?.cancel()
        contacts.returnToCaller = true
        contacts.endExternal()
    }

    // ---- provider ----

    private fun canRead() = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Blocking. Never throws: a provider that refuses or fails reads as "no permission" or "not found". */
    fun resolve(ref: ContactRef): Resolution {
        if (!canRead()) return Resolution.NoPermission
        return try {
            val r = app.contentResolver
            val (contactId, rawId) = when (ref) {
                is ContactRef.ContactId -> ref.id to null
                is ContactRef.RawId -> (rawContactOwner(ref.id) ?: return Resolution.NotFound) to ref.id
                is ContactRef.Lookup -> {
                    val uri = ContactsContract.Contacts.CONTENT_LOOKUP_URI.buildUpon().appendPath(ref.key)
                        .apply { ref.contactId?.let { appendPath(it.toString()) } }.build()
                    val found = ContactsContract.Contacts.lookupContact(r, uri) ?: return Resolution.NotFound
                    ContentUris.parseId(found) to null
                }
            }
            val raws = ArrayList<Triple<Long, String?, String?>>() // id, account type, source id
            r.query(
                RawContacts.CONTENT_URI, arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE, RawContacts.SOURCE_ID),
                "${RawContacts.CONTACT_ID}=? AND ${RawContacts.DELETED}=0", arrayOf(contactId.toString()), null,
            )?.use { c -> while (c.moveToNext()) raws.add(Triple(c.getLong(0), c.getString(1), c.getString(2))) }
            if (raws.isEmpty()) return Resolution.NotFound
            val mine = raws.filter { it.second == MirrorPlan.ACCOUNT_TYPE && !it.third.isNullOrEmpty() }
            (mine.firstOrNull { it.first == rawId } ?: mine.firstOrNull())?.let { return Resolution.Mine(it.third!!) }
            val rows = ArrayList<DataRow>()
            val cols = arrayOf(Data.MIMETYPE) + (1..15).map { "data$it" }
            r.query(Data.CONTENT_URI, cols, "${Data.CONTACT_ID}=?", arrayOf(contactId.toString()), "${Data.IS_SUPER_PRIMARY} DESC, ${Data.IS_PRIMARY} DESC")
                ?.use { c ->
                    while (c.moveToNext()) {
                        val mime = c.getString(0) ?: continue
                        // data15 is a blob for photos: getString would throw
                        rows.add(DataRow(mime, (1..14).associate { "data$it" to runCatching { c.getString(it) }.getOrNull() }))
                    }
                }
            val draft = ContactRequests.fromRows(rows)
            if (draft.isEmpty) Resolution.NotFound else Resolution.Foreign(draft)
        } catch (e: SecurityException) {
            Resolution.NoPermission
        } catch (e: Exception) {
            android.util.Log.w("ContactRequestFlow", "contact not resolved: $ref", e)
            Resolution.NotFound
        }
    }

    private fun rawContactOwner(rawId: Long): Long? =
        app.contentResolver.query(ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), arrayOf(RawContacts.CONTACT_ID), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    companion object {
        /** The request an intent carries, or null when it is not a contacts action. */
        fun request(intent: Intent): ContactRequest? {
            val uri: Uri? = intent.data
            val extras = intent.extras
            val data = try {
                IntentCompat.getParcelableArrayListExtra(intent, ContactRequests.DATA, ContentValues::class.java).orEmpty()
                    .mapNotNull { cv ->
                        val mime = cv.getAsString(Data.MIMETYPE) ?: return@mapNotNull null
                        DataRow(mime, cv.keySet().associateWith { k -> runCatching { cv.getAsString(k) }.getOrNull() })
                    }
            } catch (e: RuntimeException) {
                emptyList() // a malformed extra from another app must not crash the app
            }
            return ContactRequests.classify(
                intent.action, intent.type, uri?.authority, uri?.pathSegments,
                extras = { key -> try { @Suppress("DEPRECATION") extras?.get(key) } catch (e: RuntimeException) { null } },
                data = data,
            )
        }
    }
}
