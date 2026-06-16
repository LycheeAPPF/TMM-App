package io.github.lycheeappf.tmm.contact

import android.content.Context
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.core.util.LogBuffer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ergebnis des Sender-Resolution-Selbsttests (Diagnose-Screen). Bündelt die
 * Permission-Lage, ob der Contact angelegt werden konnte und ob die AOSP-MAP-
 * identische PhoneLookup-Query unseren Contact findet — also ob Tesla später
 * den Namen statt der Rohnummer ins Sender-Listing bekommt.
 */
data class SenderResolutionResult(
    val hasRead: Boolean,
    val hasWrite: Boolean,
    val contactCount: Int,
    val upsertOk: Boolean,
    val phoneLookupFound: Boolean,
    val resolvedName: String?,
    // Diagnose-Sonden: lokalisieren WO PhoneLookup bricht.
    // (AOSP verwirft den IN_VISIBLE_GROUP-Filter in case PHONE_LOOKUP, also ist
    //  Sichtbarkeit NICHT die Ursache; entscheidend sind contactId + Nummer-Match.)
    val rawContactId: Long? = null,
    /** NULL ⇒ AGGREGATION_MODE_DISABLED ließ keine Aggregat-Contact-Zeile entstehen
     *  ⇒ der PhoneLookup-INNER-JOIN (contacts_view._id = raw_contacts.contact_id) bricht. */
    val contactId: Long? = null,
    val storedNumber: String? = null,
    val storedNormalized: String? = null,
    /** formatNumberToE164 — bei der Test-Range NULL (ungültiger Ländercode) ⇒ Strict-Branch tot. */
    val computedE164: String? = null,
    /** Red Herring (PhoneLookup verwirft den Filter), nur zur Vollständigkeit. */
    val inVisibleGroup: Int? = null,
    val ungroupedVisible: Int? = null
)

/**
 * Reine Diagnose-Schicht für den Clean-Name-Pfad. Liegt bewusst NEBEN dem schlanken
 * [ContactSyncWriter]: nur der Diagnose-Screen nutzt diese (potentiell teuren)
 * lesenden Sonden, der Produktiv-Pfad (Capture → Inject) bleibt davon unberührt.
 *
 * Macht KEINEN SMS-Inject (rein lesend Richtung Tesla) und delegiert das eigentliche
 * Schreiben an den [ContactSyncWriter].
 */
