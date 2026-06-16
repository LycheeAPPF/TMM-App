package io.github.lycheeappf.tmm.data.repository

import io.github.lycheeappf.tmm.data.db.AppPolicyDao
import io.github.lycheeappf.tmm.data.db.AppPolicyEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pre-populates die AppPolicy-Tabelle beim ersten Start: Beeper-Varianten
 * werden by-default whitelisted. Wird beim App-Start (von [MfsApplication] aus)
 * aufgerufen — idempotent über OnConflictStrategy.REPLACE.
 *
 * Invalidiert nach dem Seed den `RoomAppPolicyProvider`-Cache, sodass andere
 * Komponenten die frischen DB-Werte sehen.
 */
@Singleton
class AppPolicySeed @Inject constructor(
    private val dao: AppPolicyDao,
    private val cache: RoomAppPolicyProvider
) {
    suspend fun seedIfEmpty() {
        var seeded = false
        DEFAULT_PACKAGES.forEach { pkg ->
            if (dao.get(pkg) == null) {
                dao.upsert(
                    AppPolicyEntity(
                        packageName = pkg,
                        whitelisted = true,
                        customDisplayName = null,
                        lastSeenRemoteInput = false,
                        lastSeenAt = 0L
                    )
                )
                seeded = true
            }
        }
        if (seeded) cache.invalidateCache()
    }

    companion object {
        val DEFAULT_PACKAGES = setOf(
            "com.beeper.android",
            "com.beeper.inc.android"
        )
    }
}
