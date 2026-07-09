package io.github.lycheeappf.tmm.platform.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.core.util.Clock
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LocationProvider] implementation backed by [LocationManager.getLastKnownLocation].
 *
 * Queries ALL enabled providers and picks the newest cached fix — a GPS-first order
 * would happily return a days-old GPS fix even though the network provider has a
 * current one. Fixes older than [MAX_FIX_AGE_MS] are rejected entirely: a stale
 * position presented to Grok as "current" is worse than none. Returns null
 * immediately without blocking when no permission is granted or no fresh fix is
 * cached in the OS.
 */
@Singleton
class AndroidLocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionGate: PermissionGate,
    private val clock: Clock
) : LocationProvider {

    @SuppressLint("MissingPermission") // checked at runtime via permissionGate.hasLocationAccess()
    override fun lastKnownLocation(): LocationFix? {
        if (!permissionGate.hasLocationAccess()) return null
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = runCatching { locationManager.getProviders(true) }.getOrNull() ?: return null
        val newest = providers
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
            ?: return null
        if (clock.now() - newest.time > MAX_FIX_AGE_MS) return null
        return LocationFix(newest.latitude, newest.longitude, newest.accuracy)
    }

    companion object {
        /** Cached fixes older than this count as absent — the turn proceeds without a location clause. */
        const val MAX_FIX_AGE_MS: Long = 15 * 60 * 1000L
    }
}
