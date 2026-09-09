package io.github.lycheeappf.tmm.channel.llm.tools.tesla

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.channel.llm.tools.AssistantTool
import io.github.lycheeappf.tmm.channel.llm.tools.ToolInvocationResult
import io.github.lycheeappf.tmm.channel.llm.tools.ToolSchema
import io.github.lycheeappf.tmm.core.locale.localizedString
import io.github.lycheeappf.tmm.domain.tesla.ActiveVehicleResolver
import io.github.lycheeappf.tmm.platform.tesla.api.TeslaCommandError
import io.github.lycheeappf.tmm.platform.tesla.api.TeslaVehicleCommandClient
import io.github.lycheeappf.tmm.platform.tesla.api.userMessage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Grok-Tool: sendet ein Navigationsziel an das Tesla-Fahrzeug per Fleet API.
 *
 * Grok ruft dieses Tool auf, wenn der Fahrer einen Navigationsbefehl diktiert
 * (z.B. "Navigiere mich zur nächsten Apotheke"). Das Tool leitet den Zielort
 * an [TeslaVehicleCommandClient] weiter, der die Fleet-API-Call ausführt.
 *
 * Multi-Tesla: das Ziel-Fahrzeug liefert der [ActiveVehicleResolver] — das per
 * Bluetooth verbundene, verknüpfte Auto, sonst das Standard-Fahrzeug.
 *
 * Bei vagen Zielen sucht Grok via Websuche die konkrete Adresse, bevor es das Tool aufruft.
 */
@Singleton
class TeslaNavigateTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val commandClient: TeslaVehicleCommandClient,
    private val vehicleResolver: ActiveVehicleResolver
) : AssistantTool {

    override val schema = ToolSchema(
        name = "tesla_navigate",
        description = "Sends a navigation destination to the driver's Tesla vehicle via the Fleet API. " +
            "The app picks the Tesla the phone is currently connected to via Bluetooth and falls back " +
            "to the default vehicle selected in the app settings. " +
            "Call this when the driver asks to navigate somewhere, find a route, or go to a place, " +
            "or when you are explicitly instructed to call this tool (for example an app integration check). " +
            "Always pass a specific, concrete address or place name — never a vague query. " +
            "For vague destinations (e.g. 'nearest pharmacy', 'Italian restaurant nearby'): " +
            "use web search first to find the actual address near the driver, " +
            "then call this tool with the specific address found. " +
            "Requires Tesla account login in the app settings. " +
            "If it fails because no vehicle or credentials are configured, tell the driver to connect their Tesla account in the app settings.",
        parametersJson = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("address") {
                    put("type", "string")
                    put("description", "Full address or place name to navigate to (e.g. 'Alexanderplatz Berlin')")
                }
            }
            putJsonArray("required") { add("address") }
        }
    )

    override suspend fun invoke(arguments: JsonObject): ToolInvocationResult {
        val vehicle = vehicleResolver.resolve()
            ?: return ToolInvocationResult.Failure(
                context.localizedString(R.string.tesla_error_no_vehicle_configured)
            )

        val address = arguments["address"]?.jsonPrimitive?.content.orEmpty()
        if (address.isBlank()) {
            return ToolInvocationResult.Failure(context.localizedString(R.string.tesla_error_no_destination))
        }

        return try {
            commandClient.navigate(vehicle, address)
            // Ziel-Echo im Tool-Result, damit das Modell die gestartete Navigation in
            // seiner Bestätigung benennen kann. Rohwert VOR dem Encoden kürzen —
            // Escaping (Quotes/Backslashes/Newlines) übernimmt kotlinx.serialization.
            val result = buildJsonObject {
                put("status", "ok")
                put("destination", address.take(MAX_DESTINATION_ECHO_CHARS))
            }
            ToolInvocationResult.Success(result.toString())
        } catch (e: TeslaCommandError) {
            ToolInvocationResult.Failure(e.userMessage(context))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ToolInvocationResult.Failure(context.localizedString(R.string.tesla_error_network))
        }
    }

    companion object {
        /** Max. Zeichen des Ziel-Echos im Tool-Result (nicht des Fleet-API-Calls). */
        private const val MAX_DESTINATION_ECHO_CHARS = 200
    }
}
