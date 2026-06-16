package io.github.lycheeappf.tmm.contact

import android.Manifest
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schreibt RawContacts in den App-eigenen Tesla-Bridge-Account.
 *
 * Hintergrund: Der AOSP-Bluetooth-MAP-Server generiert die Sender-vCard, indem
 * er aus `Sms.ADDRESS` einen `ContactsContract.PhoneLookup` macht und den
 * `DISPLAY_NAME` einsetzt. Filter dabei: `Contacts.IN_VISIBLE_GROUP = 1`. Ohne
 * `Settings.UNGROUPED_VISIBLE = 1` auf unseren Account sind unsere RawContacts
 * für diesen Lookup unsichtbar — siehe [ensureAccountAndVisibility].
 *
 * Idempotenz: pro Fake-Adresse setzen wir `RawContacts.SOURCE_ID = fakeAddress`.
 * Lookup/Update/Delete laufen über diesen Key — kein fragiler Phone-String-
 * Vergleich nötig.
 *
 * Concurrency: alle schreibenden Methoden serialisieren über einen Singleton-
 * Mutex, sodass parallel ankommende Notifications keine Duplicate RawContacts
 * erzeugen können.
 */
@Singleton
class ContactSyncWriter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val mutex = Mutex()

    /**
     * `true` sobald [ensureAccountAndVisibility] in diesem Prozess einmal komplett
     * durchlief. Spart pro Notification ~3-4 ContactsProvider-IPC (Account-Check,
     * Settings-Insert, Anker-Group-Query) — die alle idempotentes Einmal-Setup sind.
     * Wird in [removeAccount] zurückgesetzt, damit nach einem Reset neu provisioniert
     * wird. `AtomicBoolean`, da auch ausserhalb des [mutex] gelesen/gesetzt
     * (z.B. aus [ContactBackfillWorker]).
     */
    private val accountSetupDone = AtomicBoolean(false)

    /**
     * Letzter erfolgreich geschriebener `fakeAddress -> displayName`. Ein Repeat
     * mit identischem Namen überspringt damit ALLE Provider-Writes (Lookup +
     * Update + Strip). Wird bei Delete/Reset invalidiert — sonst meldete ein
     * Cache-Hit "schon geschrieben" für einen Contact, der gerade gelöscht wurde,
     * und Tesla zeigte wieder die Rohnummer.
     */
    private val lastUpsertedName = ConcurrentHashMap<String, String>()

    init {
        // Observable Invalidierung: Wird der Tesla-Bridge-Account extern entfernt
        // (System-Einstellungen → Konten), verwerfen wir Setup-Flag + Upsert-Dedup,
        // damit die nächste Notification Account + Kontakte neu anlegt. Ohne das
        // bliebe der In-Memory-Cache fälschlich "schon angelegt" und Tesla zeigte
        // bis zum Prozess-Neustart wieder die Rohnummer. Event-getrieben → KEINE
        // Kosten im Notification-Hot-Path (kein Pro-Notification-Polling).
        runCatching {
            AccountManager.get(context).addOnAccountsUpdatedListener(
                { accounts ->
                    if (accounts.none { it.type == FakeContactAccount.ACCOUNT_TYPE }) {
                        accountSetupDone.set(false)
                        lastUpsertedName.clear()
                    }
                },
                null,
                false,
                arrayOf(FakeContactAccount.ACCOUNT_TYPE)
            )
        }.onFailure { Log.w(TAG, "addOnAccountsUpdatedListener failed", it) }
    }

    fun hasReadContacts(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

    fun hasWriteContacts(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.WRITE_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

    /** Beide Kontakt-Rechte. Manche Privacy-ROMs können READ ohne WRITE erteilen — dann
     *  no-op't [upsertContact] still und Tesla sieht keinen Namen. */
    fun hasPermission(): Boolean = hasReadContacts() && hasWriteContacts()

    /**
     * Stellt sicher, dass (a) der Account existiert, (b) `UNGROUPED_VISIBLE=1`
     * gesetzt ist, (c) eine Anker-`AUTO_ADD`-Group existiert.
     *
     * **Visibility-Trick (verifiziert an AOSP `ContactsDatabaseHelper`):**
     * Unsere Contacts müssen `IN_VISIBLE_GROUP=1` haben, damit AOSP-MAP's
     * `getContactNameFromPhone` (Filter `IN_VISIBLE_GROUP=1`) den Namen ins
     * MAP-Listing schreibt — aber sie dürfen NICHT in der Contacts-App des
     * Users auftauchen.
     *
     * Diese beiden Eigenschaften sind in AOSP getrennt:
     *  - `IN_VISIBLE_GROUP` = bei gruppenlosem Contact der Wert von
     *    `Settings.UNGROUPED_VISIBLE` (Clause `CONTACT_IS_VISIBLE`).
     *  - Contacts-App-Liste filtert auf `default_directory`. Ein Contact landet
     *    dort wenn (A) er Mitglied IRGENDEINER Group ist, ODER (B) sein Account
     *    KEINE `AUTO_ADD!=0`-Group hat.
     *
     * Also: Account bekommt eine Anker-Group `AUTO_ADD=1` (unterdrückt (B)),
     * die Contacts bleiben gruppenlos (unterdrückt (A)), `UNGROUPED_VISIBLE=1`
     * macht sie für PhoneLookup sichtbar. Resultat: Tesla zeigt den Namen,
     * die Contacts-App-Liste bleibt sauber.
     *
     * Idempotent — kann beliebig oft aufgerufen werden. Returnt false nur,
     * wenn `addAccountExplicitly` fehlschlägt UND der Account danach immer noch
     * nicht existiert (z.B. fehlende Signatur-Übereinstimmung mit dem
     * Authenticator-Service).
     */
    fun ensureAccountAndVisibility(): Boolean {
        // Fast-Path: Setup lief in diesem Prozess schon erfolgreich durch.
        if (accountSetupDone.get()) return true
        val am = AccountManager.get(context)
        val existing = am.getAccountsByType(FakeContactAccount.ACCOUNT_TYPE)
        if (existing.isEmpty()) {
            val added = try {
                am.addAccountExplicitly(FakeContactAccount.account, null, null)
            } catch (e: SecurityException) {
                Log.e(TAG, "addAccountExplicitly threw SecurityException", e)
                false
            }
            if (!added && am.getAccountsByType(FakeContactAccount.ACCOUNT_TYPE).isEmpty()) {
                Log.e(TAG, "Could not create contacts account — feature disabled")
                return false
            }
        }
        // UNGROUPED_VISIBLE=1 ist kritisch: gruppenlose Contacts werden damit
        // IN_VISIBLE_GROUP=1, sodass AOSP-MAP-PhoneLookup sie findet.
        val settingsValues = ContentValues().apply {
            put(ContactsContract.Settings.ACCOUNT_TYPE, FakeContactAccount.ACCOUNT_TYPE)
            put(ContactsContract.Settings.ACCOUNT_NAME, FakeContactAccount.ACCOUNT_NAME)
            put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
        }
        val settingsOk = try {
            context.contentResolver.insert(ContactsContract.Settings.CONTENT_URI, settingsValues)
            true
        } catch (e: Exception) {
            // Settings-Provider toleriert Existenz unterschiedlich; Update als Fallback.
            runCatching {
                context.contentResolver.update(
                    ContactsContract.Settings.CONTENT_URI,
                    settingsValues,
                    "${ContactsContract.Settings.ACCOUNT_TYPE} = ? AND " +
                        "${ContactsContract.Settings.ACCOUNT_NAME} = ?",
                    arrayOf(FakeContactAccount.ACCOUNT_TYPE, FakeContactAccount.ACCOUNT_NAME)
                )
            }.onFailure { Log.w(TAG, "Settings update fallback failed", it) }.isSuccess
        }
        // Anker-Group anlegen (idempotent). Ihr einziger Zweck: dem Account eine
        // AUTO_ADD-Group geben, damit gruppenlose Contacts NICHT in
        // `default_directory` (= Contacts-App-Liste) landen. KEIN Contact wird
        // jemals Mitglied dieser Group.
        val anchorId = ensureAnchorGroupId()
        // Nur als "fertig" cachen, wenn Sichtbarkeit (UNGROUPED_VISIBLE) UND Anker-
        // Group wirklich stehen. Schlägt eines fehl, bleibt der Flag false, sodass
        // die nächste Notification das idempotente Setup erneut versucht — statt es
        // für die Prozess-Lebensdauer fälschlich als erledigt zu betrachten (sonst
        // zeigte Tesla mangels Sichtbarkeit/Anker dauerhaft die Rohnummer).
        accountSetupDone.set(settingsOk && anchorId != null)
        return true
    }

    /**
     * Erzeugt oder aktualisiert den Contact für `fakeAddress`.
     *
     * Reihenfolge: lookup via SOURCE_ID → wenn vorhanden, aktualisiere nur den
     * DISPLAY_NAME; sonst lege RawContact + StructuredName + Phone in einem
     * `applyBatch` an.
     */
    suspend fun upsertContact(fakeAddress: String, displayName: String): Boolean =
        mutex.withLock {
            if (!hasPermission()) {
                Log.v(TAG, "upsertContact skipped: no permission")
                return@withLock false
            }
            if (!ensureAccountAndVisibility()) return@withLock false

            // Dedup: gleiche (Adresse, Name) wurde in diesem Prozess schon
            // geschrieben → keine Provider-Writes nötig. ensureAccountAndVisibility
            // lief oben bereits, der Erst-Write pro Prozess passiert also weiterhin.
            if (lastUpsertedName[fakeAddress] == displayName) return@withLock true

            val existing = findRawContactIdBySourceId(fakeAddress)
            val result = if (existing != null) {
                updateDisplayName(existing, displayName)
            } else {
                insertNewContact(fakeAddress, displayName)
            }
            if (result) {
                lastUpsertedName[fakeAddress] = displayName
                evictUpsertCacheIfNeeded()
            }
            result
        }

    private fun evictUpsertCacheIfNeeded() {
        if (lastUpsertedName.size <= UPSERT_CACHE_SIZE) return
        // Grobe Eviction (ConcurrentHashMap.keys ist ungeordnet) — ein Miss kostet
        // nur die alten ~3 IPC zurück, nie eine Korrektheits-Verletzung.
        lastUpsertedName.keys.take(lastUpsertedName.size - UPSERT_CACHE_SIZE)
            .forEach { lastUpsertedName.remove(it) }
    }

    suspend fun deleteContact(fakeAddress: String): Int = mutex.withLock {
        if (!hasPermission()) return@withLock 0
        lastUpsertedName.remove(fakeAddress)
        val cr = context.contentResolver
        // SYNCADAPTER-Flag → hard delete (sonst nur Tombstone-Markierung).
        val uri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(
                ContactsContract.RawContacts.ACCOUNT_NAME,
                FakeContactAccount.ACCOUNT_NAME
            )
            .appendQueryParameter(
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                FakeContactAccount.ACCOUNT_TYPE
            )
            .build()
        try {
            cr.delete(
                uri,
                "${ContactsContract.RawContacts.SOURCE_ID} = ? AND " +
                    "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
                arrayOf(fakeAddress, FakeContactAccount.ACCOUNT_TYPE)
            )
        } catch (e: Exception) {
            Log.w(TAG, "deleteContact($fakeAddress) failed", e)
            0
        }
    }

    suspend fun deleteAllContacts(): Int = mutex.withLock {
        if (!hasPermission()) return@withLock 0
        lastUpsertedName.clear()
        val cr = context.contentResolver
        val uri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(
                ContactsContract.RawContacts.ACCOUNT_NAME,
                FakeContactAccount.ACCOUNT_NAME
            )
            .appendQueryParameter(
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                FakeContactAccount.ACCOUNT_TYPE
            )
            .build()
        try {
            cr.delete(
                uri,
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ?",
                arrayOf(FakeContactAccount.ACCOUNT_TYPE)
            )
        } catch (e: Exception) {
            Log.w(TAG, "deleteAllContacts failed", e)
            0
        }
    }

    suspend fun contactCount(): Int = mutex.withLock {
        if (!hasPermission()) return@withLock 0
        try {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                    "${ContactsContract.RawContacts.DELETED} = 0",
                arrayOf(FakeContactAccount.ACCOUNT_TYPE),
                null
            )?.use { it.count } ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "contactCount query failed", e)
            0
        }
    }

    /**
     * Entfernt den Account komplett. System löscht alle assoziierten RawContacts
     * atomic mit. Wird vom Settings-Reset-Button gerufen, um Teslas PBAP-Cache
     * via `account_changes`-Counter-Increment zu zwingen, neu zu syncen.
     */
    suspend fun removeAccount(): Boolean = mutex.withLock {
        // Unter demselben Mutex wie upsert/delete — sonst kann AccountManager
        // die `account_changes`-Counter erhöhen während ein paralleler
        // applyBatch noch in der Provider-Pipeline steckt, was zu undefiniertem
        // Verhalten beim Tesla-PBAP-Cache führt.
        // Account verschwindet → Setup-Cache und Upsert-Dedup invalidieren, sonst
        // bliebe nach einem Reset ein veralteter "schon angelegt"-Zustand.
        accountSetupDone.set(false)
        lastUpsertedName.clear()
        val am = AccountManager.get(context)
        try {
            am.removeAccountExplicitly(FakeContactAccount.account)
        } catch (e: SecurityException) {
            Log.w(TAG, "removeAccountExplicitly failed", e)
            false
        }
    }

    private fun findRawContactIdBySourceId(sourceId: String): Long? {
        val cr = context.contentResolver
        return try {
            cr.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                    "${ContactsContract.RawContacts.SOURCE_ID} = ? AND " +
                    "${ContactsContract.RawContacts.DELETED} = 0",
                arrayOf(FakeContactAccount.ACCOUNT_TYPE, sourceId),
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "findRawContactIdBySourceId failed", e)
            null
        }
    }

    private fun updateDisplayName(rawContactId: Long, displayName: String): Boolean {
        val cr = context.contentResolver
        val values = ContentValues().apply {
            put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
        }
        return try {
            val rows = cr.update(
                ContactsContract.Data.CONTENT_URI,
                values,
                "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(
                    rawContactId.toString(),
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                )
            )
            if (rows == 0) {
                // StructuredName fehlte (unwahrscheinlich aber möglich) → neu anlegen.
                val insertValues = ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    put(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                    )
                    put(
                        ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                        displayName
                    )
                }
                cr.insert(ContactsContract.Data.CONTENT_URI, insertValues)
            }
            // Invariante: unsere Contacts sind IMMER gruppenlos. Falls dieser
            // Contact aus einer alten App-Version noch eine GroupMembership hat
            // (v1-Schema mit sichtbarer Group), entfernen wir sie — sonst würde
            // der Contact via default_directory in der Phone-Contacts-App
            // auftauchen. So wird der Übergang alt→neu sauber, auch ohne dass
            // der User "Tesla-Kontakte neu syncen" tippt.
            stripGroupMemberships(rawContactId)
            true
        } catch (e: Exception) {
            Log.w(TAG, "updateDisplayName failed", e)
            false
        }
    }

    /** Entfernt alle GroupMembership-Data-Rows eines RawContacts (Invariante: gruppenlos). */
    private fun stripGroupMemberships(rawContactId: Long) {
        try {
            val deleted = context.contentResolver.delete(
                ContactsContract.Data.CONTENT_URI,
                "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(
                    rawContactId.toString(),
                    ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
                )
            )
            if (deleted > 0) Log.i(TAG, "Stripped $deleted stale group membership(s) from rawContact $rawContactId")
        } catch (e: Exception) {
            Log.w(TAG, "stripGroupMemberships failed for $rawContactId", e)
        }
    }

    private fun insertNewContact(fakeAddress: String, displayName: String): Boolean {
        // CALLER_IS_SYNCADAPTER markiert die Operation als Sync-Adapter-initiated:
        // Android setzt dirty=0 (kein Sync-Request für unseren Pseudo-Account)
        // und überspringt einige Aggregation-Heuristiken, die unsere isolierten
        // Fake-Kontakte mit echten User-Kontakten verschmelzen könnten.
        val rawContactsUri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(
                ContactsContract.RawContacts.ACCOUNT_NAME,
                FakeContactAccount.ACCOUNT_NAME
            )
            .appendQueryParameter(
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                FakeContactAccount.ACCOUNT_TYPE
            )
            .build()
        // WICHTIG: Der Contact bleibt GRUPPENLOS (keine GroupMembership-Op unten).
        // Zusammen mit der Anker-AUTO_ADD-Group (aus ensureAccountAndVisibility)
        // und UNGROUPED_VISIBLE=1 bedeutet das:
        //   - IN_VISIBLE_GROUP=1 (via UNGROUPED_VISIBLE, da gruppenlos) → MAP findet ihn
        //   - NICHT in default_directory (gruppenlos + Account hat Anker-Group)
        //     → NICHT in der Contacts-App-Liste sichtbar.
        // Würde der Contact einer Group beitreten, landete er in default_directory
        // und würde das Phonebook des Users zumüllen.
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(rawContactsUri)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, FakeContactAccount.ACCOUNT_TYPE)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, FakeContactAccount.ACCOUNT_NAME)
                .withValue(ContactsContract.RawContacts.SOURCE_ID, fakeAddress)
                // AGGREGATION_MODE_DISABLED verhindert, dass Android unsere
                // isolierten Fake-Contacts mit User-Contacts gleichen Namens
                // ("Anna", "Mama") aggregiert.
                .withValue(
                    ContactsContract.RawContacts.AGGREGATION_MODE,
                    ContactsContract.RawContacts.AGGREGATION_MODE_DISABLED
                )
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                )
                .withValue(
                    ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                    displayName
                )
                .build(),
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                )
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, fakeAddress)
                // NORMALIZED_NUMBER explizit setzen — `phone_lookup`-Index nutzt
                // die normalized Variante. Wenn null, fällt der Index leer, und
                // PhoneLookup(+9994...) findet nichts.
                .withValue(
                    ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                    fakeAddress
                )
                .withValue(
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                )
                .build()
        )
        return try {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Log.i(TAG, "Inserted contact $displayName ($fakeAddress)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "insertNewContact failed for $fakeAddress", e)
            false
        }
    }

    /**
     * Lazy-erzeugt die **Anker-Group** für unseren Account: `AUTO_ADD=1`,
     * `GROUP_VISIBLE=0`. Idempotent — findet bestehende Group via SOURCE_ID-Match.
     *
     * Zweck: Sie ist die einzige `AUTO_ADD!=0`-Group im Account. Dadurch greift
     * der AOSP-Trigger `insertContactsWithAccountNoDefaultGroup` NICHT mehr (der
     * sonst ALLE gruppenlosen Contacts des Accounts in `default_directory` =
     * Contacts-App-Liste schöbe). KEIN Contact wird je Mitglied dieser Group —
     * sie ist reine "Existenz-Bedingung". `GROUP_VISIBLE=0`, damit die Group
     * selbst nicht als sichtbarer Eintrag auftaucht.
     *
     * Returnt die Group-_ID oder null bei IO-Fehler.
     */
    private fun ensureAnchorGroupId(): Long? {
        val cr = context.contentResolver
        val existing = try {
            cr.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(ContactsContract.Groups._ID),
                "${ContactsContract.Groups.ACCOUNT_TYPE} = ? AND " +
                    "${ContactsContract.Groups.ACCOUNT_NAME} = ? AND " +
                    "${ContactsContract.Groups.SOURCE_ID} = ?",
                arrayOf(
                    FakeContactAccount.ACCOUNT_TYPE,
                    FakeContactAccount.ACCOUNT_NAME,
                    GROUP_SOURCE_ID
                ),
                null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        } catch (e: Exception) {
            Log.w(TAG, "Group lookup failed", e)
            null
        }
        if (existing != null) return existing

        val groupValues = ContentValues().apply {
            put(ContactsContract.Groups.ACCOUNT_TYPE, FakeContactAccount.ACCOUNT_TYPE)
            put(ContactsContract.Groups.ACCOUNT_NAME, FakeContactAccount.ACCOUNT_NAME)
            put(ContactsContract.Groups.SOURCE_ID, GROUP_SOURCE_ID)
            put(ContactsContract.Groups.TITLE, "Tesla Messages Manager (anchor)")
            // AUTO_ADD=1: macht dies zur einzigen auto-add-Group des Accounts →
            // unterdrückt den default_directory-Trigger für gruppenlose Contacts.
            put(ContactsContract.Groups.AUTO_ADD, 1)
            // GROUP_VISIBLE=0: die Group selbst soll nicht als sichtbare Gruppe
            // erscheinen. Unsere Contacts sind eh gruppenlos, ihre Sichtbarkeit
            // kommt aus UNGROUPED_VISIBLE, nicht aus dieser Group.
            put(ContactsContract.Groups.GROUP_VISIBLE, 0)
            // Kein SHOULD_SYNC — die Anker-Group ist ein reines lokales Konstrukt.
        }
        val groupUri = ContactsContract.Groups.CONTENT_URI.buildUpon()
            .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(
                ContactsContract.Groups.ACCOUNT_NAME,
                FakeContactAccount.ACCOUNT_NAME
            )
            .appendQueryParameter(
                ContactsContract.Groups.ACCOUNT_TYPE,
                FakeContactAccount.ACCOUNT_TYPE
            )
            .build()
        return try {
            val uri = cr.insert(groupUri, groupValues)
            uri?.lastPathSegment?.toLongOrNull().also {
                if (it != null) Log.i(TAG, "Created anchor group $it (AUTO_ADD=1, GROUP_VISIBLE=0)")
                else Log.w(TAG, "Anchor group insert returned null URI")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Anchor group insert failed — contacts may leak into default_directory", e)
            null
        }
    }

    companion object {
        private const val TAG = "ContactSyncWriter"
        // v2: Anker-Group-Schema (AUTO_ADD=1). v1 war die alte sichtbare Group —
        // neue SOURCE_ID erzwingt Neuanlage statt Update der alten Visible-Group.
        private const val GROUP_SOURCE_ID = "tesla_bridge_anchor_group_v2"
        private const val UPSERT_CACHE_SIZE = 500
    }
}
