package app.memento.contacts

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.core.content.ContextCompat
import app.memento.NoteFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.ZoneId
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** What one run of the mirror did, both ways. */
data class MirrorResult(
    val inserted: Int,
    val updated: Int,
    val deleted: Int,
    val total: Int,
    val keptBecauseEmpty: Boolean = false,
    /** Contacts created on the phone, now files. */
    val created: Int = 0,
    /** Files rewritten from the phone's edits (conflicts included). */
    val edited: Int = 0,
    /** Contacts deleted on the phone, their files put in the trash. */
    val trashed: Int = 0,
    val conflicts: Int = 0,
    /** Contacts deleted on the phone all at once, whose files were kept (and put back on the phone). */
    val keptBecauseMassDelete: Int = 0,
)

/**
 * The two-way mirror between the `contacts/` files and the phone's contacts provider, under the app's own
 * account (Part C §5 L2). All decisions are in [MirrorSync]; this is the provider and file glue. One run at a
 * time per process (the app, the provider observer and the sync adapter share [lock]).
 * Blocking: call from an I/O dispatcher or the sync adapter's thread.
 */
class ContactsMirror(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val accounts: AccountManager = AccountManager.get(context)
    val store = MirrorStore(File(context.filesDir, "contacts-mirror"))

    fun hasPermissions(): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** The app's account, if it exists (an account of its own type needs no permission to be listed). */
    fun account(): Account? = accounts.getAccountsByType(MirrorPlan.ACCOUNT_TYPE).firstOrNull()

    /**
     * Creates the account if needed and makes its contacts visible. Automatic sync is on so that an edit made in
     * the phone's contacts app (which marks the raw contact DIRTY) makes Android call [MirrorSyncService].
     */
    fun ensureAccount(): Account {
        val account = account() ?: Account(MirrorPlan.ACCOUNT_NAME, MirrorPlan.ACCOUNT_TYPE).also {
            if (!accounts.addAccountExplicitly(it, null, null)) throw IllegalStateException("Impossible de créer le compte ${it.name}")
        }
        if (ContentResolver.getIsSyncable(account, ContactsContract.AUTHORITY) <= 0) ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1)
        if (!ContentResolver.getSyncAutomatically(account, ContactsContract.AUTHORITY)) {
            ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true)
        }
        ensureVisible(account)
        return account
    }

    /** Removing the account makes the provider delete every raw contact it owns. */
    fun removeAccount(): Boolean {
        val account = account() ?: return true
        return accounts.removeAccountExplicitly(account)
    }

    private fun syncUri(uri: Uri, account: Account): Uri = uri.buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
        .build()

    private fun ensureVisible(account: Account) {
        val uri = syncUri(ContactsContract.Settings.CONTENT_URI, account)
        val selection = "${ContactsContract.Settings.ACCOUNT_NAME}=? AND ${ContactsContract.Settings.ACCOUNT_TYPE}=?"
        val args = arrayOf(account.name, account.type)
        val visible = resolver.query(uri, arrayOf(ContactsContract.Settings.UNGROUPED_VISIBLE), selection, args, null)?.use { c ->
            if (c.moveToFirst()) c.getInt(0) else null
        }
        when (visible) {
            1 -> Unit
            null -> resolver.insert(uri, ContentValues().apply {
                put(ContactsContract.Settings.ACCOUNT_NAME, account.name)
                put(ContactsContract.Settings.ACCOUNT_TYPE, account.type)
                put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
            })
            else -> resolver.update(uri, ContentValues().apply { put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1) }, selection, args)
        }
    }

    // ---- reading the provider ----

    private fun existing(account: Account, now: Long): List<MirrorRaw> {
        val uri = syncUri(RawContacts.CONTENT_URI, account)
        val cols = arrayOf(
            RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1, RawContacts.DIRTY, RawContacts.DELETED,
            RawContacts.STARRED, RawContacts.CONTACT_ID,
        )
        val out = ArrayList<MirrorRaw>()
        val contactOf = HashMap<Long, Long>()
        resolver.query(uri, cols, "${RawContacts.ACCOUNT_TYPE}=? AND ${RawContacts.ACCOUNT_NAME}=?", arrayOf(account.type, account.name), null)
            ?.use { c ->
                while (c.moveToNext()) {
                    val r = MirrorRaw(
                        c.getLong(0), c.getString(1)?.ifEmpty { null }, c.getString(2), c.getInt(3) != 0, c.getInt(4) != 0, c.getInt(5) != 0,
                    )
                    if (!c.isNull(6)) contactOf[r.rawId] = c.getLong(6)
                    out.add(r)
                }
            } ?: throw IllegalStateException("Le carnet d'adresses du téléphone ne répond pas")
        val needed = out.filter { !it.deleted && (it.dirty || it.sourceId == null) }
        if (needed.isEmpty()) return out
        val ids = needed.map { it.rawId }.toHashSet()
        val rows = dataRows(ids.toList())
        val updatedAt = lastUpdated(needed.mapNotNull { contactOf[it.rawId] })
        return out.map { r ->
            if (r.rawId !in ids) r
            else r.copy(rows = rows[r.rawId].orEmpty(), changedAt = contactOf[r.rawId]?.let { updatedAt[it] }?.takeIf { it > 0 } ?: now)
        }
    }

    private fun inList(ids: List<Long>) = ids.joinToString(",", "(", ")")

    /** The data rows of [rawIds] as [MirrorRow]s: the mirrored kinds, then [MirrorRow.Extra]s (a photo is not read). */
    private fun dataRows(rawIds: List<Long>): Map<Long, List<MirrorRow>> {
        val out = HashMap<Long, MutableList<MirrorRow>>()
        val cols = arrayOf(Data.RAW_CONTACT_ID, Data.MIMETYPE) + (1..10).map { "data$it" }
        for (chunk in rawIds.chunked(400)) {
            resolver.query(Data.CONTENT_URI, cols, "${Data.RAW_CONTACT_ID} IN ${inList(chunk)}", null, "${Data._ID} ASC")?.use { c ->
                while (c.moveToNext()) {
                    fun s(i: Int): String = c.getString(i + 1).orEmpty().trim()
                    val row: MirrorRow? = when (c.getString(1)) {
                        CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE ->
                            // data1 display, data2 given, data3 family, data4 prefix, data5 middle, data6 suffix
                            MirrorPlan.nameRow(s(4), s(2), s(5), s(3), s(6), s(1))
                        CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> s(1).takeIf { it.isNotEmpty() }?.let {
                            val (kind, label) = MirrorPlan.phoneType(s(2).toIntOrNull() ?: 0, s(3).ifEmpty { null })
                            MirrorRow.Tel(it, kind, label)
                        }
                        CommonDataKinds.Email.CONTENT_ITEM_TYPE -> s(1).takeIf { it.isNotEmpty() }?.let { MirrorRow.Mail(it) }
                        CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE -> {
                            // data1 formatted; else data4 street, data9 postcode, data7 city, data8 region, data10 country
                            val formatted = s(1).ifEmpty {
                                listOf(s(4), listOf(s(9), s(7)).filter { it.isNotEmpty() }.joinToString(" "), s(8), s(10))
                                    .filter { it.isNotEmpty() }.joinToString("\n")
                            }
                            formatted.takeIf { it.isNotEmpty() }?.let { MirrorRow.Postal(it) }
                        }
                        CommonDataKinds.Event.CONTENT_ITEM_TYPE ->
                            if (s(2).toIntOrNull() == CommonDataKinds.Event.TYPE_BIRTHDAY && s(1).isNotEmpty()) MirrorRow.Birthday(s(1)) else null
                        CommonDataKinds.Note.CONTENT_ITEM_TYPE -> s(1).takeIf { it.isNotEmpty() }?.let { MirrorRow.Note(it) }
                        else -> null
                    }
                    val list = out.getOrPut(c.getLong(0)) { ArrayList() }
                    if (row != null) list.add(row)
                    // what the files have no field for (an organisation, an anniversary, an e-mail's type...): into the notes
                    list.addAll(MirrorPlan.extra(c.getString(1).orEmpty(), (1..10).map { s(it) }))
                }
            } ?: throw IllegalStateException("Le carnet d'adresses du téléphone ne répond pas")
        }
        return out
    }

    /** When each aggregate contact last changed: the closest the provider gives to "when the user edited it". */
    private fun lastUpdated(contactIds: List<Long>): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        val cols = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP)
        for (chunk in contactIds.distinct().chunked(400)) {
            resolver.query(ContactsContract.Contacts.CONTENT_URI, cols, "${ContactsContract.Contacts._ID} IN ${inList(chunk)}", null, null)?.use { c ->
                while (c.moveToNext()) if (!c.isNull(1)) out[c.getLong(0)] = c.getLong(1)
            }
        }
        return out
    }

    // ---- one run ----

    /**
     * Brings the files and the provider in line, both ways (see [MirrorSync]). Throws on any failure (permission,
     * provider, files): nothing is hidden, and a failed run is simply done again by the next one.
     */
    fun sync(book: ContactBook, now: Long = System.currentTimeMillis()): MirrorResult = lock.withLock {
        if (!hasPermissions()) throw SecurityException("Autorisation Contacts refusée")
        val account = ensureAccount()
        book.scan()
        // a file missing from the index would read as deleted (its phone copy removed, or re-created over it)
        book.unreadable.firstOrNull()?.let { throw java.io.IOException("Fichier de contact illisible : $it (nouvel essai à la prochaine synchro)") }
        val files = book.contacts.value
        val state = store.read()
        store.purge(now)
        val raws = MirrorSync.resume(existing(account, now), state.pending, files.map { it.id }.toSet())
        val plan = MirrorSync.reconcile(files, book.modifiedById(), raws, state.synced, now, ZoneId.systemDefault()) { NoteFormat.generateId() }

        // Files first: the provider is told a phone change is settled (DIRTY cleared) only once its file holds it.
        val createdIds = plan.files.filterIsInstance<FileOp.Create>().map { it.contact.id }.toSet()
        store.addPending(plan.provider.filterIsInstance<MirrorOp.Ack>().filter { it.sourceId in createdIds }.associate { it.rawId to it.sourceId })
        for (op in plan.files) {
            // the plan was made from the files as scanned: one changed since (the sync app, the PC) is never overwritten
            if (op !is FileOp.Create && book.changedOnDisk(op.contact)) {
                throw IllegalStateException("${op.contact.displayName} a changé pendant la synchro des contacts : nouvel essai au prochain passage")
            }
            when (op) {
                is FileOp.Create -> {
                    val saved = book.save(op.contact, now)
                    if (saved.id != op.contact.id) throw IllegalStateException("Identifiant de contact déjà pris : ${op.contact.id}")
                }
                is FileOp.Write -> book.save(op.contact, now)
                is FileOp.Trash -> {
                    val text = book.text(op.contact)
                    if (text != null) store.trash(op.contact.path, op.contact.displayName, text, now)
                    book.delete(op.contact)
                }
            }
        }
        val phonetic = phoneticNames(plan.provider.filterIsInstance<MirrorOp.Update>().map { it.rawId })
        for (batch in MirrorPlan.batches(plan.provider)) {
            val list = ArrayList<ContentProviderOperation>()
            batch.forEach { add(list, it, account, phonetic) }
            resolver.applyBatch(ContactsContract.AUTHORITY, list)
        }
        store.write(MirrorStore.State(plan.synced, emptyMap()))

        val result = MirrorResult(
            inserted = plan.provider.count { it is MirrorOp.Insert },
            updated = plan.provider.count { it is MirrorOp.Update },
            deleted = plan.provider.count { it is MirrorOp.Delete },
            total = MirrorPlan.mirrored(book.contacts.value).size,
            keptBecauseEmpty = plan.keptBecauseEmpty,
            created = createdIds.size,
            edited = plan.files.count { it is FileOp.Write },
            trashed = plan.files.count { it is FileOp.Trash },
            conflicts = plan.conflicts,
            keptBecauseMassDelete = plan.keptBecauseMassDelete,
        )
        record(context, describe(result), error = false, now = now)
        result
    }

    /** Rewritten by an Update; of the events only the birthday is mirrored (an anniversary stays on the phone). */
    private val mirroredMimes = MirrorPlan.OWNED_MIMES

    /**
     * The phonetic names (given, middle, family) of [rawIds], typed on the phone: the files have no field for
     * them, so an Update writes them back into the new name row instead of dropping them.
     */
    private fun phoneticNames(rawIds: List<Long>): Map<Long, List<String?>> {
        val out = HashMap<Long, List<String?>>()
        val cols = arrayOf(Data.RAW_CONTACT_ID, CommonDataKinds.StructuredName.PHONETIC_GIVEN_NAME, CommonDataKinds.StructuredName.PHONETIC_MIDDLE_NAME, CommonDataKinds.StructuredName.PHONETIC_FAMILY_NAME)
        for (chunk in rawIds.distinct().chunked(400)) {
            resolver.query(Data.CONTENT_URI, cols, "${Data.RAW_CONTACT_ID} IN ${inList(chunk)} AND ${Data.MIMETYPE}=?", arrayOf(CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE), null)?.use { c ->
                while (c.moveToNext()) {
                    val v = (1..3).map { c.getString(it)?.trim()?.ifEmpty { null } }
                    if (v.any { it != null }) out[c.getLong(0)] = v
                }
            } ?: throw IllegalStateException("Le carnet d'adresses du téléphone ne répond pas")
        }
        return out
    }

    private fun add(list: ArrayList<ContentProviderOperation>, op: MirrorOp, account: Account, phonetic: Map<Long, List<String?>>) {
        val raws = syncUri(RawContacts.CONTENT_URI, account)
        val data = syncUri(Data.CONTENT_URI, account)
        when (op) {
            is MirrorOp.Delete -> list.add(ContentProviderOperation.newDelete(ContentUris.withAppendedId(raws, op.rawId)).build())
            is MirrorOp.Ack -> list.add(
                ContentProviderOperation.newUpdate(ContentUris.withAppendedId(raws, op.rawId))
                    .withValue(RawContacts.SOURCE_ID, op.sourceId)
                    .withValue(RawContacts.SYNC1, op.hash)
                    .withValue(RawContacts.DIRTY, 0)
                    .build(),
            )
            is MirrorOp.Insert -> {
                val back = list.size
                list.add(
                    ContentProviderOperation.newInsert(raws)
                        .withValue(RawContacts.ACCOUNT_NAME, account.name)
                        .withValue(RawContacts.ACCOUNT_TYPE, account.type)
                        .withValue(RawContacts.SOURCE_ID, op.contact.id)
                        .withValue(RawContacts.SYNC1, op.hash)
                        .withValue(RawContacts.STARRED, if (op.contact.favorite) 1 else 0)
                        .build(),
                )
                MirrorPlan.rows(op.contact).forEach { row ->
                    list.add(values(ContentProviderOperation.newInsert(data).withValueBackReference(Data.RAW_CONTACT_ID, back), row).build())
                }
            }
            is MirrorOp.Update -> {
                val marks = mirroredMimes.joinToString(",") { "?" }
                list.add(
                    ContentProviderOperation.newDelete(data)
                        .withSelection(
                            "${Data.RAW_CONTACT_ID}=? AND (${Data.MIMETYPE} IN ($marks) OR (${Data.MIMETYPE}=? AND ${CommonDataKinds.Event.TYPE}=${CommonDataKinds.Event.TYPE_BIRTHDAY}))",
                            arrayOf(op.rawId.toString()) + mirroredMimes + CommonDataKinds.Event.CONTENT_ITEM_TYPE,
                        )
                        .build(),
                )
                list.add(
                    ContentProviderOperation.newUpdate(ContentUris.withAppendedId(raws, op.rawId))
                        .withValue(RawContacts.SYNC1, op.hash)
                        .withValue(RawContacts.DIRTY, 0)
                        .withValue(RawContacts.STARRED, if (op.contact.favorite) 1 else 0)
                        .build(),
                )
                MirrorPlan.rows(op.contact).forEach { row ->
                    val b = values(ContentProviderOperation.newInsert(data).withValue(Data.RAW_CONTACT_ID, op.rawId), row)
                    val p = phonetic[op.rawId]
                    if (row is MirrorRow.Name && p != null) {
                        b.withValue(CommonDataKinds.StructuredName.PHONETIC_GIVEN_NAME, p[0]).withValue(CommonDataKinds.StructuredName.PHONETIC_MIDDLE_NAME, p[1]).withValue(CommonDataKinds.StructuredName.PHONETIC_FAMILY_NAME, p[2])
                    }
                    list.add(b.build())
                }
            }
        }
    }

    private fun values(b: ContentProviderOperation.Builder, row: MirrorRow): ContentProviderOperation.Builder = when (row) {
        is MirrorRow.Name -> b.withValue(Data.MIMETYPE, CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(CommonDataKinds.StructuredName.GIVEN_NAME, row.given.ifEmpty { null })
            .withValue(CommonDataKinds.StructuredName.FAMILY_NAME, row.family.ifEmpty { null })
            .withValue(CommonDataKinds.StructuredName.DISPLAY_NAME, row.display)
        is MirrorRow.Tel -> b.withValue(Data.MIMETYPE, CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            .withValue(CommonDataKinds.Phone.NUMBER, row.number)
            .withValue(
                CommonDataKinds.Phone.TYPE,
                when (row.kind) {
                    PhoneKind.MOBILE -> CommonDataKinds.Phone.TYPE_MOBILE
                    PhoneKind.HOME -> CommonDataKinds.Phone.TYPE_HOME
                    PhoneKind.WORK -> CommonDataKinds.Phone.TYPE_WORK
                    PhoneKind.OTHER -> CommonDataKinds.Phone.TYPE_OTHER
                    PhoneKind.CUSTOM -> CommonDataKinds.Phone.TYPE_CUSTOM
                },
            )
            .withValue(CommonDataKinds.Phone.LABEL, row.label)
        is MirrorRow.Mail -> b.withValue(Data.MIMETYPE, CommonDataKinds.Email.CONTENT_ITEM_TYPE)
            .withValue(CommonDataKinds.Email.ADDRESS, row.address)
            .withValue(CommonDataKinds.Email.TYPE, CommonDataKinds.Email.TYPE_OTHER)
        is MirrorRow.Postal -> b.withValue(Data.MIMETYPE, CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE)
            .withValue(CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS, row.formatted)
            .withValue(CommonDataKinds.StructuredPostal.TYPE, CommonDataKinds.StructuredPostal.TYPE_HOME)
        is MirrorRow.Birthday -> b.withValue(Data.MIMETYPE, CommonDataKinds.Event.CONTENT_ITEM_TYPE)
            .withValue(CommonDataKinds.Event.START_DATE, row.date)
            .withValue(CommonDataKinds.Event.TYPE, CommonDataKinds.Event.TYPE_BIRTHDAY)
        is MirrorRow.Note -> b.withValue(Data.MIMETYPE, CommonDataKinds.Note.CONTENT_ITEM_TYPE)
            .withValue(CommonDataKinds.Note.NOTE, row.text)
        is MirrorRow.Extra -> throw IllegalArgumentException("Extra rows are read from the phone, never written")
    }

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)

        private val lock = ReentrantLock()

        /** The app's contact index while its screens are alive: the sync adapter writes through it, so the list follows. */
        @Volatile var liveBook: ContactBook? = null

        private val _runs = MutableStateFlow(0L)
        /** Bumped after every run or failure, whoever ran it (status line, trash snackbar). */
        val runs: StateFlow<Long> = _runs

        private const val PREFS = "contacts"

        /** Keeps the outcome of the last run, read by the settings ("Dernière synchro des contacts : …"). */
        fun record(context: Context, text: String, error: Boolean, now: Long = System.currentTimeMillis()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("mirror_status", text).putLong("mirror_status_at", now).putBoolean("mirror_status_error", error).apply()
            _runs.value = now
        }

        /** The last run's outcome: (time, text, failed), or null before the first run. */
        fun lastStatus(context: Context): Triple<Long, String, Boolean>? {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val text = p.getString("mirror_status", null) ?: return null
            return Triple(p.getLong("mirror_status_at", 0), text, p.getBoolean("mirror_status_error", false))
        }

        fun describe(r: MirrorResult): String {
            if (r.keptBecauseEmpty) {
                return "aucun contact lu dans le dossier : rien n'a été retiré du téléphone" +
                    (if (r.created > 0) " (${r.created} créé${if (r.created > 1) "s" else ""} sur le téléphone, enregistré${if (r.created > 1) "s" else ""})" else "")
            }
            val parts = buildList {
                if (r.inserted + r.updated + r.deleted > 0) add("vers le téléphone : ${r.inserted} ajoutés, ${r.updated} mis à jour, ${r.deleted} retirés")
                if (r.created + r.edited + r.trashed > 0) add("depuis le téléphone : ${r.created} créés, ${r.edited} modifiés, ${r.trashed} supprimés")
                if (r.keptBecauseMassDelete > 0) {
                    add("${r.keptBecauseMassDelete} supprimés d'un coup sur le téléphone : fichiers gardés, remis sur le téléphone (supprime-les dans Mémento)")
                }
                if (r.conflicts > 0) add("${r.conflicts} modifié${if (r.conflicts > 1) "s" else ""} des deux côtés (l'autre version est dans les notes)")
            }
            return "${ContactList.countLabel(r.total)}, " + (if (parts.isEmpty()) "à jour" else parts.joinToString(" ; "))
        }
    }
}
