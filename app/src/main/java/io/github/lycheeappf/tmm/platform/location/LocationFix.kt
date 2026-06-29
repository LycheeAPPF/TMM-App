package io.github.lycheeappf.tmm.platform.location

/**
 * A GPS position fix returned by [ILocationProvider].
 *
 * @property latitude Latitude in decimal degrees (positive = North, negative = South).
 * @property longitude Longitude in decimal degrees (positive = East, negative = West).
 * @property accuracyInMeters Estimated horizontal accuracy radius in metres.
 */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyInMeters: Float
)
