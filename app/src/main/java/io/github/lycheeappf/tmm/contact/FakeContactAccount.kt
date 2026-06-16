package io.github.lycheeappf.tmm.contact

import android.accounts.Account

/**
 * Konstanten für den App-eigenen Contacts-Account.
 *
 * Pro Mapping (z.B. "+9994200000042" → "Anna") wird ein `RawContact` in diesem
 * Account-Namespace abgelegt. Tesla löst beim Bluetooth-MAP-Export bzw. PBAP-
 * Lookup die Fake-Nummer via `ContactsContract.PhoneLookup` auf und findet so
 * den Klartextnamen.
 *
 * Cleanup: bei App-Uninstall entfernt Android den Account → alle zugehörigen
 * RawContacts werden atomic gelöscht.
 */
object FakeContactAccount {
    const val ACCOUNT_TYPE = "io.github.lycheeappf.tmm.contacts"
    const val ACCOUNT_NAME = "Tesla Messages Manager Contacts"

    val account: Account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)
}
