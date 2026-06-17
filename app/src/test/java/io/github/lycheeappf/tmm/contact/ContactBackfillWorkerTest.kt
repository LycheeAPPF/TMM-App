package io.github.lycheeappf.tmm.contact

import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.google.common.truth.Truth.assertThat
import io.github.lycheeappf.tmm.channel.llm.AssistantContactProvisioner
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.domain.channel.ChannelMapping
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.github.lycheeappf.tmm.domain.repository.MappingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Sichert die zwei feature-tragenden Zweige des [ContactBackfillWorker] ab:
 *  1. LLM-Mappings (Grok + Sprach-Aliasse) werden im Backfill ÜBERSPRUNGEN —
 *     sonst materialisierte der Worker ein veraltetes Grok-Duplikat aus einer
 *     Altlast-Row neu (genau der Bug, den die Konsolidierung behebt).
 *  2. Nach dem Backfill delegiert er an [AssistantContactProvisioner.reconcile],
 *     der allein für Grok + Aliasse zuständig ist.
 * Beides ist sonst ungetestet — ein gedropptes `continue` bzw. ein entfernter
 * `reconcile()`-Call würde keinen bestehenden Test brechen.
 */
class ContactBackfillWorkerTest {

    private val mappingRepository = mockk<MappingRepository>(relaxed = true)
    private val contactSyncWriter = mockk<ContactSyncWriter>(relaxed = true)
    private val contactProvisioner = mockk<AssistantContactProvisioner>(relaxed = true)

    private val worker = ContactBackfillWorker(
        mockk(relaxed = true),
        mockk<WorkerParameters>(relaxed = true),
        mappingRepository,
        contactSyncWriter,
        contactProvisioner
    )

    private fun notificationMapping() = ChannelMapping(
        mappingId = 42L,
        channel = ChannelId.NOTIFICATION,
        fakeAddress = "+88800000042",
        conversationKey = "com.whatsapp::anna",
        payload = ChannelPayload.Notification(
            sourcePackage = "com.whatsapp",
            notificationKey = "key-1",
            remoteInputResultKey = "input",
            conversationLabel = "Anna",
            senderDisplayName = "Anna"
        ),
        createdAt = 0L,
        expiresAt = Long.MAX_VALUE,
        lastUsedAt = null,
        replyCount = 0,
        replyable = true
    )

    private fun llmMapping() = ChannelMapping(
        mappingId = 0L,
        channel = ChannelId.LLM,
        fakeAddress = "+88810000000",
        conversationKey = "default-assistant",
        payload = ChannelPayload.Llm(),
        createdAt = 0L,
        expiresAt = Long.MAX_VALUE,
        lastUsedAt = null,
        replyCount = 0,
        replyable = true
    )

    @Test
    fun `doWork backfills NOTIFICATION but skips LLM and delegates to reconcile`() = runTest {
        every { contactSyncWriter.hasPermission() } returns true
        coEvery { mappingRepository.allMappings() } returns listOf(notificationMapping(), llmMapping())
        coEvery { contactSyncWriter.upsertContact(any(), any()) } returns true

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        // NOTIFICATION-Kontakt wird gebackfilled …
        coVerify(exactly = 1) { contactSyncWriter.upsertContact("+88800000042", "Anna") }
        // … der LLM-Kontakt NICHT (gehört allein dem Provisioner).
        coVerify(exactly = 0) { contactSyncWriter.upsertContact("+88810000000", any()) }
        // … und Grok + Aliasse werden via reconcile() (re-)provisioniert.
        coVerify(exactly = 1) { contactProvisioner.reconcile() }
    }

    @Test
    fun `doWork without contacts permission is a no-op`() = runTest {
        every { contactSyncWriter.hasPermission() } returns false

        val result = worker.doWork()

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        coVerify(exactly = 0) { mappingRepository.allMappings() }
        coVerify(exactly = 0) { contactSyncWriter.upsertContact(any(), any()) }
        coVerify(exactly = 0) { contactProvisioner.reconcile() }
    }
}
