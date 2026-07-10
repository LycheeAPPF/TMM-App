package io.github.lycheeappf.tmm.channel.llm

import io.github.lycheeappf.tmm.channel.llm.provider.LlmProvider
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProviderError
import io.github.lycheeappf.tmm.channel.llm.provider.LlmRequest
import io.github.lycheeappf.tmm.channel.llm.tools.ToolCallExecutor
import io.github.lycheeappf.tmm.channel.llm.tools.ToolRegistry
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.github.lycheeappf.tmm.data.store.TeslaTokenStore
import io.github.lycheeappf.tmm.platform.location.LocationProvider
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import io.github.lycheeappf.tmm.platform.tesla.auth.TeslaAuthManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mehrstufiger Grok-Selbsttest (Assistant-Screen): 1. Key-Ping, 2. lokaler
 * Positions-Ketten-Check, 3. lokaler Tesla-Konto-Check, 4. echter End-to-end-Turn
 * (Grok ruft `tesla_navigate` → Fleet API; Koordinaten-Echo-Prüfung).
 *
 * Wie [GrokKeyTester]: keine eigenen R-/Context-Referenzen (Lokalisierung an der
 * UI-Grenze), bewusst am [LlmTurnRunner]/Rate-Limiter/[LlmConversationStore] vorbei —
 * kein Conversation-State, kein Rate-Limit-Verbrauch, kein SMS/Mapping. Der
 * E2E-Turn ist consent-gated (Turn-Zeit-Parität zu [LlmChannel]) und deterministisch
 * (temperature=0, ohne Web-/X-Suche, leere History, feste maxTokens).
 *
 * PII: in den [LogBuffer] gehen nur Stufen-Outcomes/Typnamen/Längen — nie Prompt,
 * Antworttext, Koordinaten oder Zieladresse.
 */
@Singleton
class GrokSelfTester @Inject constructor(
    private val keyTester: GrokKeyTester,
    private val provider: LlmProvider,
    private val prefs: AssistantPreferencesStore,
    private val toolRegistry: ToolRegistry,
    private val toolCallExecutor: ToolCallExecutor,
    private val locationProvider: LocationProvider,
    private val permissionGate: PermissionGate,
    private val teslaAuthManager: TeslaAuthManager,
    private val teslaTokenStore: TeslaTokenStore,
    private val logBuffer: LogBuffer
) {

    fun run(destination: String): Flow<SelfTestEvent> = flow {
        emit(SelfTestEvent.StageRunning(SelfTestStage.KEY))
        val key = keyTester.run()
        emit(SelfTestEvent.KeyResult(key))
        logBuffer.info(TAG, "Self-test key: $key")
        if (key != KeyTestOutcome.VALID) {
            emit(SelfTestEvent.StageSkipped(SelfTestStage.POSITION))
            emit(SelfTestEvent.StageSkipped(SelfTestStage.TESLA_LOCAL))
            emit(SelfTestEvent.StageSkipped(SelfTestStage.E2E))
            return@flow
        }

        emit(SelfTestEvent.StageRunning(SelfTestStage.POSITION))
        val position = positionStage()
        emit(SelfTestEvent.PositionResult(position))
        logBuffer.info(TAG, "Self-test position: ${position::class.simpleName}")

        emit(SelfTestEvent.StageRunning(SelfTestStage.TESLA_LOCAL))
        val credentialsSet = teslaAuthManager.hasCredentials()
        val vinSelected = teslaTokenStore.readSelectedVin() != null
        emit(SelfTestEvent.TeslaLocalResult(credentialsSet, vinSelected))
        logBuffer.info(TAG, "Self-test tesla: credentials=$credentialsSet vin=$vinSelected")

        emit(SelfTestEvent.StageRunning(SelfTestStage.E2E))
        val e2e = e2eStage(destination, position)
        emit(SelfTestEvent.E2eDone(e2e))
        logBuffer.info(
            TAG,
            "Self-test e2e: ${e2e::class.simpleName}" +
                ((e2e as? E2eResult.Completed)?.let {
                    " (nav=${it.nav::class.simpleName}, echo=${it.echo}, answer len=${it.answer.length})"
                } ?: "")
        )
    }

    /** Erstes fehlendes Glied gewinnt: Opt-in → Permission → frischer Fix. */
    private suspend fun positionStage(): PositionLocalResult = when {
        !prefs.locationContextEnabled() -> PositionLocalResult.Disabled
        !permissionGate.hasLocationAccess() -> PositionLocalResult.NoPermission
        else -> locationProvider.lastKnownLocation()?.let { fix ->
            PositionLocalResult.Ok(fix, backgroundGranted = permissionGate.hasBackgroundLocationAccess())
        } ?: PositionLocalResult.NoFix
    }

    private suspend fun e2eStage(destination: String, position: PositionLocalResult): E2eResult {
        // Consent-Gate wie der Produktions-Turn (LlmChannel prüft zur Turn-Zeit):
        // ohne Zustimmung KEIN xAI-Call — sonst würde der Test Nutzerdaten
        // (Zieladresse, ggf. Koordinaten) ohne Consent senden und einen Zustand
        // grün melden, den das Auto ablehnen würde.
        if (!prefs.isPrivacyConsentGiven()) return E2eResult.ConsentMissing

        val fix = (position as? PositionLocalResult.Ok)?.fix
        val systemPrompt = prefs.systemPrompt(webSearch = false, xSearch = false, location = fix)
        val request = LlmRequest(
            model = prefs.model(),
            systemPrompt = systemPrompt,
            history = emptyList(),
            userMessage = testPrompt(destination),
            tools = toolRegistry.activeSchemas(),
            maxTokens = E2E_MAX_TOKENS,
            temperature = 0f,
            webSearch = false,
            xSearch = false
        )
        return try {
            withTimeoutOrNull(E2E_TIMEOUT_MS) {
                val initialResponse = provider.complete(request)
                val loop = toolCallExecutor.run(request, initialResponse) { provider.complete(it) }
                val answer = loop.finalResponse.content.orEmpty()
                E2eResult.Completed(
                    nav = SelfTestEvaluation.navCheck(destination, loop.steps),
                    echo = SelfTestEvaluation.positionEcho(fix, systemPrompt.isBlank(), answer),
                    answer = answer
                )
            } ?: E2eResult.Timeout
        } catch (e: LlmProviderError) {
            E2eResult.ProviderFailed(e.toKeyTestOutcome())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            logBuffer.error(TAG, "Self-test e2e unexpected: ${e::class.simpleName}")
            E2eResult.ProviderFailed(KeyTestOutcome.UNKNOWN)
        }
    }

    companion object {
        private const val TAG = "GrokSelfTester"

        /** Hartes Gesamt-Cap des E2E-Turns — der Fleet-Pfad kann Wake-up + 15 s Delay + Retry enthalten. */
        internal const val E2E_TIMEOUT_MS = 120_000L

        /** Fest statt User-Setting: ein zu kleines User-maxTokens würde das Koordinaten-Echo abschneiden. */
        internal const val E2E_MAX_TOKENS = 512

        /** Model-facing, bewusst englisch (wie Tool-Descriptions). */
        internal fun testPrompt(destination: String): String =
            "This is an automated integration self-test of the app. Do exactly two things: " +
                "(1) Call the tesla_navigate tool with the destination '$destination' passed exactly " +
                "as written — do not reformat it and do not search the web. " +
                "(2) After the tool call, reply with one short line: if your context contains the " +
                "user's GPS position, repeat its coordinates; otherwise write exactly NO POSITION."
    }
}
