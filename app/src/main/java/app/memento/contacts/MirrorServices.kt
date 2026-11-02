package app.memento.contacts

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import app.memento.MainActivity
import app.memento.NoteRepository
import app.memento.Settings
import app.memento.agenda.AgendaData

/**
 * The account the mirrored contacts belong to has no credentials: this authenticator only exists so the
 * account type is registered. "Ajouter un compte" in the phone's settings opens the app instead.
 */
class MirrorAuthenticator(private val context: Context) : AbstractAccountAuthenticator(context) {
    override fun addAccount(
        response: AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?,
        requiredFeatures: Array<out String>?, options: Bundle?,
    ): Bundle = Bundle().apply {
        putParcelable(AccountManager.KEY_INTENT, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle =
        throw UnsupportedOperationException()

    override fun confirmCredentials(response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle? = null

    override fun getAuthToken(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle =
        throw UnsupportedOperationException()

    override fun getAuthTokenLabel(authTokenType: String?): String? = null

    override fun updateCredentials(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle =
        throw UnsupportedOperationException()

    override fun hasFeatures(response: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?): Bundle =
        Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
}

class MirrorAuthenticatorService : Service() {
    private val authenticator by lazy { MirrorAuthenticator(this) }
    override fun onBind(intent: Intent?): IBinder? = authenticator.iBinder
}

/**
 * The account's contacts sync adapter. With `supportsUploading` and automatic sync on, Android calls it when the
 * phone's contacts app created, edited or deleted a contact of the account (DIRTY raw contacts): it runs the
 * same reconciliation as the app ([ContactsMirror.sync]), even with the app closed. A failure is recorded for the
 * settings and counted in [SyncResult] so Android retries later; it is never dropped.
 */
class MirrorSyncAdapter(context: Context) : AbstractThreadedSyncAdapter(context, true, false) {
    override fun onPerformSync(account: Account?, extras: Bundle?, authority: String?, provider: ContentProviderClient?, syncResult: SyncResult?) {
        val app = context.applicationContext
        if (!app.getSharedPreferences("contacts", Context.MODE_PRIVATE).getBoolean("mirror", false)) return
        val settings = Settings(app)
        if (!AgendaData.storageReady(app, settings)) {
            ContactsMirror.record(app, "Dossier des notes inaccessible (autorisation de stockage) : rien n'a été synchronisé", error = true)
            syncResult?.stats?.numAuthExceptions = (syncResult?.stats?.numAuthExceptions ?: 0) + 1
            return
        }
        try {
            val book = ContactsMirror.liveBook ?: NoteRepository(app, settings).let { repo -> ContactBook { repo.fs } }
            val r = ContactsMirror(app).sync(book)
            syncResult?.stats?.let { s ->
                s.numInserts += (r.inserted + r.created).toLong()
                s.numUpdates += (r.updated + r.edited).toLong()
                s.numDeletes += (r.deleted + r.trashed).toLong()
            }
        } catch (e: SecurityException) {
            ContactsMirror.record(app, "Autorisation Contacts refusée : ouvre les autorisations de l'app", error = true)
            syncResult?.stats?.let { it.numAuthExceptions++ }
        } catch (e: Exception) {
            android.util.Log.e("MirrorSyncAdapter", "contacts sync failed", e)
            ContactsMirror.record(app, "Échec : ${e.message ?: e.javaClass.simpleName}", error = true)
            syncResult?.stats?.let { it.numIoExceptions++ }
        }
    }
}

class MirrorSyncService : Service() {
    private val adapter by lazy { MirrorSyncAdapter(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = adapter.syncAdapterBinder
}
