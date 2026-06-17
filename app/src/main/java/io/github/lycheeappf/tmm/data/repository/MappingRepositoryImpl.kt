package io.github.lycheeappf.tmm.data.repository

import io.github.lycheeappf.tmm.contact.ContactSyncWriter
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.core.util.coRunCatching
import io.github.lycheeappf.tmm.core.model.FakeAddress
import io.github.lycheeappf.tmm.data.db.MappingDao
import io.github.lycheeappf.tmm.data.db.MappingEntity
import io.github.lycheeappf.tmm.data.db.PayloadJson
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.domain.channel.AssistantIdentity
import io.github.lycheeappf.tmm.domain.channel.ChannelMapping
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.github.lycheeappf.tmm.domain.repository.MappingRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MappingRepositoryImpl @Inject constructor(
    private val dao: MappingDao,
    private val settings: SettingsStore,
    private val contactSyncWriter: ContactSyncWriter
) : MappingRepository {

    override suspend fun findById(mappingId: Long, channel: ChannelId): ChannelMapping? =
        dao.findById(mappingId, channel.code)?.toDomain()

    override suspend fun findByConversationKey(
        channel: ChannelId,
        conversationKey: String
    ): ChannelMapping? = dao.findByConversationKey(channel.code, conversationKey)?.toDomain()

    override suspend fun findByFakeAddress(fakeAddress: String): ChannelMapping? =
        dao.findByFakeAddress(fakeAddress)?.toDomain()

    override suspend fun allocateOrReuse(
        channel: ChannelId,
        conversationKey: String,
        payload: ChannelPayload,
        ttlMillis: Long
    ): ChannelMapping {
        val now = System.currentTimeMillis()
        val newExpiry = now + ttlMillis

        val existing = dao.findByConversationKey(channel.code, conversationKey)
        if (existing != null) {
            val newReplyable = payload.isReplyable
            // CX7: einmal replyable, bleibt replyable. Wenn ein Messenger eine Folge-
            // Notification ohne RemoteInput postet (z.B. "delivered"-Update),
            // soll der Chat weiter beantwortbar bleiben.
            val effectiveReplyable = existing.replyable || newReplyable
            val newPayloadJson = PayloadJson.encode(payload)
            // Reuse verlängert die TTL, verkürzt sie aber NIE. Wichtig fürs statische
            // Grok-Mapping (expiresAt = Long.MAX_VALUE): ein Button-Start
            // (`allocateOrReuse`) darf es nicht auf now+ttl herunterziehen und damit
            // wieder ablaufbar machen.
            val effectiveExpiry = maxOf(existing.expiresAt, newExpiry)
            dao.refreshOnReuse(
                mappingId = existing.mappingId,
                channel = channel.code,
                payloadJson = newPayloadJson,
                replyable = effectiveReplyable,
                newExpiresAt = effectiveExpiry,
                now = now
            )
            // Migration: ein vorheriger Display-only-Versuch hat fakeAddress
            // auf einen Klartext-Namen ("Grok", "Anna") gesetzt. Das bricht
            // den Reply-Pfad (Tesla schreibt outbox-Row nicht zuverlässig mit
            // unserem String). Wir migrieren zurück auf die deterministische
            // numerische `+888x...`-Form, damit `FakeAddress.parse(outbox)`
            // wieder zuverlässig zur mappingId resolved. SmsContentProvider-
            // Writer wickelt die Number dann in "Display <Number>" ein, damit
            // Tesla wenigstens den Namen mit anzeigt.
            val migratedAddress = maybeMigrateDisplayBackToNumeric(existing)
            return existing.copy(
                expiresAt = effectiveExpiry,
                lastUsedAt = now,
                payloadJson = newPayloadJson,
                replyable = effectiveReplyable,
                fakeAddress = migratedAddress
            ).toDomain()
        }

        val newId = settings.nextMappingId()
        val fakeAddress = FakeAddress(channel, newId).toE164()
        val entity = MappingEntity(
            mappingId = newId,
            channel = channel.code,
            fakeAddress = fakeAddress,
            conversationKey = conversationKey,
            payloadJson = PayloadJson.encode(payload),
            createdAt = now,
            expiresAt = newExpiry,
            lastUsedAt = null,
            replyCount = 0,
            replyable = payload.isReplyable
        )
        dao.insert(entity)
        return entity.toDomain()
    }

    /**
     * Wenn das existing-Mapping einen Display-Only-fakeAddress hat (z.B. "Grok"),
     * migrieren wir es zurück auf die numerische `+888x...`-Form. Das ist
     * die rollback-Migration zum letzten working Reply-Pfad — Display-only
     * brach den Outbox-Roundtrip. Die numerische Form wird vom Provider-
     * Writer in ein "Display <Number>"-Format eingewickelt, damit Tesla
     * beide Informationen sieht.
     */
    private suspend fun maybeMigrateDisplayBackToNumeric(existing: MappingEntity): String {
        // FakeAddress.parse() returnt nicht-null nur für unsere numerischen
        // Schema-Adressen. Wenn parse != null → schon numeric → no-op.
        if (FakeAddress.parse(existing.fakeAddress) != null) return existing.fakeAddress
        val numeric = FakeAddress(
            channel = io.github.lycheeappf.tmm.core.model.ChannelId.fromCode(existing.channel)
                ?: return existing.fakeAddress,
            mappingId = existing.mappingId
        ).toE164()
        dao.updateFakeAddress(existing.mappingId, existing.channel, numeric)
        return numeric
    }

    override suspend fun ensureStaticAssistantMapping(displayName: String): ChannelMapping {
        // Erst alle veralteten dynamischen LLM-Mappings (+ ihre Kontakte) abräumen.
        // Danach kann höchstens noch die reservierte id-0-Row existieren — der
        // Unique-Index (channel, conversationKey) ist damit garantiert frei.
        sweepStaleAssistantMappings()

        val now = System.currentTimeMillis()
        val payloadJson = PayloadJson.encode(
            ChannelPayload.Llm(
                providerId = "grok",
                assistantDisplayName = displayName,
                conversationKey = AssistantIdentity.CONVERSATION_KEY
            )
        )
        val existing = dao.findById(AssistantIdentity.RESERVED_MAPPING_ID, ChannelId.LLM.code)

        // Bereits reserviert (id 0) → nur Adresse/Name aktualisieren und
        // Nicht-Ablaufen (expiresAt = MAX) sicherstellen. Idempotent.
        if (existing != null) {
            val updated = existing.copy(
                fakeAddress = AssistantIdentity.STATIC_FAKE_ADDRESS,
                payloadJson = payloadJson,
                expiresAt = Long.MAX_VALUE,
                replyable = true
            )
            dao.update(updated)
            return updated.toDomain()
        }

        val entity = MappingEntity(
            mappingId = AssistantIdentity.RESERVED_MAPPING_ID,
            channel = ChannelId.LLM.code,
            fakeAddress = AssistantIdentity.STATIC_FAKE_ADDRESS,
            conversationKey = AssistantIdentity.CONVERSATION_KEY,
            payloadJson = payloadJson,
            createdAt = now,
            expiresAt = Long.MAX_VALUE,
            lastUsedAt = null,
            replyCount = 0,
            replyable = true
        )
        dao.insert(entity)
        return entity.toDomain()
    }

    override suspend fun sweepStaleAssistantMappings(): Int {
        var removed = 0
        val llmRows = dao.findByChannel(ChannelId.LLM.code)
        for (entity in llmRows) {
            if (entity.mappingId == AssistantIdentity.RESERVED_MAPPING_ID) continue
            // ZUERST Kontakt löschen (fakeAddress als Lookup-Key), DANN die Row.
            coRunCatching { contactSyncWriter.deleteContact(entity.fakeAddress) }
            dao.deleteById(entity.mappingId, ChannelId.LLM.code)
            removed++
        }
        return removed
    }

    override suspend fun recordReplyAttempt(mappingId: Long, channel: ChannelId) {
        dao.recordReply(mappingId, channel.code, System.currentTimeMillis())
    }

    override suspend fun deleteExpired(now: Long) {
        // Reihenfolge: ZUERST Adressen lesen, Contacts dazu löschen, DANN DB-Rows.
        // Würde die Reihenfolge umgedreht, hätten wir keinen Lookup-Key mehr für
        // die Contact-Cleanup-Schleife.
        val expired = dao.findExpired(now)
        for (entity in expired) {
            coRunCatching { contactSyncWriter.deleteContact(entity.fakeAddress) }
        }
        dao.deleteExpired(now)
    }

    override suspend fun delete(mappingId: Long, channel: ChannelId) {
        val entity = dao.findById(mappingId, channel.code) ?: return
        coRunCatching { contactSyncWriter.deleteContact(entity.fakeAddress) }
        dao.deleteById(mappingId, channel.code)
    }

    override suspend fun allMappings(): List<ChannelMapping> =
        dao.findAll().map { it.toDomain() }

    private fun MappingEntity.toDomain(): ChannelMapping {
        val channelId = ChannelId.fromCode(channel)
            ?: error("Unknown channel code $channel in DB row $mappingId")
        return ChannelMapping(
            mappingId = mappingId,
            channel = channelId,
            fakeAddress = fakeAddress,
            conversationKey = conversationKey,
            payload = PayloadJson.decodeOrFallback(payloadJson),
            createdAt = createdAt,
            expiresAt = expiresAt,
            lastUsedAt = lastUsedAt,
            replyCount = replyCount,
            replyable = replyable
        )
    }

}
