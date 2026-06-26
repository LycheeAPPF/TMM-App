package io.github.lycheeappf.tmm.channel.llm

import io.github.lycheeappf.tmm.channel.llm.provider.LlmProvider
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProviderError
import io.github.lycheeappf.tmm.channel.llm.provider.LlmRequest
import io.github.lycheeappf.tmm.channel.llm.provider.LlmTurn
import io.github.lycheeappf.tmm.channel.llm.provider.TokenUsage
import io.github.lycheeappf.tmm.channel.llm.tools.ToolRegistry
import io.github.lycheeappf.tmm.core.util.Clock
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.github.lycheeappf.tmm.platform.location.ILocationProvider
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zentrale "Ein Turn"-Logik. Alles, was zu einem User-Diktat passieren muss,
 * läuft hier unter **einem** Per-Session-Mutex:
 *
 *   1. TTL-Expire-Check
 *   2. Rate-Limit-Check
 *   3. Snapshot der bisherigen History (vor Provider-Call)
 *   4. Provider-Call (Network)
 *   5. Response → Formatter
 *   6. Nur bei Erfolg: User-Turn + Assistant-Turn anhängen
 *
 * Damit kann es nicht passieren, dass zwei parallel ankommende Tesla-Replies
 * ihre History gegenseitig zerschießen — der zweite wartet, bis der erste
 * sauber fertig ist. Und bei Netzwerk-Failure bleibt die History
 * konsistent: der User-Turn wird nicht persistiert.
 */
@Singleton
class LlmTurnRunner @Inject constructor(
    private val store: LlmConversationStore,
    private val provider: LlmProvider,
    private val prefs: AssistantPreferencesStore,
    private val rateLimiter: LlmRateLimiter,
    private val formatter: LlmResponseFormatter,
    private val toolRegistry: ToolRegistry,
    private val locationProvider: ILocationProvider,
    private val logBuffer: LogBuffer,
    private val clock: Clock
) {

    sealed class TurnResult {
        data class Success(val assistantText: String, val usage: TokenUsage?) : TurnResult()
        /** Rate-Limit; [reason] wird im [LlmChannel] zu lokalisiertem TTS-Text aufgelöst. */
        data class RateLimited(val reason: LlmRateLimiter.Reason) : TurnResult()
        data class ProviderFailed(val error: LlmProviderError) : TurnResult()
        data object EmptyResponse : TurnResult()
    }

    suspend fun run(mappingId: Long, userText: String): TurnResult {
        // Session-Snapshot vor dem Lock holen. Innerhalb des Locks arbeiten wir
        // ausschließlich auf dieser Instanz — selbst wenn `LlmStarter` parallel
        // `store.reset(mappingId)` aufruft, bleiben wir auf der gelockten
        // History konsistent (die neue Session ist dann ungebunden zu diesem Turn).
        val session = store.sessionFor(mappingId)
        return session.mutex.withLock {
            store.expireIfStale(session)

            when (val decision = rateLimiter.checkAndAcquire(mappingId)) {
                LlmRateLimiter.Decision.Allow -> {}
                is LlmRateLimiter.Decision.Reject -> {
                    logBuffer.warn(TAG, "Rate-limited (${decision.reason})")
                    return@withLock TurnResult.RateLimited(decision.reason)
                }
            }

            val model = prefs.model()
            val historyBefore = store.snapshot(session)
            // Such-Flags EINMAL lesen, damit System-Prompt-Klausel und Request-Tools
            // garantiert konsistent sind (ein Toggle-Flip zwischen zwei Reads könnte
            // sonst "du kannst suchen" sagen, ohne die Tools mitzuschicken — oder umgekehrt).
            val webSearch = prefs.webSearchEnabled()
            val xSearch = prefs.xSearchEnabled()
            val location = locationProvider.lastKnownLocation()
            val req = LlmRequest(
                model = model,
                systemPrompt = prefs.systemPrompt(webSearch, xSearch, location),
                history = historyBefore,
                userMessage = userText,
                tools = toolRegistry.activeSchemas(),
                maxTokens = prefs.maxTokens(),
                temperature = prefs.temperature(),
                webSearch = webSearch,
                xSearch = xSearch
            )
            val response = try {
                provider.complete(req)
            } catch (e: LlmProviderError) {
                // Nur Typ-Name loggen — Provider-Error-Messages können Body-Fragmente
                // (= ggf. Diktatinhalt) enthalten und gehören nicht in den LogBuffer.
                logBuffer.warn(TAG, "Provider error: ${e::class.simpleName}")
                // Transientes Failure → Rate-Limit-Slot refunden, sodass flakiges Netz
                // nicht das User-Limit aufbraucht.
                rateLimiter.refund(mappingId)
                return@withLock TurnResult.ProviderFailed(e)
            } catch (e: Exception) {
                // CancellationException NIE in ProviderFailed wrappen — sonst bricht
                // structured concurrency und der Lifecycle (z.B. App-Stop) wird vom
                // Channel als "Grok-Server-Fehler" an den User vorgelesen.
                if (e is kotlinx.coroutines.CancellationException) throw e
                logBuffer.error(TAG, "Unexpected provider exception: ${e::class.simpleName}")
                rateLimiter.refund(mappingId)
                return@withLock TurnResult.ProviderFailed(LlmProviderError.Network(e))
            }

            val cleaned = formatter.format(response.content.orEmpty())
            if (cleaned.isBlank()) {
                logBuffer.warn(TAG, "Empty response from provider (resp=${response.responseId})")
                rateLimiter.refund(mappingId)
                return@withLock TurnResult.EmptyResponse
            }

            // Persist nur jetzt — bei Provider-Fehler oder Empty bleibt history sauber.
            store.append(session, LlmTurn("user", userText, clock.now()))
            store.append(session, LlmTurn("assistant", cleaned, clock.now()))
            logBuffer.info(
                TAG,
                "LLM-Turn OK (mapping=$mappingId, model=$model, " +
                    "in=${response.usage?.inputTokens ?: 0}, out=${response.usage?.outputTokens ?: 0})"
            )
            TurnResult.Success(assistantText = cleaned, usage = response.usage)
        }
    }

    companion object {
        private const val TAG = "LlmTurnRunner"
    }
}
