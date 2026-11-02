package app.scriptorium.contacts

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
import app.scriptorium.MainActivity

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
 * Declared so the contacts provider and the contacts app treat the account as a contacts account (with
 * `contacts.xml`). It does nothing: the app itself writes the copies when it runs ([ContactsMirror]).
 */
class MirrorSyncService : Service() {
    private val adapter by lazy {
        object : AbstractThreadedSyncAdapter(applicationContext, true) {
            override fun onPerformSync(account: Account?, extras: Bundle?, authority: String?, provider: ContentProviderClient?, syncResult: SyncResult?) = Unit
        }
    }

    override fun onBind(intent: Intent?): IBinder? = adapter.syncAdapterBinder
}
