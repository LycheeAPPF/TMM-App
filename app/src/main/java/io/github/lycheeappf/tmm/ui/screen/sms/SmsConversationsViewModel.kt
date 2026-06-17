package io.github.lycheeappf.tmm.ui.screen.sms

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lycheeappf.tmm.core.di.IoDispatcher
import io.github.lycheeappf.tmm.domain.sms.SmsConversation
import io.github.lycheeappf.tmm.domain.sms.SmsInboxReader
import io.github.lycheeappf.tmm.platform.permission.PermissionGate
import io.github.lycheeappf.tmm.platform.role.DefaultSmsRoleManager
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

data class SmsConversationsUiState(
    val items: List<SmsConversation> = emptyList(),
    val loading: Boolean = true,
    val isDefaultSmsApp: Boolean = true,
    val hasReadSms: Boolean = true
)

@HiltViewModel
class SmsConversationsViewModel @Inject constructor(
    private val reader: SmsInboxReader,
    private val roleManager: DefaultSmsRoleManager,
    private val permissionGate: PermissionGate,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    private val _uiState = MutableStateFlow(SmsConversationsUiState())
    val uiState: StateFlow<SmsConversationsUiState> = _uiState.asStateFlow()

    init { observeChanges() }

    @OptIn(FlowPreview::class)
    private fun observeChanges() {
        viewModelScope.launch {
            // Initialer Load sofort (onStart), danach content://sms-Änderungen debounced.
            reader.changes()
                .debounce(CHANGE_DEBOUNCE_MS)
                .onStart { emit(Unit) }
                .collect { reload() }
        }
    }

    /** Aus LifecycleResumeEffect: Permission/Default-Status frisch prüfen + neu laden. */
    fun refresh() {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        val isDefault = withContext(ioDispatcher) { roleManager.isDefault() }
        val canRead = permissionGate.hasReadSms()
        if (!canRead) {
            _uiState.update {
                it.copy(loading = false, isDefaultSmsApp = isDefault, hasReadSms = false, items = emptyList())
            }
            return
        }
        _uiState.update { it.copy(loading = it.items.isEmpty(), isDefaultSmsApp = isDefault, hasReadSms = true) }
        val items = withContext(ioDispatcher) { reader.loadConversations() }
        _uiState.update {
            it.copy(items = items, loading = false, isDefaultSmsApp = isDefault, hasReadSms = true)
        }
    }

    /** Intent zum Anfordern der Standard-SMS-Rolle (oder null vor Android Q). */
    fun defaultSmsIntent(): Intent? = roleManager.createRequestIntent()

    companion object {
        private const val CHANGE_DEBOUNCE_MS = 250L
    }
}
