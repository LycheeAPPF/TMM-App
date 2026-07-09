package io.github.lycheeappf.tmm.platform.location

import android.content.Context
import android.location.Location
import android.location.LocationManager
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * Sichert die drei Verteidigungslinien von [AndroidLocationProvider.lastKnownLocation]:
 * ohne Permission wird der [LocationManager] gar nicht erst angefasst; über alle
 * aktivierten Provider gewinnt der NEUSTE Fix (nicht der erste in einer festen
 * Reihenfolge); und ein Fix, der älter als [AndroidLocationProvider.MAX_FIX_AGE_MS]
 * ist, gilt als abwesend — sonst würde eine tagealte Position Grok als "aktuell"
 * präsentiert. Provider-Exceptions sind best-effort und dürfen den Turn nie kippen.
 */
class AndroidLocationProviderTest {

    private val locationManager = mockk<LocationManager>()
    private val context = mockk<Context> {
        every { getSystemService(LocationManager::class.java) } returns locationManager
    }
    private val permissionGate = mockk<PermissionGate> {
        every { hasLocationAccess() } returns true
    }
    private val now = 10_000_000L
    private val provider = AndroidLocationProvider(context, permissionGate) { now }

    private fun fix(time: Long, lat: Double = 48.0, lon: Double = 11.0, acc: Float = 10f): Location {
        val location = mockk<Location>()
        every { location.time } returns time
        every { location.latitude } returns lat
        every { location.longitude } returns lon
        every { location.accuracy } returns acc
        return location
    }

    @Test
    fun `newest fix wins across enabled providers regardless of order`() {
        every { locationManager.getProviders(true) } returns listOf("gps", "network")
        // GPS zuerst gelistet, aber älter — der frischere Network-Fix muss gewinnen.
        every { locationManager.getLastKnownLocation("gps") } returns
            fix(time = now - 600_000L, lat = 1.0)
        every { locationManager.getLastKnownLocation("network") } returns
            fix(time = now - 60_000L, lat = 2.0, lon = 3.0, acc = 25f)

        val result = provider.lastKnownLocation()

        assertThat(result).isEqualTo(LocationFix(latitude = 2.0, longitude = 3.0, accuracyInMeters = 25f))
    }

    @Test
    fun `fix older than the max age is rejected`() {
        every { locationManager.getProviders(true) } returns listOf("gps")
        every { locationManager.getLastKnownLocation("gps") } returns
            fix(time = now - AndroidLocationProvider.MAX_FIX_AGE_MS - 1L)

        assertThat(provider.lastKnownLocation()).isNull()
    }

    @Test
    fun `fix exactly at the max age is still accepted`() {
        every { locationManager.getProviders(true) } returns listOf("gps")
        every { locationManager.getLastKnownLocation("gps") } returns
            fix(time = now - AndroidLocationProvider.MAX_FIX_AGE_MS)

        assertThat(provider.lastKnownLocation()).isNotNull()
    }

    @Test
    fun `missing permission returns null without touching the location manager`() {
        every { permissionGate.hasLocationAccess() } returns false

        assertThat(provider.lastKnownLocation()).isNull()
        verify { locationManager wasNot Called }
    }

    @Test
    fun `provider exception is swallowed and the remaining providers are still queried`() {
        every { locationManager.getProviders(true) } returns listOf("gps", "fused")
        every { locationManager.getLastKnownLocation("gps") } throws SecurityException("revoked mid-flight")
        every { locationManager.getLastKnownLocation("fused") } returns fix(time = now - 1_000L)

        assertThat(provider.lastKnownLocation()).isNotNull()
    }

    @Test
    fun `no cached fix on any provider returns null`() {
        every { locationManager.getProviders(true) } returns listOf("gps", "network")
        every { locationManager.getLastKnownLocation(any()) } returns null

        assertThat(provider.lastKnownLocation()).isNull()
    }
}
