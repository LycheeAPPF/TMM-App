package io.github.lycheeappf.tmm.ui.screen.diagnostics

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.channel.llm.LlmStarter
import io.github.lycheeappf.tmm.contact.ContactDiagnostics
import io.github.lycheeappf.tmm.core.locale.localizedString
import io.github.lycheeappf.tmm.contact.SenderResolutionResult
import io.github.lycheeappf.tmm.core.di.IoDispatcher
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.core.util.DiagnosticsExporter
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.data.db.MappingDao
import io.github.lycheeappf.tmm.data.db.MappingEntity
import io.github.lycheeappf.tmm.data.db.ReplyHistoryDao
import io.github.lycheeappf.tmm.data.db.ReplyHistoryEntity
import io.github.lycheeappf.tmm.data.store.AssistantPreferencesStore
import io.github.lycheeappf.tmm.domain.channel.ChannelPayload
import io.github.lycheeappf.tmm.domain.repository.MappingRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class DiagnosticsUiState(
    val selectedChannel: ChannelId = ChannelId.NOTIFICATION,
    val mappings: List<MappingEntity> = emptyList(),
    val replyHistory: List<ReplyHistoryEntity> = emptyList(),
    val exportInFlight: Boolean = false,
    val lastExportPath: String? = null,
    val senderTestRunning: Boolean = false,
    val senderTest: SenderTestUi? = null
)

/** Ergebnis des Diagnose-Buttons "Sender-Resolution testen". */
data class SenderTestUi(
    val fakeAddress: String = "",
    val displayName: String = "",
    val result: SenderResolutionResult? = null,
    val error: String? = null
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mappingDao: MappingDao,
    private val exporter: DiagnosticsExporter,
    replyHistoryDao: ReplyHistoryDao,
    logBuffer: LogBuffer,
    private val contactDiagnostics: ContactDiagnostics,
    private val mappingRepository: MappingRepository,
    private val assistantPrefs: AssistantPreferencesStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val selectedChannel = MutableStateFlow(ChannelId.NOTIFICATION)
    private val transient = MutableStateFlow(TransientState())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val mappingsFlow = selectedChannel.flatMapLatest { ch ->
        mappingDao.observeByChannel(ch.code, limit = 50)
    }

    private val historyFlow = replyHistoryDao.observeRecent(limit = 50)

    val uiState: StateFlow<DiagnosticsUiState> = combine(
        selectedChannel, mappingsFlow, historyFlow, transient
    ) { channel, mappings, history, transient ->
        DiagnosticsUiState(
            selectedChannel = channel,
            mappings = mappings,
            replyHistory = history,
            exportInFlight = transient.exporting,
            lastExportPath = transient.lastPath,
            senderTestRunning = transient.senderTestRunning,
            senderTest = transient.senderTest
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())

    /**
     * Live-Log getrennt vom Haupt-State: der Buffer emittiert in Hot-Paths (pro
     * SMS/Notification). Würde er Teil von [uiState] sein, würde jede Log-Zeile
     * auch die Mappings-/Reply-Tabs rekomponieren. Wird nur gesammelt, während der
     * Live-Log-Tab sichtbar ist (WhileSubscribed).
     */
    val logs: StateFlow<List<LogBuffer.LogEntry>> = logBuffer.events
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectChannel(channel: ChannelId) {
        selectedChannel.value = channel
    }

    fun exportDiagnostics() {
        viewModelScope.launch {
            transient.value = transient.value.copy(exporting = true)
            val path = withContext(ioDispatcher) {
                runCatching { exporter.exportToCache().absolutePath }.getOrNull()
            }
            transient.value = transient.value.copy(exporting = false, lastPath = path)
        }
    }

    /**
     * On-device-Selbsttest des Clean-Name-Pfads. Holt die Grok-Assistant-Mapping
     * (gleiche Allocation wie [LlmStarter]), legt den Kontakt an und prüft via der
     * AOSP-MAP-identischen PhoneLookup-Query, ob Tesla den Namen statt der Nummer
     * bekommt. Macht KEINEN SMS-Inject — rein diagnostisch.
     */
    fun runSenderResolutionTest() {
        viewModelScope.launch {
            transient.value = transient.value.copy(senderTestRunning = true)
            val outcome = withContext(ioDispatcher) {
                runCatching {
                    val displayName = assistantPrefs.assistantDisplayName().ifBlank { "Grok" }
                    val ttlMs = TimeUnit.HOURS.toMillis(assistantPrefs.mappingTtlHours().toLong())
                    val mapping = mappingRepository.allocateOrReuse(
                        channel = ChannelId.LLM,
                        conversationKey = LlmStarter.CONVERSATION_KEY,
                        payload = ChannelPayload.Llm(
                            assistantDisplayName = displayName,
                            conversationKey = LlmStarter.CONVERSATION_KEY
                        ),
                        ttlMillis = ttlMs
                    )
                    SenderTestUi(
                        fakeAddress = mapping.fakeAddress,
                        displayName = displayName,
                        result = contactDiagnostics.testSenderResolution(mapping.fakeAddress, displayName)
                    )
                }.getOrElse { e ->
                    SenderTestUi(
                        error = e.message ?: e::class.simpleName
                            ?: context.localizedString(R.string.diagnostics_sender_test_unknown_error)
                    )
                }
            }
            transient.value = transient.value.copy(senderTestRunning = false, senderTest = outcome)
        }
    }

    private data class TransientState(
        val exporting: Boolean = false,
        val lastPath: String? = null,
        val senderTestRunning: Boolean = false,
        val senderTest: SenderTestUi? = null
    )
}
