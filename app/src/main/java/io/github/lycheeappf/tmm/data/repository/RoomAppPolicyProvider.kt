package io.github.lycheeappf.tmm.data.repository

import io.github.lycheeappf.tmm.data.db.AppPolicyDao
import io.github.lycheeappf.tmm.listener.filter.AppPolicyProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-basierte Implementation der App-Whitelist. Cached häufige Lookups
 * (Notification-Listener wird oft mehrmals pro Sekunde aufgerufen).
 *
 * Race-Resilienz: Wenn der App-Start-Seed (AppPolicySeed) noch nicht durchgelaufen
 * ist, ist die DB für die Default-Packages leer und würde `isWhitelisted("com.beeper.android")`
 * mit `false` zurückgeben — was dazu führt, dass die ersten Beeper-Notifications
 * silent gedropt werden. Daher: bei DB-Miss wird gegen [AppPolicySeed.DEFAULT_PACKAGES]
 * als Hardcoded-Fallback geprüft, und der `false`-Wert nur dann gecached,
 * wenn die DB-Row explizit existiert.
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

        // DB-Miss: nutze Hardcoded-Default-Whitelist als Fallback.
        // NICHT cachen — sobald Seed läuft, soll die DB-Quelle übernehmen.
        return packageName in AppPolicySeed.DEFAULT_PACKAGES
    }

    fun invalidateCache(packageName: String? = null) {
        if (packageName == null) cache.clear() else cache.remove(packageName)
    }
}
