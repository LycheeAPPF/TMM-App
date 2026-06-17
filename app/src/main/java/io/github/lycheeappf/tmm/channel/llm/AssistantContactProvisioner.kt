package io.github.lycheeappf.tmm.channel.llm

import io.github.lycheeappf.tmm.contact.ContactSyncWriter
import io.github.lycheeappf.tmm.core.security.ApiKeyStore
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.core.util.coRunCatching
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.github.lycheeappf.tmm.domain.channel.AssistantIdentity
import io.github.lycheeappf.tmm.domain.repository.MappingRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hält den statischen, Tesla-sichtbaren Grok-Kontakt mit dem Zustand des
 * Assistenten in Sync: der Kontakt existiert genau dann, wenn der Assistent
 * einsatzbereit ist (xAI-API-Key gesetzt UND Datenschutz-Einwilligung gegeben).
 *
 * Über diesen Kontakt kann die Tesla-Sprachsteuerung Grok direkt ansprechen
 * („schreibe eine Nachricht an Grok …"), ohne dass die App geöffnet werden muss —
 * die ausgehende SMS landet auf der reservierten Fake-Adresse und durchläuft
 * dieselbe Pipeline wie eine Grok-Antwort.
 *
 * Idee: DaGeneral.
 */
@Singleton
class AssistantContactProvisioner @Inject constructor(
    private val mappingRepository: MappingRepository,
    private val contactSyncWriter: ContactSyncWriter,
    private val prefs: AssistantPreferencesStore,
    private val apiKeyStore: ApiKeyStore,
    private val logBuffer: LogBuffer
) {

    /**
     * Gleicht den Grok-Kontakt mit dem Assistenten-Zustand ab: anlegen/aktualisieren
     * wenn einsatzbereit, sonst entfernen. Idempotent — kann nach jeder relevanten
     * Einstellungsänderung und beim Boot gefahrlos aufgerufen werden.
     */
    suspend fun reconcile() {
        val ready = prefs.isPrivacyConsentGiven() && !apiKeyStore.read().isNullOrBlank()
        if (ready) ensure() else remove()
    }

    private suspend fun ensure() {
        val name = prefs.assistantDisplayName()
        // ensureStaticAssistantMapping räumt zuerst veraltete dynamische Grok-Mappings
        // (+ ihre Kontakte) ab, danach steht genau die reservierte id-0-Identität.
        val mapping = mappingRepository.ensureStaticAssistantMapping(name)
        val ok = contactSyncWriter.upsertContact(mapping.fakeAddress, name)
        logBuffer.info(TAG, "Grok-Auto-Kontakt bereit: ${mapping.fakeAddress} ($name, upsert=$ok)")

        // Zusätzlicher Sprach-Ansprech-Kontakt mit nutzer-konfigurierbarem Namen
        // (z.B. „Walter Grok") — eigener Kontakt, KEINE DB-Row. Der Classifier lenkt
        // Diktate an diese Adresse auf die kanonische Grok-Session (id 0) um; die
        // Antwort kommt damit als „Grok" zurück. Per Schalter („Aus") deaktivierbar;
        // jeder reconcile() (Boot/Health/Backfill) ehrt das Pref.
        if (prefs.voiceAliasEnabled() && prefs.voiceAliasName().isNotBlank()) {
            val aliasName = prefs.voiceAliasName()
            val aliasOk = contactSyncWriter.upsertContact(AssistantIdentity.VOICE_ALIAS_FAKE_ADDRESS, aliasName)
            logBuffer.info(
                TAG,
                "Grok-Sprach-Alias bereit: ${AssistantIdentity.VOICE_ALIAS_FAKE_ADDRESS} ($aliasName, upsert=$aliasOk)"
            )
        } else {
            coRunCatching { contactSyncWriter.deleteContact(AssistantIdentity.VOICE_ALIAS_FAKE_ADDRESS) }
            logBuffer.info(TAG, "Grok-Sprach-Alias deaktiviert: ${AssistantIdentity.VOICE_ALIAS_FAKE_ADDRESS}")
        }
    }

    private suspend fun remove() {
        // Nur die Kontakte entfernen — das reservierte Mapping bleibt bestehen
        // (nicht ablaufend, ohne Kontakt unsichtbar). Das ermöglicht sofortiges
        // Re-Enable und Kontext-Kontinuität, ohne erneute Migration.
        coRunCatching { contactSyncWriter.deleteContact(AssistantIdentity.STATIC_FAKE_ADDRESS) }
        coRunCatching { contactSyncWriter.deleteContact(AssistantIdentity.VOICE_ALIAS_FAKE_ADDRESS) }
    }

    companion object {
        private const val TAG = "GrokContactProvisioner"
    }
}
