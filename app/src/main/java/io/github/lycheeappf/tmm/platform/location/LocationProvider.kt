package io.github.lycheeappf.tmm.platform.location

/**
 * Provides the device's current or last known geographical position.
 */
interface LocationProvider {

    /**
     * Returns the freshest fix from the OS cache across all enabled providers, or
     * null if location permission is not granted, no cached fix is available, or
     * the newest fix is too old to be presented as "current".
     */
    fun lastKnownLocation(): LocationFix?
}
