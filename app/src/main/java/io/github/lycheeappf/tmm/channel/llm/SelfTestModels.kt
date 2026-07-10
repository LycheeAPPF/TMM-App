package io.github.lycheeappf.tmm.channel.llm

import io.github.lycheeappf.tmm.platform.location.LocationFix

/**
 * Modelle des Grok-Selbsttests (siehe docs/superpowers/specs/2026-07-10-…-design.md).
 * Bewusst ohne Android-/R-Referenzen — die lokalisierte UI-Fassung entsteht erst an
 * der UI-Grenze im Assistant-Screen.
 */
enum class SelfTestStage { KEY, POSITION, TESLA_LOCAL, E2E }

sealed class SelfTestEvent {
    data class StageRunning(val stage: SelfTestStage) : SelfTestEvent()
    data class KeyResult(val outcome: KeyTestOutcome) : SelfTestEvent()
    data class PositionResult(val result: PositionLocalResult) : SelfTestEvent()
    data class TeslaLocalResult(val credentialsSet: Boolean, val vinSelected: Boolean) : SelfTestEvent()
    data class E2eDone(val result: E2eResult) : SelfTestEvent()
    /** Stufen, die wegen eines früheren Abbruchs (Key ≠ VALID) nicht laufen. */
    data class StageSkipped(val stage: SelfTestStage) : SelfTestEvent()
}

/** Lokaler Positions-Ketten-Check: erstes fehlendes Glied gewinnt. */
sealed class PositionLocalResult {
    data object Disabled : PositionLocalResult()
    data object NoPermission : PositionLocalResult()
    data object NoFix : PositionLocalResult()
    /** [backgroundGranted]=false ⇒ Fix nur im Vordergrund — Warnung, kein Fail. */
    data class Ok(val fix: LocationFix, val backgroundGranted: Boolean) : PositionLocalResult()
}

sealed class NavCheck {
    data object NotCalled : NavCheck()
    /** Tool gerufen, aber Ziel weicht ab — hat Vorrang vor Ok/Failed (falsches Ziel im Auto!). */
    data class WrongAddress(val sent: String) : NavCheck()
    data class CalledOk(val sent: String) : NavCheck()
    /** [error] ist die bereits lokalisierte Meldung aus dem Tool (z. B. TeslaNavigateTool). */
    data class CalledFailed(val sent: String, val error: String) : NavCheck()
}

enum class PositionEcho {
    /** Stufe 2 lieferte keinen Fix → keine Erwartung. */
    SKIPPED,
    /** Fix da, aber System-Prompt bewusst geleert → Klausel wird nie angehängt (Produktionsverhalten). */
    NO_CLAUSE,
    MATCHED,
    NOT_FOUND
}

sealed class E2eResult {
    data class Completed(val nav: NavCheck, val echo: PositionEcho, val answer: String) : E2eResult()
    data class ProviderFailed(val outcome: KeyTestOutcome) : E2eResult()
    /** Privacy-Consent fehlt — es ist KEIN xAI-Call erfolgt. */
    data object ConsentMissing : E2eResult()
    data object Timeout : E2eResult()

    /**
     * Der Turn kam mit `finishReason=incomplete` OHNE Nav-Call zurück: das Token-
     * Budget wurde vom Reasoning aufgebraucht, bevor das Tool dran war — KEIN
     * Modell-„Nein" und kein Pipeline-Defekt. (Truncation NACH erfolgreichem
     * Call bleibt Completed; sichtbar über leere Antwort/Echo-Zeile.)
     */
    data object Truncated : E2eResult()
}
