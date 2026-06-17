package io.github.lycheeappf.tmm.domain.channel

import io.github.lycheeappf.tmm.core.model.AddressScheme
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.core.model.FakeAddress

/**
 * Feste Identität des Grok-Assistenten als statischer, Tesla-sichtbarer Kontakt.
 *
 * Anders als die dynamisch pro Konversation allozierten Messenger-Mappings hat
 * Grok eine RESERVIERTE, nicht ablaufende Mapping-Id. Dadurch existiert der
 * Kontakt dauerhaft und die Tesla-Sprachsteuerung kann ihn per Name ansprechen
 * („schreibe eine Nachricht an Grok …") — auch ohne dass die App geöffnet wurde.
 *
 * Idee: DaGeneral.
 */
object AssistantIdentity {

    /** Stabiler conversationKey der einen Grok-Konversation (App-Button + Auto teilen ihn). */
    const val CONVERSATION_KEY = "default-assistant"

    /**
     * Reservierte Mapping-Id. [io.github.lycheeappf.tmm.data.store.SettingsStore.nextMappingId]
     * startet bei 1 und vergibt 0 NIE dynamisch — daher kann diese Id mit keinem
     * dynamischen Messenger-Mapping kollidieren.
     */
    const val RESERVED_MAPPING_ID = 0L

    /**
     * Reservierte Fake-Adresse `+88810000000` (Channel-Digit 1 = [ChannelId.LLM], Id 0).
     * `+888` ist das einzige aktive Schema (siehe [AddressScheme]).
     */
    val STATIC_FAKE_ADDRESS: String =
        FakeAddress(ChannelId.LLM, RESERVED_MAPPING_ID).toE164()

    /**
     * Reservierte Mapping-Id des zusätzlichen Sprach-Ansprech-Kontakts (Id 1).
     * Dieser Kontakt trägt einen NUTZER-konfigurierbaren Namen (z.B. „Elon Musk");
     * diktiert der User an ihn, lenkt der
     * [io.github.lycheeappf.tmm.sms.outbound.OutboundSmsClassifier] die ausgehende SMS
     * auf die KANONISCHE Grok-Session ([RESERVED_MAPPING_ID]) um — Grok antwortet damit
     * weiterhin als „Grok" (aus [STATIC_FAKE_ADDRESS]). Der Alias hat KEINE eigene DB-Row.
     */
    const val VOICE_ALIAS_MAPPING_ID = 1L

    /**
     * Fake-Adresse `+88810000001` des Sprach-Ansprech-Kontakts (Channel-Digit 1 = LLM,
     * Id 1) — kann per Konstruktion nie die NOTIFICATION-Kontakte (Digit 0) treffen.
     */
    val VOICE_ALIAS_FAKE_ADDRESS: String =
        FakeAddress(ChannelId.LLM, VOICE_ALIAS_MAPPING_ID).toE164()

    /**
     * Reservierte Mapping-Ids, die [io.github.lycheeappf.tmm.data.store.SettingsStore.nextMappingId]
     * NIE dynamisch vergeben darf: die statische Grok-Id (0) plus die Sprach-Alias-Id (1).
     * Verhindert, dass ein dynamisches Messenger-Mapping je eine reservierte Fake-Adresse bekommt.
     */
    val RESERVED_MAPPING_IDS: Set<Long> = setOf(RESERVED_MAPPING_ID, VOICE_ALIAS_MAPPING_ID)
}
