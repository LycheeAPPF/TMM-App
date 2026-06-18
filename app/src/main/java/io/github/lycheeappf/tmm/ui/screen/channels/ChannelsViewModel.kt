package io.github.lycheeappf.tmm.ui.screen.channels

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.core.locale.localizedString
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
    @ApplicationContext private val context: android.content.Context,
    private val registry: ChannelRegistry
) : ViewModel() {

    fun rows(): List<ChannelRow> = ChannelId.entries.map { id ->
        val registered = registry.isRegistered(id)
        ChannelRow(
            id = id,
            displayName = when (id) {
                ChannelId.NOTIFICATION -> context.localizedString(R.string.channel_notification_label)
                ChannelId.LLM -> context.localizedString(R.string.channel_llm_label)
                ChannelId.SYSTEM -> context.localizedString(R.string.channel_system_label)
            },
            isRegistered = registered,
            // V2: LLM-Channel ist live. V3-reserve gibt es derzeit nicht.
            isReserved = !registered,
            description = when (id) {
                ChannelId.NOTIFICATION -> context.localizedString(R.string.channels_desc_notification)
                ChannelId.LLM -> context.localizedString(R.string.channels_desc_llm)
                ChannelId.SYSTEM -> context.localizedString(R.string.channels_desc_system)
            }
        )
    }
}
