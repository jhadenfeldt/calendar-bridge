package dev.jhadenfeldt.calendarbridge.android.account

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/** Android requires an authenticator for the account that owns our calendars; there are no credentials. */
class AuthenticatorService : Service() {
    private lateinit var authenticator: Authenticator
    override fun onCreate() { authenticator = Authenticator(this) }
    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder

    private class Authenticator(context: Context) : AbstractAccountAuthenticator(context) {
        override fun editProperties(r: AccountAuthenticatorResponse?, accountType: String?): Bundle? = null
        override fun addAccount(r: AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?, features: Array<out String>?, options: Bundle?): Bundle? = null
        override fun confirmCredentials(r: AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle? = null
        override fun getAuthToken(r: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle? = null
        override fun getAuthTokenLabel(authTokenType: String?): String? = null
        override fun updateCredentials(r: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle? = null
        override fun hasFeatures(r: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?): Bundle =
            Bundle().apply { putBoolean(android.accounts.AccountManager.KEY_BOOLEAN_RESULT, false) }
    }
}
