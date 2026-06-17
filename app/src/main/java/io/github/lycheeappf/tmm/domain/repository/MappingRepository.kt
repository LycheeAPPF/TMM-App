package io.github.lycheeappf.tmm.domain.repository

import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.domain.channel.ChannelMapping
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload

/**
 * Repository-Interface für Channel-Mappings. Implementation in Phase 4 (data/repository/).
 *
 * Mapping-IDs sind monoton wachsend pro Channel; reuse erfolgt nur über
 * `findByConversationKey`-Lookup, um Tesla-Thread-Stabilität zu garantieren.
 */
interface MappingRepository {

    suspend fun findById(mappingId: Long, channel: ChannelId): ChannelMapping?

    suspend fun findByConversationKey(channel: ChannelId, conversationKey: String): ChannelMapping?

    /**
     * Findet ein Mapping anhand des `fakeAddress`-Werts. Wird vom
     * [io.github.lycheeappf.tmm.sms.outbound.OutboundSmsClassifier] benutzt,
     * um Tesla-Replies (die exakt unsere ADDRESS-Spalte zurückschreiben)
     * deterministisch dem richtigen Channel zuzuordnen.
     */
    suspend fun findByFakeAddress(fakeAddress: String): ChannelMapping?

    /**
     * Findet ein existierendes Mapping mit gleichem conversationKey/Channel oder
     * erzeugt ein neues. Refreshes `lastUsedAt` und `expiresAt` bei Reuse
     * (verkürzt `expiresAt` dabei nie — `maxOf(bestehend, now+ttl)`).
     */
    suspend fun allocateOrReuse(
        channel: ChannelId,
        conversationKey: String,
        payload: ChannelPayload,
        ttlMillis: Long
    ): ChannelMapping

    /**
     * Stellt das reservierte, **nicht ablaufende** Mapping für den statischen
     * Grok-Kontakt sicher (idempotent). Räumt vorab via
     * [sweepStaleAssistantMappings] alle veralteten dynamischen LLM-Mappings ab
     * und erzeugt/aktualisiert dann die reservierte Id
     * ([io.github.lycheeappf.tmm.domain.channel.AssistantIdentity.RESERVED_MAPPING_ID]).
     */
    suspend fun ensureStaticAssistantMapping(displayName: String): ChannelMapping

    /**
     * Entfernt ALLE LLM-Mappings (inkl. zugehörigem Tesla-Kontakt), deren Id NICHT
     * die reservierte statische Grok-Id
     * ([io.github.lycheeappf.tmm.domain.channel.AssistantIdentity.RESERVED_MAPPING_ID])
     * ist. Da die App nur EINE Assistenten-Konversation kennt, sind alle anderen
     * LLM-Rows Altlasten (veraltete „Grok"-Duplikate, z.B. nach einem dynamischen
     * Allocate vor dem Seed der reservierten Identität). NOTIFICATION/SYSTEM-
     * Mappings werden nie angefasst. Gibt die Anzahl entfernter Rows zurück.
     */
    suspend fun sweepStaleAssistantMappings(): Int

    /**
     * Aktualisiert die Reply-Statistik nach erfolgreicher Zustellung.
     */
    suspend fun recordReplyAttempt(mappingId: Long, channel: ChannelId)

    suspend fun deleteExpired(now: Long)

    /**
     * Löscht ein einzelnes Mapping inkl. zugehörigem RawContact im Tesla-
     * Bridge-Account.
     */
    suspend fun delete(mappingId: Long, channel: ChannelId)

    /**
     * Löscht ALLE Mappings. Wird beim Wechsel des Number-Schema gerufen, sodass
     * alte fakeAddresses (im alten Schema-Prefix) nicht mehr aktiv sind.
     */
    suspend fun deleteAll()

    /**
     * Alle aktiven Mappings. Wird vom ContactBackfillWorker iteriert, um
     * RawContacts für bereits existierende Mappings nachzulegen.
     */
    suspend fun allMappings(): List<ChannelMapping>
}
