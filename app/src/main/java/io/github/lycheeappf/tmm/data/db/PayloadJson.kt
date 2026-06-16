package io.github.lycheeappf.tmm.data.db

import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Singleton-Konfiguration für die Serialisierung von [ChannelPayload] in/aus
 * der Room-Spalte `payloadJson` (String).
 *
 * Polymorphe sealed classes werden automatisch mit Type-Discriminator serialisiert
 * (Default-Key `"type"`).
 */
object PayloadJson {
    val format: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "kind"
    }

    fun encode(payload: ChannelPayload): String = format.encodeToString(payload)

    /**
     * Striktes Decode — Exception bei Format-Fehlern. Für Tests und Inline-Pfade,
     * bei denen ein Crash kein Problem ist.
     */
    fun decode(raw: String): ChannelPayload = format.decodeFromString(raw)

    /**
     * Defensives Decode für aus der DB gelesene Payloads. Eine einzelne korrupte
     * Row darf nicht den Backfill-Worker, Diagnostics-Export oder den Dispatcher
     * killen — wir fallen auf einen `System`-Sentinel zurück und loggen.
     */
    fun decodeOrFallback(raw: String): ChannelPayload =
        runCatching { format.decodeFromString<ChannelPayload>(raw) }
            .getOrElse {
                android.util.Log.w("PayloadJson", "decode failed, sentinel used: ${it.message}")
                ChannelPayload.System(reason = "decode_failed")
            }
}
