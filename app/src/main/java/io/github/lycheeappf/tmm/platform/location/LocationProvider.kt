package io.github.lycheeappf.tmm.platform.location

/**
 * Provides the device's current or last known geographical position.
 */
interface LocationProvider {

    /**
     * Returns the last known GPS fix from the OS cache, or null if location
     * permission is not granted or no cached fix is available.
     */
    fun lastKnownLocation(): LocationFix?
}
