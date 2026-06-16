package io.github.lycheeappf.tmm.channel.llm.provider.grok

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Retrofit-Interface für die xAI Responses API. Authorization wird pro Call
 * über einen @Header-Parameter gesetzt, damit wir den Key zur Aufrufzeit aus
 * dem [ApiKeyStore] lesen können (statt einen Interceptor mit captured key
 * zu verdrahten).
 */
interface GrokApi {

    @POST(GrokConfig.ENDPOINT_RESPONSES)
    suspend fun responses(
        @Header("Authorization") authorization: String,
        @Body body: ResponsesRequest
    ): Response<ResponsesResponse>
}
