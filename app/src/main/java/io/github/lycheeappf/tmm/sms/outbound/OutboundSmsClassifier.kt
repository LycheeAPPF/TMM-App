package io.github.lycheeappf.tmm.sms.outbound

import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.core.model.FakeAddress
import io.github.lycheeappf.tmm.domain.channel.AssistantIdentity
import io.github.lycheeappf.tmm.domain.repository.MappingRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entscheidet, ob eine Outbox-Row ein Tesla-Reply an unsere Fake-Adresse ist
 * oder eine echte SMS, die der User (oder eine andere App via Google Messages)
 * abgesendet hat.
 *
 * Resolution-Reihenfolge:
 *  1. **Sprach-Alias-Redirect** — die Adresse des zusätzlichen Sprach-Ansprech-
 *     Kontakts ([AssistantIdentity.VOICE_ALIAS_FAKE_ADDRESS]) wird VOR allen
 *     DB-/Parse-Lookups auf die kanonische Grok-Session
 *     ([AssistantIdentity.RESERVED_MAPPING_ID]) umgelenkt. Der Alias hat KEINE
 *     eigene DB-Row; ein späterer `FakeAddress.parse` würde ihn sonst auf ein
 *     Phantom-Mapping (id 1) auflösen → „Konversation abgelaufen".
 *  2. **DB-Lookup** über [MappingRepository.findByFakeAddress] — der primäre
 *     Pfad. `mapping.fakeAddress` enthält bei neuen Mappings den Display-
 *     Namen (z.B. `"Grok"`, `"Anna · WhatsApp"`), den das Tesla in der
 *     Outbox 1:1 wieder zurückschreibt.
 *  3. **`FakeAddress.parse`-Fallback** für Legacy-Mappings, die noch das
 *     numerische `+99942x...`-Schema in `fakeAddress` haben (vor der
 *     Migration zu Display-Adresses).
 *
 * Nicht-unsere Adressen (echte SMS-Sends durch Google Messages) durchlaufen
 * beide Lookups erfolglos und werden als `NotOurs` klassifiziert.
 */
@Singleton
class OutboundSmsClassifier @Inject constructor(
    private val mappingRepository: MappingRepository
) {

    suspend fun classify(row: OutboundSmsRow): Classification {
        if (row.address.isBlank()) return Classification.NotOurs

        // Sprach-Ansprech-Kontakt (z.B. „Elon Musk"): exakte Alias-Adresse → auf die
        // KANONISCHE Grok-Session (id 0) umlenken. Muss VOR findByFakeAddress und dem
        // FakeAddress.parse-Fallback stehen: der Alias hat keine DB-Row, parse würde
        // ihn sonst auf ein Phantom-Mapping (id 1) auflösen → „Konversation abgelaufen".
        // Durch das Umlenken auf id 0 läuft der Turn auf der Grok-Session UND die
        // Antwort wird aus +88810000000 / „Grok" injiziert.
        if (normalizeAddress(row.address) == AssistantIdentity.VOICE_ALIAS_FAKE_ADDRESS) {
            return Classification.TeslaReply(
                mappingId = AssistantIdentity.RESERVED_MAPPING_ID,
                channelCode = ChannelId.LLM.code
            )
        }

        // Primary: DB-Lookup (exakter Match auf ADDRESS).
        mappingRepository.findByFakeAddress(row.address)?.let { mapping ->
            return Classification.TeslaReply(mapping.mappingId, mapping.channel.code)
        }

        // Fallback: `+9994x...`-Schema (aus Bracket-Form extrahiert oder pure Numeric).
        FakeAddress.parse(row.address)?.let { parsed ->
            return Classification.TeslaReply(parsed.mappingId, parsed.channel.code)
        }
        return Classification.NotOurs
    }

    /**
     * Normalisiert eine ADDRESS-Spalte auf reine `+E.164`-Form (analog
     * [FakeAddress.parse]): entfernt alles außer `+`/Ziffern und wandelt ein
     * führendes `00` in `+`. Toleriert damit Bracket-Form (`"Elon Musk <+888…>"`)
     * und `00`-Präfix beim Alias-Match.
     */
    private fun normalizeAddress(raw: String): String {
        var clean = raw.replace(Regex("[^+0-9]"), "")
        if (clean.startsWith("00")) clean = "+" + clean.substring(2)
        return clean
    }

    sealed class Classification {
        data object NotOurs : Classification()
        data class TeslaReply(val mappingId: Long, val channelCode: Int) : Classification()
    }
}

data class OutboundSmsRow(
    val id: Long,
    val address: String,
    val body: String,
    val type: Int,
    val date: Long
)
