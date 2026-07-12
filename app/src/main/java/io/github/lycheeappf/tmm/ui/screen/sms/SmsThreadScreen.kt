package io.github.lycheeappf.tmm.ui.screen.sms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.domain.sms.SmsDirection
import io.github.lycheeappf.tmm.domain.sms.SmsMessage
import io.github.lycheeappf.tmm.ui.component.MfsScaffold
import io.github.lycheeappf.tmm.ui.theme.MfsSpacing

// Konstant über alle Bubbles: Unterstreichung statt eigener Farbe, damit Links
// auf allen drei Bubble-Containern (surfaceVariant/primaryContainer/errorContainer)
// lesbar bleiben. Top-level statt lokal in MessageBubble, damit remember() den
// AnnotatedString nicht bei jeder Recomposition neu aufbaut (siehe unten).
private val linkStyle = SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Bold)

@Composable
fun SmsThreadScreen(
    onBack: () -> Unit,
    viewModel: SmsThreadViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    LaunchedEffect(state.feedback) {
        state.feedback?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeFeedback()
        }
    }

    // Beim Laden/neuen Nachrichten ans Ende (neueste) scrollen.
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex)
        }
    }

    MfsScaffold(
        title = state.title.ifBlank { stringResource(R.string.sms_thread_title_fallback) },
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    state.messages.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.sms_thread_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = MfsSpacing.lg,
                            vertical = MfsSpacing.md
                        ),
                        verticalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
                    ) {
                        items(state.messages, key = { it.id }) { message ->
                            MessageBubble(message)
                        }
                    }
                }
            }

            ReplyBar(
                draft = state.draft,
                sending = state.sending,
                onDraftChange = viewModel::setDraft,
                onSend = viewModel::send
            )
        }
    }
}

@Composable
private fun ReplyBar(
    draft: String,
    sending: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit
) {
    HorizontalDivider()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MfsSpacing.md, vertical = MfsSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.sms_thread_reply_hint)) },
            enabled = !sending
        )
        IconButton(
            onClick = onSend,
            enabled = draft.isNotBlank() && !sending
        ) {
            if (sending) {
                CircularProgressIndicator()
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.sms_thread_send_action))
            }
        }
    }
}

@Composable
internal fun MessageBubble(message: SmsMessage) {
    val incoming = message.isIncoming
    val failed = message.direction == SmsDirection.FAILED
    val outboxPending = message.direction == SmsDirection.OUTBOX

    val container = when {
        failed -> MaterialTheme.colorScheme.errorContainer
        incoming -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val onContainer = when {
        failed -> MaterialTheme.colorScheme.onErrorContainer
        incoming -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.align(if (incoming) Alignment.CenterStart else Alignment.CenterEnd),
            color = container,
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.padding(horizontal = MfsSpacing.md, vertical = MfsSpacing.sm)
            ) {
                // Links im Body tappbar machen (LinkAnnotation.Url → Default-UriHandler).
                // Unterstreichung statt eigener Farbe: bleibt auf allen drei
                // Bubble-Containern (surfaceVariant/primaryContainer/errorContainer) lesbar.
                val body = remember(message.body) { linkifySmsBody(message.body, linkStyle) }
                // Long-Press startet die native Teiltext-Selektion (z. B. 2FA-Code);
                // Kopieren übernimmt die System-Toolbar; Links bleiben per Tap öffenbar;
                // die Meta-Zeile bleibt bewusst unselektierbar.
                SelectionContainer {
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = onContainer
                    )
                }
                val failedLabel = stringResource(R.string.sms_thread_meta_failed)
                val sendingLabel = stringResource(R.string.sms_thread_meta_sending)
                val meta = buildString {
                    append(SmsFormat.clockTime(message.date))
                    when {
                        failed -> append(" · $failedLabel")
                        outboxPending -> append(" · $sendingLabel")
                    }
                }
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = onContainer
                )
            }
        }
    }
}
