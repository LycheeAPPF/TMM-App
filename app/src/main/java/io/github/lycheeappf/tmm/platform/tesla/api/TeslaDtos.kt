package io.github.lycheeappf.tmm.platform.tesla.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RegionResponse(val response: RegionData? = null, val error: String? = null)

@Serializable
data class RegionData(@SerialName("fleet_api_base_url") val fleetApiBaseUrl: String)

@Serializable
data class NavigationRequestBody(
    val type: String = "share_ext_content_raw",
    val locale: String,
    @SerialName("timestamp_ms") val timestampMs: Long,
    val value: NavigationValue
)

@Serializable
data class NavigationValue(@SerialName("android.intent.extra.TEXT") val text: String)

@Serializable
data class NavigationGpsBody(val lat: Double, val lon: Double, val order: Int = 0)

@Serializable
data class CommandResponse(val response: CommandResult? = null, val error: String? = null)

@Serializable
data class CommandResult(val result: Boolean = false, val reason: String? = null)

@Serializable
data class VehiclesResponse(
    val response: List<VehicleInfo> = emptyList(),
    val count: Int = 0
)

@Serializable
data class VehicleInfo(
    val id: Long = 0L,
    val vin: String = "",
    @SerialName("display_name") val displayName: String = ""
)
