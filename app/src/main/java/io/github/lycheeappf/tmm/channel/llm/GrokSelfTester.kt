package io.github.lycheeappf.tmm.channel.llm

import io.github.lycheeappf.tmm.channel.llm.provider.FINISH_REASON_INCOMPLETE
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProvider
import io.github.lycheeappf.tmm.channel.llm.provider.LlmProviderError
import io.github.lycheeappf.tmm.channel.llm.provider.LlmRequest
import io.github.lycheeappf.tmm.channel.llm.tools.ToolCallExecutor
import io.github.lycheeappf.tmm.channel.llm.tools.ToolRegistry
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.github.lycheeappf.tmm.domain.tesla.ActiveVehicleResolver
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
    private val vehicleResolver: ActiveVehicleResolver,
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
        // Multi-Tesla: Standard-Fahrzeug ODER ein verknüpftes Gerät zählt als konfiguriert —
        // nicht `resolve() != null`, sonst meldete ein 2-Auto-Setup ohne Standard „kein Fahrzeug".
        val vinSelected = vehicleResolver.isAnyVehicleConfigured()
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
            xSearch = false,
            // Erzwungener erster Tool-Call: der Selbsttest misst „funktioniert die
            // Pipeline", nicht „entscheidet sich das Modell". Der ToolCallExecutor
            // setzt das Feld auf Folge-Requests zurück.
            toolChoice = TOOL_CHOICE_REQUIRED
        )
        return try {
            withTimeoutOrNull(E2E_TIMEOUT_MS) {
                val initialResponse = provider.complete(request)
                val loop = toolCallExecutor.run(request, initialResponse) { provider.complete(it) }
                val navCalled = loop.steps.any { it.call.name == SelfTestEvaluation.NAV_TOOL_NAME }
                if (!navCalled && loop.finalResponse.finishReason == FINISH_REASON_INCOMPLETE) {
                    // Reasoning hat max_output_tokens aufgebraucht, bevor das Tool dran
                    // war — eigener Befund statt fälschlich „nicht aufgerufen".
                    E2eResult.Truncated
                } else {
                    val answer = loop.finalResponse.content.orEmpty()
                    E2eResult.Completed(
                        nav = SelfTestEvaluation.navCheck(destination, loop.steps),
                        echo = SelfTestEvaluation.positionEcho(fix, systemPrompt.isBlank(), answer),
                        answer = answer
                    )
                }
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

        /**
         * Fest statt User-Setting. 4096 statt 512: grok-4.x sind Reasoning-Modelle,
         * deren Denk-Tokens gegen `max_output_tokens` zählen — 512 war oft schon vom
         * Reasoning aufgebraucht, bevor der function_call emittiert war (Response
         * kam als status=incomplete ohne Call zurück).
         */
        internal const val E2E_MAX_TOKENS = 4096

        /** Responses-API string-Form: das Modell MUSS im ersten Call ein Tool rufen. */
        internal const val TOOL_CHOICE_REQUIRED = "required"

        /**
         * Model-facing, bewusst englisch (wie die Tool-Descriptions). Als Fahrer-
         * Navigationsanfrage geframt, damit die Trigger-Klauseln von Tool-Description
         * und System-Prompt („when the driver asks to navigate…") greifen, statt mit
         * einem „automated self-test"-Framing zu kollidieren (das las sich für das
         * Modell wie eine Injection und unterdrückte den Call intermittierend).
         */
        internal fun testPrompt(destination: String): String =
            "Navigate to '$destination'. To do this, call the tesla_navigate tool now, passing " +
                "the destination exactly as written above — do not reformat it and do not search the web. " +
                "Only after the tool has returned, reply with one short line: if your context contains " +
                "the user's GPS position, repeat its coordinates; otherwise write exactly NO POSITION. " +
                "(The driver started this navigation check from the app's settings screen.)"
    }
}
