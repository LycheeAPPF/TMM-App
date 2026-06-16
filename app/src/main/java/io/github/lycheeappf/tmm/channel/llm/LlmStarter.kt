package io.github.lycheeappf.tmm.channel.llm

import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.core.security.ApiKeyStore
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.core.util.SendBudget
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.github.lycheeappf.tmm.domain.repository.MappingRepository
import io.github.lycheeappf.tmm.platform.role.DefaultSmsRoleManager
import io.github.lycheeappf.tmm.sms.provider.SmsContentProviderWriter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lege eine LLM-Konversation neu an (oder reuse die existierende), schreibe die
 * Welcome-Inbox-SMS in den Provider und setze den Conversation-Store auf
 * fresh-state. Nach diesem Call wartet der LLM-Channel passiv auf Tesla-Diktate.
 *
 *  - Privacy-Consent wird hier *nicht* geprüft — das macht die UI vor dem Call,
 *    damit der Coordinator (V3: auch BLE) sich keine Compose-Dialoge bauen muss.
 *  - Default-SMS-Role wird geprüft: ohne Role kann der Provider-Insert nicht
 *    schreiben, also macht der Welcome-Inject silent-fail. Wir checken pre-flight.
 *  - SendBudget zählt diesen Call (Welcome-Message ist eine "Inject-Slot",
 *    konsistent zur Behandlung in [NotificationCapture]).
 */
@Singleton
class LlmStarter @Inject constructor(
    private val mappingRepo: MappingRepository,
    private val smsWriter: SmsContentProviderWriter,
    private val prefs: AssistantPreferencesStore,
    private val sendBudget: SendBudget,
    private val store: LlmConversationStore,
    private val apiKeyStore: ApiKeyStore,
    private val roleManager: DefaultSmsRoleManager,
    private val rateLimiter: LlmRateLimiter,
    private val logBuffer: LogBuffer
) {

    sealed class StartResult {
        data class Success(val fakeAddress: String, val mappingId: Long) : StartResult()
        data object NoApiKey : StartResult()
        data object NotDefaultSmsApp : StartResult()
        data object BudgetExceeded : StartResult()
        data object InjectionFailed : StartResult()
        data object ConsentMissing : StartResult()
    }

    suspend fun start(source: AssistantTriggerSource): StartResult {
        if (!prefs.isPrivacyConsentGiven()) {
            logBuffer.warn(TAG, "Start requested but privacy consent not given (src=$source)")
            return StartResult.ConsentMissing
        }
        if (!roleManager.isDefault()) {
            logBuffer.warn(TAG, "Start requested but not default SMS app")
            return StartResult.NotDefaultSmsApp
        }
        if (apiKeyStore.read().isNullOrBlank()) {
            logBuffer.warn(TAG, "Start requested but no API key")
            return StartResult.NoApiKey
        }

        // SendBudget erst NACH den oben erfolgreichen Pre-Checks erhöhen, sodass
        // wir einen klar definierten Rollback-Pfad haben.
        if (!sendBudget.checkAndIncrement()) return StartResult.BudgetExceeded

        // budgetCommitted = false → bei jedem unteren Fehler ein Rollback.
        // Idempotent: wir setzen das Flag erst, wenn der Inject erfolgreich war.
        var budgetCommitted = false
        try {
            val payload = ChannelPayload.Llm(
                providerId = "grok",
                assistantDisplayName = prefs.assistantDisplayName(),
                conversationKey = CONVERSATION_KEY
            )
            val ttlMs = TimeUnit.HOURS.toMillis(prefs.mappingTtlHours().toLong())
            val mapping = mappingRepo.allocateOrReuse(
                channel = ChannelId.LLM,
                conversationKey = CONVERSATION_KEY,
                payload = payload,
                ttlMillis = ttlMs
            )
            // Fresh state UNTER dem Mutex der laufenden Session — verhindert,
            // dass ein parallel laufender TurnRunner mit einer Halb-leeren
            // History weiter rennt.
            store.resetUnderLock(mapping.mappingId)
            // Rate-Limit nur für DIESE Mapping-Sequenz zurücksetzen — resetAll()
            // würde alle anderen Konversationen mit clearen.
            rateLimiter.reset(mapping.mappingId)

            // welcomeMessage() löst {driver} auf und fällt intern auf den Default zurück;
            // hier KEIN .ifBlank auf die rohe DEFAULT_WELCOME-Konstante (sonst landet ein
            // unaufgelöstes {driver}-Token im Tesla-TTS).
            val welcome = prefs.welcomeMessage()
            val uri = smsWriter.injectIncoming(
                fakeAddress = mapping.fakeAddress,
                body = welcome,
                displayName = prefs.assistantDisplayName()
            )
            if (uri == null) {
                logBuffer.warn(TAG, "Welcome-Inject returned null")
                return StartResult.InjectionFailed
            }
            budgetCommitted = true
            logBuffer.info(
                TAG,
                "AI chat started via ${source.label} — mapping=${mapping.mappingId}, " +
                    "addr=${mapping.fakeAddress}"
            )
            return StartResult.Success(mapping.fakeAddress, mapping.mappingId)
        } catch (e: Exception) {
            // CancellationException muss bis nach oben durchschlagen, sonst bricht
            // strukturelle Konkurrenz und der Lifecycle-Stop wird stumm geschluckt.
            if (e is kotlinx.coroutines.CancellationException) throw e
            logBuffer.error(TAG, "Start aborted (${e::class.simpleName})")
            return StartResult.InjectionFailed
        } finally {
            if (!budgetCommitted) sendBudget.rollback()
        }
    }

    companion object {
        /**
         * Stabiler Key, damit dieselbe LLM-Konversation wiederverwendet wird.
         * Wechsel auf z.B. "default-assistant-v2" würde ein frisches Mapping
         * mit anderer Fake-Adresse erzwingen (für künftige Modell-Wechsel relevant).
         */
        const val CONVERSATION_KEY = "default-assistant"
        private const val TAG = "LlmStarter"
    }
}
