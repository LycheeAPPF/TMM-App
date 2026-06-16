package io.github.lycheeappf.tmm.sms.provider

import android.content.Context
import android.content.SharedPreferences
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolved `Telephony.Threads.getOrCreateThreadId(...)` für eine Fake-Adresse.
 *
 * Persistiert die Auflösung in SharedPreferences, damit nach App-Prozess-Neustart
 * keine zusätzlichen ContentProvider-Lookups nötig sind. Das System selbst ist
 * idempotent — der Cache ist eine reine Performance-Optimierung.
 */
@Singleton
class ThreadIdResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOrCreate(address: String): Long {
        prefs.getLong(address, -1L).takeIf { it > 0 }?.let { return it }
        val id = Telephony.Threads.getOrCreateThreadId(context, address)
        prefs.edit().putLong(address, id).apply()
        return id
    }

    fun invalidate(address: String) {
        prefs.edit().remove(address).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "mfs_thread_ids"
    }
}
