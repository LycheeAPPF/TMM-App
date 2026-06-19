package io.github.lycheeappf.tmm.channel.llm

import io.github.lycheeappf.tmm.channel.llm.provider.LlmProvider
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProviderError
import io.github.lycheeappf.tmm.channel.llm.provider.LlmRequest
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ergebnis eines einmaligen, rein lokalen xAI-Key-Pings. Android-frei (keine `R`-,
 * keine Context-Referenz) — die lokalisierte UI-Fassung wird erst an der UI-Grenze
 * aufgelöst (`keyTestResultUi` im Assistant-Screen).
 */
enum class KeyTestOutcome {
    VALID, AUTH_ERROR, NO_NETWORK, TIMEOUT, RATE_LIMITED, SERVER_ERROR, MISSING_KEY, UNKNOWN
}

/**
 * Validiert den gespeicherten xAI-Key **rein lokal** (kein Tesla/Bluetooth/SMS):
 * schickt einen minimalen „ping" an die xAI-API über [LlmProvider.complete] und mappt
 * das Ergebnis auf ein [KeyTestOutcome]. Umgeht bewusst
 * `LlmTurnRunner`/`LlmRateLimiter`/`LlmConversationStore` — keine Conversation-State-
 * Berührung, kein Mapping, kein SMS.
 *
 * Gepingt wird gegen das feste [AssistantPreferencesStore.DEFAULT_MODEL], NICHT gegen
 * das vom User konfigurierte Modell: so verfälscht ein ungültiger/eigener Modellname
 * das Ergebnis nicht — der Test beantwortet sauber „ist der **Key** gültig", nicht
 * „passt die Gesamt-Config". [LlmProvider.complete] erledigt Online-Check, Key-Read,
 * Bearer-Auth und das HTTP-Status→[LlmProviderError]-Mapping.
 */
@Singleton
class GrokKeyTester @Inject constructor(
    private val provider: LlmProvider
) {

    suspend fun run(): KeyTestOutcome {
        val request = LlmRequest(
            model = AssistantPreferencesStore.DEFAULT_MODEL,
            systemPrompt = null,
            history = emptyList(),
            userMessage = "ping",
            tools = emptyList(),
            // 16 statt 1: vermeidet API-Minimum-/Leer-Output-Edgecases, bleibt trivial billig.
            maxTokens = 16,
            temperature = 0f
        )
        val response = try {
            // Harte Obergrenze, statt am 60s-OkHttp-Read-Timeout zu hängen; cancelt den
            // laufenden Retrofit-Suspend-Call mit.
            withTimeoutOrNull(TIMEOUT_MS) { provider.complete(request) }
        } catch (e: LlmProviderError) {
            return e.toOutcome()
        }
        return if (response == null) KeyTestOutcome.TIMEOUT else KeyTestOutcome.VALID
    }

    private fun LlmProviderError.toOutcome(): KeyTestOutcome = when (this) {
        is LlmProviderError.Auth -> KeyTestOutcome.AUTH_ERROR
        is LlmProviderError.MissingKey -> KeyTestOutcome.MISSING_KEY
        is LlmProviderError.NoNetwork -> KeyTestOutcome.NO_NETWORK
        is LlmProviderError.RateLimit -> KeyTestOutcome.RATE_LIMITED
        is LlmProviderError.Network ->
            if (cause is SocketTimeoutException) KeyTestOutcome.TIMEOUT else KeyTestOutcome.NO_NETWORK
        is LlmProviderError.Server -> KeyTestOutcome.SERVER_ERROR
        is LlmProviderError.Parse -> KeyTestOutcome.SERVER_ERROR
    }

    companion object {
        /** Bounded-Timeout für den Ping (vom Unit-Test referenzierbar → `internal`). */
        internal const val TIMEOUT_MS = 20_000L
    }
}
