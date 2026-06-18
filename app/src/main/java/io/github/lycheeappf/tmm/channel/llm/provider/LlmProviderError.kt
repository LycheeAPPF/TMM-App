package io.github.lycheeappf.tmm.channel.llm.provider

/**
 * Sealed Exception-Hierarchie für Provider-Failures. Sealed Class statt
 * Sealed Interface, damit wir [Exception] erweitern können — der Code drumherum
 * fängt das in den meisten Fällen als [LlmProviderError] und differenziert
 * dann via `when`.
 *
 * Bleibt bewusst Android-frei (kein Context, keine `R`-Referenz): die vom Tesla-
 * TTS vorlesbare, lokalisierte Fassung wird erst an der UI-/Channel-Grenze
 * aufgelöst (siehe `LlmChannel.providerErrorMessage`). Die `message`-Strings hier
 * sind nur Debug-/Exception-Text (intern, gehen nicht an den User).
 */
sealed class LlmProviderError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Gerät hat keine validierte Internet-Verbindung. Pre-Flight-Check fail. */
    data object NoNetwork : LlmProviderError("Keine Internetverbindung")

    /** API-Key fehlt oder ist leer. */
    class MissingKey : LlmProviderError("Kein API-Key konfiguriert")

    /** HTTP 401/403 — API-Key abgelehnt. */
    class Auth(detail: String) : LlmProviderError("API-Key ungültig: $detail")

    /** HTTP 429 — Rate-Limit von xAI selbst. */
    class RateLimit(val retryAfterSec: Int?) :
        LlmProviderError("xAI Rate-Limit erreicht" + (retryAfterSec?.let { " (retry in ${it}s)" }.orEmpty()))

    /** HTTP 5xx — Server-Side-Problem. */
    class Server(val code: Int, val body: String?) :
        LlmProviderError("xAI Server-Fehler $code")

    /** Netzwerk-IO (DNS, TLS, Timeout). */
    class Network(cause: Throwable) :
        LlmProviderError("Netzwerkfehler: ${cause.message ?: cause::class.simpleName}", cause)

    /** Response konnte nicht geparsed werden. */
    class Parse(detail: String) : LlmProviderError("Antwort nicht lesbar: $detail")
}
