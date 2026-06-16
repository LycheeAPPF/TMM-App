package io.github.lycheeappf.tmm.channel.llm.provider

/**
 * Sealed Exception-Hierarchie für Provider-Failures. Sealed Class statt
 * Sealed Interface, damit wir [Exception] erweitern können — der Code drumherum
 * fängt das in den meisten Fällen als [LlmProviderError] und differenziert
 * dann via `when`.
 */
sealed class LlmProviderError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * Vom Tesla TTS vorlesbare Fassung dieser Fehlerart — kurz, ohne sensible
     * Detail-Body-Fragmente. `message` (für Debug) kann mehr enthalten.
     */
    abstract val userFacingMessage: String

    /** Gerät hat keine validierte Internet-Verbindung. Pre-Flight-Check fail. */
    data object NoNetwork : LlmProviderError("Keine Internetverbindung") {
        override val userFacingMessage = "kein Internetzugriff."
    }

    /** API-Key fehlt oder ist leer. */
    class MissingKey : LlmProviderError("Kein API-Key konfiguriert") {
        override val userFacingMessage = "kein API-Key konfiguriert."
    }

    /** HTTP 401/403 — API-Key abgelehnt. */
    class Auth(detail: String) : LlmProviderError("API-Key ungültig: $detail") {
        override val userFacingMessage = "der API-Key wurde abgelehnt."
    }

    /** HTTP 429 — Rate-Limit von xAI selbst. */
    class RateLimit(val retryAfterSec: Int?) :
        LlmProviderError("xAI Rate-Limit erreicht" + (retryAfterSec?.let { " (retry in ${it}s)" }.orEmpty())) {
        override val userFacingMessage = "Grok ist zu beschäftigt, bitte gleich nochmal."
    }

    /** HTTP 5xx — Server-Side-Problem. */
    class Server(val code: Int, val body: String?) :
        LlmProviderError("xAI Server-Fehler $code") {
        override val userFacingMessage = "Grok-Server-Fehler ($code)."
    }

    /** Netzwerk-IO (DNS, TLS, Timeout). */
    class Network(cause: Throwable) :
        LlmProviderError("Netzwerkfehler: ${cause.message ?: cause::class.simpleName}", cause) {
        override val userFacingMessage = "die Verbindung zu Grok hat nicht geklappt."
    }

    /** Response konnte nicht geparsed werden. */
    class Parse(detail: String) : LlmProviderError("Antwort nicht lesbar: $detail") {
        override val userFacingMessage = "Grok-Antwort war unverständlich."
    }
}
