package io.github.lycheeappf.tmm.platform.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ILocationProvider] implementation backed by [LocationManager.getLastKnownLocation].
 *
 * Tries GPS first (most precise), then network, then the system-managed fused provider.
 * Returns null immediately without blocking when no permission is granted or no cached
 * fix is available in the OS.
 */
@Singleton
class LocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionGate: PermissionGate
) : ILocationProvider {

    @SuppressLint("MissingPermission") // checked at runtime via permissionGate.hasLocationAccess()
    override fun lastKnownLocation(): LocationFix? {
        if (!permissionGate.hasLocationAccess()) return null
        val locationManager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.FUSED_PROVIDER
        )
        return providers.firstNotNullOfOrNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }?.let { fix ->
            LocationFix(fix.latitude, fix.longitude, fix.accuracy)
        }
    }
}
