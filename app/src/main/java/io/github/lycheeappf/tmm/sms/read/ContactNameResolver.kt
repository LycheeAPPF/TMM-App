package io.github.lycheeappf.tmm.sms.read

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Löst eine Telefonnummer über [ContactsContract.PhoneLookup] auf einen
 * Anzeigenamen auf — dieselbe Mechanik, die auch Tesla per PBAP nutzt, sodass
 * die Inbox dieselben Namen zeigt wie das Auto.
 *
 * Ohne `READ_CONTACTS` oder ohne Treffer wird null zurückgegeben (Aufrufer
 * fällt auf die Rohnummer zurück). Ergebnisse werden in-memory gecacht
 * (Nummern wiederholen sich über viele Rows), inkl. „kein Treffer"-Negativ-Cache.
 */
@Singleton
class ContactNameResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Sentinel für „aufgelöst, aber kein Name" (Negativ-Cache), da ConcurrentHashMap kein null erlaubt.
    private val cache = ConcurrentHashMap<String, String>()

    fun hasContactsRead(): Boolean =
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

    /** @return Kontaktname oder null (keine Permission / leer / kein Treffer). */
    fun resolve(address: String): String? {
        if (address.isBlank()) return null
        if (!hasContactsRead()) return null
        cache[address]?.let { return it.ifEmpty { null } }

        val name = runCatching { lookup(address) }.getOrElse {
            Log.w(TAG, "PhoneLookup failed", it)
            null
        }
        cache[address] = name.orEmpty() // "" = Negativ-Cache
        return name
    }

    private fun lookup(address: String): String? {
        val uri: Uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address)
        )
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        }
        return null
    }

    companion object {
        private const val TAG = "ContactNameResolver"
    }
}
