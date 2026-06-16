package io.github.lycheeappf.tmm.contact

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.content.Context
import android.os.Bundle

/**
 * Stub-Authenticator für den Tesla-Bridge-Contacts-Account.
 *
 * Wir brauchen einen Authenticator, weil Android keine RawContacts in einem
 * Account-Namespace zulässt, der nicht via `AccountManager` registriert ist.
 * Login, Token-Refresh etc. sind hier nicht relevant — der Account ist
 * reiner Datacontainer.
 *
 * Alle Methoden werfen `UnsupportedOperationException`, weil sie nie von
 * unserer App selbst aufgerufen werden sollten und auch keine externen
 * Konsumenten existieren.
 */
class FakeContactAuthenticator(context: Context) : AbstractAccountAuthenticator(context) {

    override fun editProperties(
        response: AccountAuthenticatorResponse?,
        accountType: String?
    ): Bundle = throw UnsupportedOperationException("editProperties not supported")

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?
    ): Bundle? = null

    override fun confirmCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        options: Bundle?
    ): Bundle? = null

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?
    ): Bundle = throw UnsupportedOperationException("getAuthToken not supported")

    override fun getAuthTokenLabel(authTokenType: String?): String =
        throw UnsupportedOperationException("getAuthTokenLabel not supported")

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?
    ): Bundle = throw UnsupportedOperationException("updateCredentials not supported")

    override fun hasFeatures(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        features: Array<out String>?
    ): Bundle = Bundle().apply { putBoolean("booleanResult", false) }
}
