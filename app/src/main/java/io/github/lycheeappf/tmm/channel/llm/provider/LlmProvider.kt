package io.github.lycheeappf.tmm.channel.llm.provider

/**
 * Provider-agnostische LLM-Schnittstelle. V2 hat genau eine Implementation:
 * [io.github.lycheeappf.tmm.channel.llm.provider.grok.GrokProvider]. V3 kann
 * additiv Claude/OpenAI/Ollama-Provider ergänzen — das `MessagingChannel`-
 * Interface dahinter bleibt unverändert.
 */
interface LlmProvider {

    /**
     * Führt einen Multi-Turn-Call aus.
     *
     * @throws LlmProviderError bei jedem erwartbaren Failure-Mode.
     */
    suspend fun complete(req: LlmRequest): LlmResponse
}
