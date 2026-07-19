package io.github.lycheeappf.tmm.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.lycheeappf.tmm.domain.sms.SmsInboxReader
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Liefert die Anzahl ungelesener echter SMS für das Badge am SMS-Item der
 * Bottom-Bar. Alle Trigger (Provider-Änderungen, initialer Load, [refresh])
 * laufen durch EINEN gemergten Flow mit einem einzigen Collector — die
 * Zähl-Queries sind dadurch serialisiert (keine Out-of-order-Races), und
 * `onStart` liegt vor `debounce`, sodass der ContentObserver bereits
 * registriert ist, während die erste Query läuft.
 */
@HiltViewModel
class UnreadBadgeViewModel @Inject constructor(
    private val reader: SmsInboxReader
) : ViewModel() {

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        observeTriggers()
    }

    @OptIn(FlowPreview::class)
    private fun observeTriggers() {
        viewModelScope.launch {
            merge(reader.changes(), refreshRequests)
                .onStart { emit(Unit) }
                .debounce(CHANGE_DEBOUNCE_MS)
                .collect { _unreadCount.value = reader.unreadCount() }
        }
    }

    /** Aus LifecycleResumeEffect: Count neu laden (z. B. frisch erteilte Permission). */
    fun refresh() {
        refreshRequests.tryEmit(Unit)
    }

    companion object {
        private const val CHANGE_DEBOUNCE_MS = 250L

        /** Badge-Text: ab 100 gekappt auf „99+" (M3-Konvention). */
        internal fun formatBadgeCount(count: Int): String =
            if (count > MAX_BADGE_COUNT) "$MAX_BADGE_COUNT+" else count.toString()

        private const val MAX_BADGE_COUNT = 99
    }
}
