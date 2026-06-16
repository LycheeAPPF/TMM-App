package io.github.lycheeappf.tmm.sms.outbound

import android.content.ContentUris
import android.content.Context
import android.provider.Telephony
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.core.model.AddressScheme
import io.github.lycheeappf.tmm.core.model.FakeAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Räumt nach Tesla-Replies die Outbox/Failed-Row auf, sodass keine
 * "SMS fehlgeschlagen"-System-Notification aufpoppt und der Provider
 * sauber bleibt.
 */
@Singleton
class FailedRowCleaner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun delete(rowId: Long): Int = try {
        val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, rowId)
        context.contentResolver.delete(uri, null, null)
    } catch (e: Exception) {
        Log.w(TAG, "delete(rowId=$rowId) failed", e)
        0
    }

    /**
     * Periodischer Cleanup: Findet alle FAILED-Rows mit Fake-Adresse und löscht sie.
     * Verwendet [FakeAddress.parse] zur exakten Validierung — `LIKE prefix%` allein
     * könnte reale Telefonnummern im selben Range (z.B. `+4932...` als DE-Personal-Number)
     * versehentlich treffen.
     */
    fun deleteAllFailedFakeRows(): Int {
        val candidates = queryFailedCandidates() ?: return 0
        var deleted = 0
        for ((rowId, address) in candidates) {
            // Doppelt validieren: nur Adressen, die FakeAddress.parse akzeptiert
            if (FakeAddress.parse(address) == null) continue
            try {
                val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, rowId)
                deleted += context.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete row $rowId ($address)", e)
            }
        }
        return deleted
    }

    private fun queryFailedCandidates(): List<Pair<Long, String>>? {
        // Pre-Filter über LIKE auf alle Schema-Prefixes (cheap), Final-Validierung
        // dann pro Row via FakeAddress.parse.
        val prefixClauses = AddressScheme.entries.joinToString(" OR ") {
            "${Telephony.Sms.ADDRESS} LIKE ?"
        }
        val args = mutableListOf(Telephony.Sms.MESSAGE_TYPE_FAILED.toString())
        AddressScheme.entries.forEach { args.add("${it.prefix}%") }

        return try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS),
                "${Telephony.Sms.TYPE} = ? AND ($prefixClauses)",
                args.toTypedArray(),
                null
            )?.use { c ->
                val list = mutableListOf<Pair<Long, String>>()
                while (c.moveToNext()) {
                    list += c.getLong(0) to c.getString(1).orEmpty()
                }
                list
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryFailedCandidates failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "FailedRowCleaner"
    }
}