@Singleton
class ContactDiagnostics @Inject constructor(
    @ApplicationContext private val context: Context,
    private val writer: ContactSyncWriter,
    private val logBuffer: LogBuffer
) {

    /**
     * Diagnose-Selbsttest: legt (idempotent) den Contact für [fakeAddress]/
     * [displayName] an und prüft via derselben Query wie AOSP-MAP, ob PhoneLookup
     * ihn findet. Liefert die Bausteine für die Diagnose-UI. So kann der User
     * on-device sehen, an welcher Stelle der Pfad bricht, ohne Logcat/adb.
     */
    suspend fun testSenderResolution(fakeAddress: String, displayName: String): SenderResolutionResult {
        val hasRead = writer.hasReadContacts()
        val hasWrite = writer.hasWriteContacts()
        val upsertOk = if (hasRead && hasWrite) writer.upsertContact(fakeAddress, displayName) else false
        val lookup = queryPhoneLookup(fakeAddress)
        val count = writer.contactCount()

        // Diagnose-Sonden: das Verbindungsglied lokalisieren, das bricht.
        val (rawId, contactId) = queryRawAndContactId(fakeAddress)
        val stored = rawId?.let { queryStoredPhone(it) }
        val inVis = contactId?.let { queryInVisibleGroup(it) }
        val ungrouped = queryUngroupedVisible()
        val e164 = runCatching {
            PhoneNumberUtils.formatNumberToE164(
                fakeAddress, Locale.getDefault().country.ifBlank { "DE" }
            )
        }.getOrNull()

        logBuffer.info(
            TAG,
            "SenderDiag addr=$fakeAddress name='$displayName' read=$hasRead write=$hasWrite " +
                "upsert=$upsertOk lookup=${lookup.found} resolved='${lookup.resolvedName}' " +
                "rawId=$rawId contactId=$contactId storedNum='${stored?.first}' " +
                "storedNorm='${stored?.second}' e164=$e164 inVisibleGroup=$inVis ungroupedVisible=$ungrouped"
        )
        return SenderResolutionResult(
            hasRead = hasRead,
            hasWrite = hasWrite,
            contactCount = count,
            upsertOk = upsertOk,
            phoneLookupFound = lookup.found,
            resolvedName = lookup.resolvedName,
            rawContactId = rawId,
            contactId = contactId,
            storedNumber = stored?.first,
            storedNormalized = stored?.second,
            computedE164 = e164,
            inVisibleGroup = inVis,
            ungroupedVisible = ungrouped
        )
    }

    /** (rawContactId, contactId) für unsere Fake-Adresse. contactId NULL ⇒ keine
     *  Aggregat-Contact-Zeile ⇒ PhoneLookup-JOIN findet nichts. */
    private fun queryRawAndContactId(fakeAddress: String): Pair<Long?, Long?> =
        safeQuery("queryRawAndContactId", null to null) {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.CONTACT_ID),
                "${ContactsContract.RawContacts.ACCOUNT_TYPE} = ? AND " +
                    "${ContactsContract.RawContacts.SOURCE_ID} = ? AND " +
                    "${ContactsContract.RawContacts.DELETED} = 0",
                arrayOf(FakeContactAccount.ACCOUNT_TYPE, fakeAddress),
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    (c.getLong(0)) to (if (c.isNull(1)) null else c.getLong(1))
                } else null to null
            } ?: (null to null)
        }

    /** (NUMBER, NORMALIZED_NUMBER) wie vom Provider gespeichert (data4 wird recomputed). */
    private fun queryStoredPhone(rawContactId: Long): Pair<String?, String?> =
        safeQuery("queryStoredPhone", null to null) {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
                ),
                "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(
                    rawContactId.toString(),
                    ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                ),
                null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null to null }
                ?: (null to null)
        }

    private fun queryInVisibleGroup(contactId: Long): Int? =
        safeQuery("queryInVisibleGroup", null) {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.IN_VISIBLE_GROUP),
                "${ContactsContract.Contacts._ID} = ?",
                arrayOf(contactId.toString()),
                null
            )?.use { c -> if (c.moveToFirst()) c.getInt(0) else null }
        }

    private fun queryUngroupedVisible(): Int? =
        safeQuery("queryUngroupedVisible", null) {
            context.contentResolver.query(
                ContactsContract.Settings.CONTENT_URI,
                arrayOf(ContactsContract.Settings.UNGROUPED_VISIBLE),
                "${ContactsContract.Settings.ACCOUNT_TYPE} = ? AND " +
                    "${ContactsContract.Settings.ACCOUNT_NAME} = ?",
                arrayOf(FakeContactAccount.ACCOUNT_TYPE, FakeContactAccount.ACCOUNT_NAME),
                null
            )?.use { c -> if (c.moveToFirst()) c.getInt(0) else null }
        }

    private data class PhoneLookupResult(val found: Boolean, val resolvedName: String?)

    /**
     * Spiegelt EXAKT die Query, die AOSP-MAP `getContactNameFromPhone` macht:
     * `PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI` + `selection IN_VISIBLE_GROUP=1`.
     * Damit liefert sie dasselbe Ergebnis das Tesla später ins Sender-Listing
     * bekommt — kein falsch-positiv durch eine ungefilterte Query.
     */
    private fun queryPhoneLookup(fakeAddress: String): PhoneLookupResult =
        safeQuery("queryPhoneLookup($fakeAddress)", PhoneLookupResult(false, null)) {
            val uri = ContactsContract.PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI
                .buildUpon()
                .appendPath(fakeAddress)
                .build()
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.PhoneLookup._ID,
                    ContactsContract.PhoneLookup.DISPLAY_NAME
                ),
                "${ContactsContract.Contacts.IN_VISIBLE_GROUP} = 1",
                null, null
            )?.use { c ->
                if (c.moveToFirst()) PhoneLookupResult(true, c.getString(1).orEmpty())
                else PhoneLookupResult(false, null)
            } ?: PhoneLookupResult(false, null)
        }

    /** Wrappt eine lesende Provider-Query: bei Exception Log.w + [default] zurück. */
    private inline fun <T> safeQuery(label: String, default: T, block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$label failed", e)
            default
        }

    companion object {
        private const val TAG = "ContactDiagnostics"
    }
}
