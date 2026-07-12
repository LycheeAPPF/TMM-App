package io.github.lycheeappf.tmm.platform.tesla.api

import android.content.Context
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.core.locale.localizedString

/**
 * Nutzergerichtete, lokalisierte Meldung für einen [TeslaCommandError] (EN+DE).
 *
 * Spiegel des `LlmProviderError`-Mappings in `LlmChannel`: die Exception selbst
 * bleibt Context-frei (Client testbar auf purem JVM), die Auflösung in Resource-
 * Strings passiert erst an der Konsumstelle — via [localizedString], damit auch
 * Nicht-Compose-Pfade (Grok-Antwort → SMS-Injektion) die aktive App-Sprache treffen.
 */
fun TeslaCommandError.userMessage(context: Context): String = when (this) {
    is TeslaCommandError.MissingCredentials ->
        context.localizedString(R.string.tesla_error_missing_credentials)
    is TeslaCommandError.Unauthorized ->
        context.localizedString(R.string.tesla_error_unauthorized)
    is TeslaCommandError.VehicleNotFound ->
        context.localizedString(R.string.tesla_error_vehicle_not_found)
    is TeslaCommandError.CommandRejected ->
        context.localizedString(
            R.string.tesla_error_command_rejected,
            reason ?: context.localizedString(R.string.tesla_error_reason_unknown)
        )
    is TeslaCommandError.Network ->
        context.localizedString(R.string.tesla_error_network)
    is TeslaCommandError.RegionDiscoveryFailed ->
        context.localizedString(R.string.tesla_error_region_discovery)
    is TeslaCommandError.Unknown ->
        context.localizedString(R.string.tesla_error_unknown, code)
}
