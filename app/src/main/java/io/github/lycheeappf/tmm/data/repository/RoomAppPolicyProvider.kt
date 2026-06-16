package io.github.lycheeappf.tmm.data.repository

import io.github.lycheeappf.tmm.data.db.AppPolicyDao
import io.github.lycheeappf.tmm.listener.filter.AppPolicyProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-basierte Implementation der App-Whitelist. Cached häufige Lookups
 * (Notification-Listener wird oft mehrmals pro Sekunde aufgerufen).
 *
 * Standardmäßig ist KEINE App whitelisted: bei einem DB-Miss liefert
 * [isWhitelisted] `false`. Der User aktiviert die gewünschten Messenger
 * (z.B. WhatsApp, Telegram, Signal) selbst im Whitelist-Screen — erst dann
 * existiert eine DB-Row und Notifications dieser App werden erfasst.
 */
@Singleton
class RoomAppPolicyProvider @Inject constructor(
    private val dao: AppPolicyDao
) : AppPolicyProvider {

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    override suspend fun isWhitelisted(packageName: String): Boolean {
        cache[packageName]?.let { return it }

        val dbValue = dao.isWhitelisted(packageName)
        if (dbValue != null) {
            cache[packageName] = dbValue
            return dbValue
        }

        // DB-Miss: keine App ist standardmäßig whitelisted.
        // NICHT cachen — sobald der User die App aktiviert, übernimmt die DB-Quelle.
        return false
    }

    fun invalidateCache(packageName: String? = null) {
        if (packageName == null) cache.clear() else cache.remove(packageName)
    }
}
