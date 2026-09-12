package io.github.lycheeappf.tmm.contact

import android.accounts.Account
import io.github.lycheeappf.tmm.BuildConfig

/**
 * Konstanten für den App-eigenen Contacts-Account.
 *
 * Pro Mapping (z.B. "+888000000042" → "Anna") wird ein `RawContact` in diesem
 * Account-Namespace abgelegt. Tesla löst beim Bluetooth-MAP-Export bzw. PBAP-
 * Lookup die Fake-Nummer via `ContactsContract.PhoneLookup` auf und findet so
 * den Klartextnamen.
 *
 * Cleanup: bei App-Uninstall entfernt Android den Account → alle zugehörigen
 * RawContacts werden atomic gelöscht.
 */
object FakeContactAccount {
    // Pro Build-Variante eigener Typ (muss `contacts_account_type` in build.gradle.kts
    // entsprechen): Android registriert einen Account-Typ nur für EINEN Authenticator-
    // Uid, sonst könnte die Debug-App neben der Release-App keine Kontakte anlegen.
    const val ACCOUNT_TYPE = BuildConfig.APPLICATION_ID + ".contacts"
    const val ACCOUNT_NAME = "Tesla Messages Manager Contacts"

    val account: Account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)
}
