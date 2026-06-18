package io.github.lycheeappf.tmm.ui.screen.sms

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.core.di.IoDispatcher
import io.github.lycheeappf.tmm.core.locale.localizedString
import io.github.lycheeappf.tmm.domain.sms.SmsInboxReader
import io.github.lycheeappf.tmm.domain.sms.SmsMessage
import io.github.lycheeappf.tmm.domain.sms.SmsSendResult
import io.github.lycheeappf.tmm.domain.sms.SmsSender
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SmsThreadUiState(
    val title: String = "",
    val address: String = "",
    val messages: List<SmsMessage> = emptyList(),
    val loading: Boolean = true,
    val draft: String = "",
    val sending: Boolean = false,
    val feedback: String? = null
)

@HiltViewModel
class SmsThreadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val reader: SmsInboxReader,
    private val sender: SmsSender,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val threadId: Long = savedStateHandle.get<Long>(ARG_THREAD_ID) ?: INVALID_THREAD_ID

    private val _uiState = MutableStateFlow(SmsThreadUiState())
    val uiState: StateFlow<SmsThreadUiState> = _uiState.asStateFlow()

    init { observeChanges() }

    @OptIn(FlowPreview::class)
    private fun observeChanges() {
        viewModelScope.launch {
            reader.changes()
                .debounce(CHANGE_DEBOUNCE_MS)
                .onStart { emit(Unit) }
                .collect { reload() }
        }
    }

    fun refresh() {
        viewModelScope.launch { reload() }
    }

    fun setDraft(value: String) {
        _uiState.update { it.copy(draft = value) }
    }

    fun consumeFeedback() {
        _uiState.update { it.copy(feedback = null) }
    }

    /** Sendet den aktuellen Entwurf an die Thread-Adresse. */
    fun send() {
        val current = _uiState.value
        val address = current.address
        val body = current.draft.trim()
        if (current.sending || body.isEmpty() || address.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(sending = true) }
            val result = sender.send(address, body)
            _uiState.update {
                it.copy(
                    sending = false,
                    draft = if (result is SmsSendResult.Enqueued) "" else it.draft,
                    feedback = smsSendFeedback(context, result)
                )
            }
            // Neue OUTBOX-Row erscheint via ContentObserver → reload zeigt sie.
        }
    }

    private suspend fun reload() {
        if (threadId < 0) {
            _uiState.update { it.copy(loading = false) }
            return
        }
        val messages = withContext(ioDispatcher) { reader.loadThread(threadId) }
        val address = messages.firstOrNull()?.address ?: _uiState.value.address
        val name = if (address.isNotBlank()) withContext(ioDispatcher) { reader.contactName(address) } else null
        _uiState.update {
            it.copy(
                messages = messages,
                address = address,
                title = name ?: address.ifBlank { context.localizedString(R.string.sms_thread_title_fallback) },
                loading = false
            )
        }
        // Beim Anzeigen als gelesen markieren (fake-sicher, scoped auf thread_id).
        // Nur wenn es ungelesene eingehende Nachrichten gibt → 0-Row-Update sonst,
        // damit kein Observer-Loop entsteht.
        if (messages.any { it.isIncoming && !it.read }) {
            reader.markThreadRead(threadId)
        }
    }

    companion object {
        const val ARG_THREAD_ID = "threadId"
        private const val INVALID_THREAD_ID = -1L
        private const val CHANGE_DEBOUNCE_MS = 250L
    }
}
