package io.github.lycheeappf.tmm.core.util

import io.github.lycheeappf.tmm.data.db.PayloadJson
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import java.security.MessageDigest

/**
 * Zentrale, testbare Redaktions-Helfer für den Diagnose-Export. Maskiert PII
 * (Kontaktnamen) und hasht potenziell PII-haltige Schlüssel, behält aber alle
 * diagnostisch relevanten Strukturen (Schema-Präfixe, Paketnamen, Längen).
 */
object Redaction {

    /** Kontaktname → "•••(len=N)". Leerstring bleibt leer (kein len=0 leaken). */
    fun maskName(value: String): String =
        if (value.isEmpty()) "" else "•••(len=${value.length})"

    /** Beliebiger String → "sha1:<12 hex>". Stabil, nicht umkehrbar. */
    fun hashKey(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "sha1:${hex.take(12)}"
    }

    /**
     * conversationKey → Schema-Präfix erhalten, PII-Teil gehasht.
     * `com.whatsapp::id::<jid>` → `com.whatsapp::id::sha1:abc123…`.
     * Unbekanntes Format → komplett gehasht.
     */
    fun redactConversationKey(key: String): String {
        for (marker in KEY_MARKERS) {
            val idx = key.indexOf(marker)
            if (idx >= 0) {
                val prefixEnd = idx + marker.length
                return key.substring(0, prefixEnd) + hashKey(key.substring(prefixEnd))
            }
        }
        return hashKey(key)
    }

    /**
     * payloadJson → Notification-Payloads: conversationLabel + senderDisplayName
     * maskiert (Drittkontakt-Namen). Llm/System unverändert (eigene Labels / kein PII).
     */
    fun redactPayloadJson(raw: String): String =
        when (val payload = PayloadJson.decodeOrFallback(raw)) {
            is ChannelPayload.Notification -> PayloadJson.encode(
                payload.copy(
                    conversationLabel = maskName(payload.conversationLabel),
                    senderDisplayName = maskName(payload.senderDisplayName)
                )
            )
            else -> raw
        }

    private val KEY_MARKERS = listOf("::id::", "::lbl::")
}
