package io.github.lycheeappf.tmm.ui.screen.channels

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.domain.channel.ChannelRegistry
import javax.inject.Inject

data class ChannelRow(
    val id: ChannelId,
    val displayName: String,
    val isRegistered: Boolean,
    val isReserved: Boolean,
    val description: String
)

@HiltViewModel
class ChannelsViewModel @Inject constructor(
    private val registry: ChannelRegistry
) : ViewModel() {

    fun rows(): List<ChannelRow> = ChannelId.entries.map { id ->
        val registered = registry.isRegistered(id)
        ChannelRow(
            id = id,
            displayName = id.label,
            isRegistered = registered,
            // V2: LLM-Channel ist live. V3-reserve gibt es derzeit nicht.
            isReserved = !registered,
            description = when (id) {
                ChannelId.NOTIFICATION -> "Routet Tesla-Diktatantworten via RemoteInput an die Original-Messaging-App (WhatsApp, Telegram, Signal, …)"
                ChannelId.LLM -> "AI-Assistent (Grok): leitet Tesla-Diktate an xAI Grok und injiziert die Antwort als fake SMS, die der Tesla TTS vorliest. Konversation startet via Home-Screen-Button."
                ChannelId.SYSTEM -> "Interne Channel-Reserve für Pre-Flight-Tests und Diagnose-Konversationen"
            }
        )
    }
}
