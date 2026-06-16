package io.github.lycheeappf.tmm.channel.llm.provider.grok

/**
 * Zentrale Konstanten für die xAI Responses API. Werte hier ändern sich nur,
 * wenn xAI die API umzieht. Modell-Wahl liegt nicht hier, sondern im
 * [io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore].
 */
object GrokConfig {
    const val BASE_URL = "https://api.x.ai/v1/"
    const val ENDPOINT_RESPONSES = "responses"
}
