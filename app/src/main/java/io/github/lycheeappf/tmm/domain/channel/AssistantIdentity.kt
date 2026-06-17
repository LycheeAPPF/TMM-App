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

    /**
     * Phonetischer Sprach-Alias als eigene Identität.
     *
     * Die Tesla-Sprachsteuerung versteht „Grok" zuverlässig falsch — mal als
     * „Grog", mal als „Grogg". Für jeden Verhörer existiert daher ein eigener,
     * Tesla-sichtbarer Kontakt. Diktiert der User an einen Alias, lenkt der
     * [io.github.lycheeappf.tmm.sms.outbound.OutboundSmsClassifier] die ausgehende
     * SMS auf die KANONISCHE Grok-Session ([RESERVED_MAPPING_ID]) um — die Antwort
     * kommt damit als „Grok" (aus [STATIC_FAKE_ADDRESS]) zurück.
     *
     * Wichtig: Ein Alias hat KEINE eigene DB-Row. Die reservierte Mapping-Id dient
     * nur zur Erzeugung einer stabilen Fake-Adresse (Kontakt-`SOURCE_ID` + Routing-
     * Token) und wird via [RESERVED_MAPPING_IDS] von [nextMappingId] ausgeschlossen,
     * damit nie ein dynamisches Mapping auf einer Alias-Adresse landet.
     */
    data class AssistantAlias(
        val mappingId: Long,
        val fakeAddress: String,
        val displayName: String
    )

    /**
     * Feste Sprach-Aliasse. LLM-Channel-Digit `1` → die Adressen können per
     * Konstruktion NIE die NOTIFICATION-Kontakte (Digit `0`, WhatsApp/Telegram/
     * Signal) treffen.
     *  - „Grog"  → `+88810000001` (Id 1)
     *  - „Grogg" → `+88810000002` (Id 2)
     */
    val ALIASES: List<AssistantAlias> = listOf(
        alias(1L, "Grog"),
        alias(2L, "Grogg")
    )

    /** Alle Alias-Fake-Adressen — vom Classifier auf die kanonische Grok-Session umgelenkt. */
    val ALIAS_FAKE_ADDRESSES: Set<String> = ALIASES.map { it.fakeAddress }.toSet()

    /**
     * Reservierte Mapping-Ids, die [nextMappingId] NIE dynamisch vergeben darf:
     * die statische Grok-Id (0) plus alle Alias-Ids. Verhindert, dass ein
     * dynamisches Messenger-Mapping je eine reservierte Fake-Adresse bekommt.
     */
    val RESERVED_MAPPING_IDS: Set<Long> =
        (listOf(RESERVED_MAPPING_ID) + ALIASES.map { it.mappingId }).toSet()

    private fun alias(mappingId: Long, displayName: String): AssistantAlias =
        AssistantAlias(
            mappingId = mappingId,
            fakeAddress = FakeAddress(ChannelId.LLM, mappingId).toE164(AddressScheme.Itu888),
            displayName = displayName
        )
}
