package io.github.lycheeappf.tmm.channel.notification

import android.service.notification.StatusBarNotification
import android.util.Log
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.core.util.SendBudget
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.github.lycheeappf.tmm.domain.repository.MappingRepository
import io.github.lycheeappf.tmm.listener.filter.MessagingStyleExtractor
import io.github.lycheeappf.tmm.listener.filter.WhitelistFilter
import io.github.lycheeappf.tmm.platform.role.DefaultSmsRoleManager
import io.github.lycheeappf.tmm.sms.provider.SmsContentProviderWriter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestriert den Inbound-Pfad (Messenger-Notification → Tesla-sichtbare fake SMS).
 *
 * Reihenfolge der Checks:
 * 1. Whitelist
 * 2. Body-Extraktion (MessagingStyle / Title-Fallback)
 * 3. **Dedup**: gleicher (conversationKey, bodyHash) wie vorher → skip
 *    (Messenger posten oft mehrere Update-Events für dieselbe Nachricht)
 * 4. roleManager.isDefault()
 * 5. SendBudget.checkAndIncrement()  ← Budget wird hier RESERVIERT
 * 6. Mapping allocate/reuse + ActionCache + injectIncoming
 * 7. Bei Insert-Fehler: SendBudget.rollback() ← reservierten Slot wieder freigeben
 */
@Singleton
class NotificationCapture @Inject constructor(
    private val whitelist: WhitelistFilter,
    private val messagingStyleExtractor: MessagingStyleExtractor,
    private val actionResolver: ActionResolver,
    private val actionCache: ActionCache,
    private val mappingRepository: MappingRepository,
    private val smsWriter: SmsContentProviderWriter,
    private val sendBudget: SendBudget,
    private val roleManager: DefaultSmsRoleManager,
    private val settingsStore: SettingsStore,
    private val logBuffer: LogBuffer
) {

    private val captureMutex = Mutex()

    /**
     * Dedup-Cache pro conversationKey → letzter Body-Inhalt.
     *
     * Wir speichern den vollen Body-String statt nur `hashCode()` — 32-bit-Hashes
     * kollidieren bei kurzen, ähnlichen Antworten ("OK", "Ok", "ok") und
     * würden legitime Nachrichten silent droppen. Konkret: zwei Strings mit
     * gleichem `hashCode()` aber verschiedenem Inhalt sind häufiger als man denkt.
     *
     * Speicherkosten: 500 Einträge × Ø 80 Bytes = ~40 KB. Vertretbar.
     */
    private val lastBodies = ConcurrentHashMap<String, String>()

    suspend fun onPosted(sbn: StatusBarNotification) {
        try {
            captureMutex.withLock { captureInternal(sbn) }
        } catch (e: Exception) {
            Log.e(TAG, "Capture failed for ${sbn.key}", e)
            logBuffer.error(TAG, "Capture failed for ${sbn.key}: ${e.message}")
        }
    }

    private suspend fun captureInternal(sbn: StatusBarNotification) {
        if (!whitelist.allow(sbn.packageName)) return

        val msg = messagingStyleExtractor.extract(sbn) ?: return
        if (msg.body.isBlank()) return

        // Dedup: identische (conversation, body) Tuple wurde schon verarbeitet?
        // Messenger senden typischerweise mehrere onNotificationPosted Events
        // für dieselbe eingehende Nachricht (z.B. nach 'delivered'-Update).
        val previousBody = lastBodies[msg.conversationKey]
        if (previousBody == msg.body) return

        if (!roleManager.isDefault()) {
            Log.w(TAG, "Skipping capture: app is not default SMS app — inject would silent-fail")
            logBuffer.warn(TAG, "Skipped ${sbn.key}: not default SMS app")
            return
        }

        if (!sendBudget.checkAndIncrement()) {
            Log.w(TAG, "Skipping capture: send budget reached for today")
            logBuffer.warn(TAG, "Send budget exceeded — ${sbn.key} dropped")
            return
        }

        // Ab hier ist das Budget reserviert. Bei jedem early-return ROLLBACK.
        var budgetCommitted = false
        try {
            val resolved = actionResolver.findReplyAction(sbn.notification)
            val replyable = resolved != null

            val payload = ChannelPayload.Notification(
                sourcePackage = sbn.packageName,
                notificationKey = sbn.key,
                remoteInputResultKey = resolved?.remoteInputs?.firstOrNull()?.resultKey,
                conversationLabel = msg.conversationLabel,
                senderDisplayName = msg.senderName
            )

            val ttlMillis = TimeUnit.HOURS.toMillis(settingsStore.mappingTtlHours().toLong())
            val mapping = mappingRepository.allocateOrReuse(
                channel = ChannelId.NOTIFICATION,
                conversationKey = msg.conversationKey,
                payload = payload,
                ttlMillis = ttlMillis
            )

            val bodyForTesla = formatBody(msg.isGroup, msg.senderName, msg.body)
            val insertedUri = smsWriter.injectIncoming(
                fakeAddress = mapping.fakeAddress,
                body = bodyForTesla,
                timestamp = sbn.postTime,
                displayName = msg.conversationLabel
            )
            if (insertedUri == null) {
                Log.w(TAG, "injectIncoming returned null — Tesla wird ${sbn.key} nicht sehen")
                logBuffer.warn(TAG, "Provider insert failed for ${mapping.fakeAddress} — Tesla blind")
                // ActionCache NICHT füllen — kein Reply auf eine nie-gesehene Adresse.
                return
            }

            if (resolved != null) {
                actionCache.put(sbn.key, resolved)
            }
            lastBodies[msg.conversationKey] = msg.body
            evictOldDedupEntries()

            budgetCommitted = true
            Log.i(TAG, "Captured ${sbn.key} → ${mapping.fakeAddress} (replyable=$replyable)")
            // Nur Metadaten loggen — body landet sonst über DiagnosticsExporter
            // im exportierten Log und ist damit ein Privacy-Leak.
            logBuffer.info(
                TAG,
                "Inject ${mapping.fakeAddress}: ${msg.senderName} (${msg.body.length} chars)"
            )
        } finally {
            if (!budgetCommitted) {
                sendBudget.rollback()
            }
        }
    }

    private fun evictOldDedupEntries() {
        if (lastBodies.size <= DEDUP_CACHE_SIZE) return
        // grobe Eviction — ConcurrentHashMap.keys ist nicht ordered, also droppen
        // wir willkürliche Einträge. Akzeptabel weil Cache nur Performance, kein
        // Korrektheits-Krücke (Worst-Case: zwei aufeinanderfolgende Events
        // mit identischem Body werden beide gepostet, was wir sowieso über
        // SendBudget abfedern).
        val toRemove = lastBodies.keys.take(lastBodies.size - DEDUP_CACHE_SIZE)
        toRemove.forEach { lastBodies.remove(it) }
    }

    /**
     * Bei 1:1-Chats verzichten wir auf den Sender-Prefix — Tesla zeigt + spricht
     * den Contact-Namen bereits beim Empfang. Bei Gruppen-Chats bleibt der
     * Sender im Body, damit erkennbar ist, wer in der Gruppe geschrieben hat.
     */
    private fun formatBody(isGroup: Boolean, sender: String, body: String): String {
        return if (isGroup) "$sender: $body" else body
    }

    companion object {
        private const val TAG = "NotificationCapture"
        private const val DEDUP_CACHE_SIZE = 500
    }
}
