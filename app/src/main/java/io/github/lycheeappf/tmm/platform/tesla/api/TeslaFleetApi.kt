package io.github.lycheeappf.tmm.platform.tesla.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Retrofit-Interface für die Tesla Fleet API.
 *
 * Alle Calls nutzen `@Url` (vollständige URL), da die regionale Base-URL
 * erst nach dem ersten `/api/1/users/region`-Call bekannt ist und sich nicht
 * zur Konfigurationszeit fixieren lässt.
 */
interface TeslaFleetApi {

    @GET
    suspend fun region(
        @Url url: String,
        @Header("Authorization") auth: String
    ): Response<RegionResponse>

    @GET
    suspend fun vehicles(
        @Url url: String,
        @Header("Authorization") auth: String
    ): Response<VehiclesResponse>

    @POST
    suspend fun navigationRequest(
        @Url url: String,
        @Header("Authorization") auth: String,
        @Body body: NavigationRequestBody
    ): Response<CommandResponse>

    @POST
    suspend fun navigationGps(
        @Url url: String,
        @Header("Authorization") auth: String,
        @Body body: NavigationGpsBody
    ): Response<CommandResponse>
}
