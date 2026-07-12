package io.github.lycheeappf.tmm.ui.screen.sms

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.lycheeappf.tmm.domain.sms.SmsSendResult
import io.github.lycheeappf.tmm.domain.sms.SmsSender
import io.github.lycheeappf.tmm.ui.navigation.ARG_BODY
import io.github.lycheeappf.tmm.ui.navigation.ARG_RECIPIENT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SmsComposeUiState(
    val recipient: String = "",
    val body: String = "",
    val sending: Boolean = false,
    val feedback: String? = null,
    /** true, sobald erfolgreich übergeben — Screen navigiert dann zurück. */
    val sent: Boolean = false
)

@HiltViewModel
class SmsComposeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sender: SmsSender,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SmsComposeUiState(
            recipient = savedStateHandle.get<String>(ARG_RECIPIENT) ?: "",
            body = savedStateHandle.get<String>(ARG_BODY) ?: ""
        )
    )
    val uiState: StateFlow<SmsComposeUiState> = _uiState.asStateFlow()

    fun setRecipient(value: String) = _uiState.update { it.copy(recipient = value) }
    fun setBody(value: String) = _uiState.update { it.copy(body = value) }
    fun consumeFeedback() = _uiState.update { it.copy(feedback = null) }

    fun send() {
        val current = _uiState.value
        if (current.sending) return
        viewModelScope.launch {
            _uiState.update { it.copy(sending = true) }
            val result = sender.send(current.recipient.trim(), current.body.trim())
            _uiState.update {
                it.copy(
                    sending = false,
                    feedback = smsSendFeedback(context, result),
                    sent = result is SmsSendResult.Enqueued
                )
            }
        }
    }
}
