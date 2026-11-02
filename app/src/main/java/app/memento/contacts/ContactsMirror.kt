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

/** What one run of the mirror did. */
data class MirrorResult(val inserted: Int, val updated: Int, val deleted: Int, val total: Int, val keptBecauseEmpty: Boolean = false)

/**
 * Writes a copy of the contacts into the phone's contacts provider under the app's own account, so the dialer
 * and the messaging apps know the names (Part C §5 L1). One way: the files are the truth, edits made in the
 * phone's contacts app are overwritten. All decisions are in [MirrorPlan]; this is the provider glue.
 * Blocking: call from an I/O dispatcher.
 */
class ContactsMirror(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val accounts: AccountManager = AccountManager.get(context)

    fun hasPermissions(): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** The app's account, if it exists (an account of its own type needs no permission to be listed). */
    fun account(): Account? = accounts.getAccountsByType(MirrorPlan.ACCOUNT_TYPE).firstOrNull()

    /** Creates the account if needed and makes its contacts visible in the dialer and the contacts app. */
    fun ensureAccount(): Account {
        val account = account() ?: Account(MirrorPlan.ACCOUNT_NAME, MirrorPlan.ACCOUNT_TYPE).also {
            if (!accounts.addAccountExplicitly(it, null, null)) throw IllegalStateException("Impossible de créer le compte ${it.name}")
        }
        ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, false)
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

    private fun existing(account: Account): List<MirrorRaw> {
        val uri = syncUri(RawContacts.CONTENT_URI, account)
        val cols = arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1, RawContacts.DIRTY, RawContacts.DELETED)
        val out = ArrayList<MirrorRaw>()
        resolver.query(uri, cols, "${RawContacts.ACCOUNT_TYPE}=? AND ${RawContacts.ACCOUNT_NAME}=?", arrayOf(account.type, account.name), null)
            ?.use { c ->
                while (c.moveToNext()) {
                    out.add(MirrorRaw(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) != 0, c.getInt(4) != 0))
                }
            } ?: throw IllegalStateException("Le carnet d'adresses du téléphone ne répond pas")
        return out
    }

    /** Brings the provider in line with [contacts]. Throws on any failure (permission, provider): nothing is hidden. */
    fun sync(contacts: List<Contact>): MirrorResult {
        if (!hasPermissions()) throw SecurityException("Autorisation Contacts refusée")
        val account = ensureAccount()
        val existing = existing(account)
        val wanted = MirrorPlan.mirrored(contacts)
        // An unreadable folder (sync in progress, SAF trouble) looks empty: never wipe the phone on that.
        val keep = wanted.isEmpty() && existing.any { !it.deleted }
        val ops = if (keep) emptyList() else MirrorPlan.diff(contacts, existing)
        for (batch in MirrorPlan.batches(ops)) {
            val list = ArrayList<ContentProviderOperation>()
            batch.forEach { add(list, it, account) }
            resolver.applyBatch(ContactsContract.AUTHORITY, list)
        }
        return MirrorResult(
            ops.count { it is MirrorOp.Insert }, ops.count { it is MirrorOp.Update }, ops.count { it is MirrorOp.Delete },
            wanted.size, keep,
        )
    }

    private fun add(list: ArrayList<ContentProviderOperation>, op: MirrorOp, account: Account) {
        val raws = syncUri(RawContacts.CONTENT_URI, account)
        val data = syncUri(Data.CONTENT_URI, account)
        when (op) {
            is MirrorOp.Delete -> list.add(ContentProviderOperation.newDelete(ContentUris.withAppendedId(raws, op.rawId)).build())
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
                list.add(ContentProviderOperation.newDelete(data).withSelection("${Data.RAW_CONTACT_ID}=?", arrayOf(op.rawId.toString())).build())
                list.add(
                    ContentProviderOperation.newUpdate(ContentUris.withAppendedId(raws, op.rawId))
                        .withValue(RawContacts.SYNC1, op.hash)
                        .withValue(RawContacts.DIRTY, 0)
                        .withValue(RawContacts.STARRED, if (op.contact.favorite) 1 else 0)
                        .build(),
                )
                MirrorPlan.rows(op.contact).forEach { row ->
                    list.add(values(ContentProviderOperation.newInsert(data).withValue(Data.RAW_CONTACT_ID, op.rawId), row).build())
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
    }

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    }
}
