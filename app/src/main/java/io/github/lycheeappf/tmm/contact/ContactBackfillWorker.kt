package io.github.lycheeappf.tmm.contact

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.github.lycheeappf.tmm.domain.repository.MappingRepository

/**
 * Einmaliger Migration-Worker. Iteriert alle aktiven Mappings und legt
 * RawContacts im Tesla-Bridge-Account für sie an. Wird nach dem ersten
 * Permission-Grant ([enqueue]) gestartet, ist aber idempotent — wiederholte
 * Läufe sind harmlos (`SOURCE_ID`-Match erkennt existierende Contacts).
 */
@HiltWorker
class ContactBackfillWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val mappingRepository: MappingRepository,
    private val contactSyncWriter: ContactSyncWriter
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!contactSyncWriter.hasPermission()) return Result.success()
        contactSyncWriter.ensureAccountAndVisibility()

        val mappings = io.github.lycheeappf.tmm.core.util.coRunCatching {
            mappingRepository.allMappings()
        }.getOrElse { return Result.retry() }

        for (mapping in mappings) {
            val displayName = displayNameFor(mapping.payload) ?: continue
            io.github.lycheeappf.tmm.core.util.coRunCatching {
                contactSyncWriter.upsertContact(mapping.fakeAddress, displayName)
            }
        }
        return Result.success()
    }

    private fun displayNameFor(payload: ChannelPayload): String? = when (payload) {
        is ChannelPayload.Notification -> payload.conversationLabel.ifBlank { payload.senderDisplayName }
        // assistantDisplayName statt hardcoded "AI Assistant" — sonst legt der
        // Backfill den LLM-Contact unter falschem Namen an, und Tesla zeigt
        // "AI Assistant" statt "Grok", bis der nächste Live-Inject ihn korrigiert.
        is ChannelPayload.Llm -> payload.assistantDisplayName.ifBlank { "Grok" }
        is ChannelPayload.System -> null
    }

    companion object {
        const val NAME = "MfsContactBackfillWorker"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ContactBackfillWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
