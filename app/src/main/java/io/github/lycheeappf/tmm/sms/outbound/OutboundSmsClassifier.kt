package io.github.lycheeappf.tmm.sms.outbound

import io.github.lycheeappf.tmm.core.model.FakeAddress
import io.github.lycheeappf.tmm.domain.repository.MappingRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entscheidet, ob eine Outbox-Row ein Tesla-Reply an unsere Fake-Adresse ist
 * oder eine echte SMS, die der User (oder eine andere App via Google Messages)
 * abgesendet hat.
 *
 * Resolution-Reihenfolge:
 *  1. **DB-Lookup** über [MappingRepository.findByFakeAddress] — der primäre
 *     Pfad. `mapping.fakeAddress` enthält bei neuen Mappings den Display-
 *     Namen (z.B. `"Grok"`, `"Anna · WhatsApp"`), den das Tesla in der
 *     Outbox 1:1 wieder zurückschreibt.
 *  2. **`FakeAddress.parse`-Fallback** für Legacy-Mappings, die noch das
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

        // Primary: DB-Lookup (exakter Match auf ADDRESS).
        mappingRepository.findByFakeAddress(row.address)?.let { mapping ->
            return Classification.TeslaReply(mapping.mappingId, mapping.channel.code)
        }

        // Fallback: `+9994x...`-Schema (Hybrid-Form extracted oder pure Numeric).
        FakeAddress.parse(row.address)?.let { parsed ->
            return Classification.TeslaReply(parsed.mappingId, parsed.channel.code)
        }
        return Classification.NotOurs
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
