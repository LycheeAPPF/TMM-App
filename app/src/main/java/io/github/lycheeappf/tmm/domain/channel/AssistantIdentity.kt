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
     * `itu_888` ist das einzige aktive Schema (siehe [AddressScheme]).
     */
    val STATIC_FAKE_ADDRESS: String =
        FakeAddress(ChannelId.LLM, RESERVED_MAPPING_ID).toE164(AddressScheme.Itu888)
}
