package io.github.lycheeappf.tmm.ui.screen.diagnostics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.data.db.MappingEntity
import io.github.lycheeappf.tmm.data.db.ReplyHistoryEntity
import io.github.lycheeappf.tmm.ui.component.MfsStatus
import io.github.lycheeappf.tmm.ui.component.StatusPill
import io.github.lycheeappf.tmm.ui.component.accentColor
import io.github.lycheeappf.tmm.ui.component.mfsExpandEnter
import io.github.lycheeappf.tmm.ui.component.mfsExpandExit
import io.github.lycheeappf.tmm.ui.theme.MfsColors
import io.github.lycheeappf.tmm.ui.theme.MfsSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }

    io.github.lycheeappf.tmm.ui.component.MfsScaffold(
        title = stringResource(R.string.diagnostics_title),
        onBack = onBack,
        actions = {
            if (state.exportInFlight) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = { viewModel.exportDiagnostics() },
                enabled = !state.exportInFlight
            ) {
                Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.diagnostics_export_action))
            }
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = MfsSpacing.xl, vertical = MfsSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MfsSpacing.md)
        ) {
            state.lastExportPath?.let { path ->
                Text(
                    stringResource(R.string.diagnostics_last_export, path),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SenderResolutionCard(state = state, onTest = viewModel::runSenderResolutionTest)

            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.diagnostics_tab_mappings)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.diagnostics_tab_reply_history)) })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.diagnostics_tab_live_log)) })
            }

            AnimatedContent(targetState = tab, label = "diagTab") { current ->
                when (current) {
                    0 -> MappingsTab(state, viewModel::selectChannel)
                    1 -> ReplyHistoryTab(state.replyHistory)
                    else -> {
                        // Logs nur in diesem Tab sammeln (separater Flow, siehe ViewModel).
                        val logs by viewModel.logs.collectAsStateWithLifecycle()
                        LogTab(logs)
                    }
                }
            }
        }
    }
}

@Composable
private fun MappingsTab(state: DiagnosticsUiState, onSelectChannel: (ChannelId) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MfsSpacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)) {
            ChannelId.entries.forEach { ch ->
                FilterChip(
                    selected = state.selectedChannel == ch,
                    onClick = { onSelectChannel(ch) },
                    label = { Text(channelLabel(ch)) }
                )
            }
        }
        if (state.mappings.isEmpty()) {
            EmptyHint(stringResource(R.string.diagnostics_mappings_empty, channelLabel(state.selectedChannel)))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(MfsSpacing.sm)) {
                items(state.mappings, key = { it.mappingId }) { MappingRow(it, Modifier.animateItem()) }
            }
        }
    }
}

/** Lokalisierter Channel-Anzeigename (ChannelId bleibt Android-frei). */
@Composable
private fun channelLabel(id: ChannelId): String = stringResource(
    when (id) {
        ChannelId.NOTIFICATION -> R.string.channel_notification_label
        ChannelId.LLM -> R.string.channel_llm_label
        ChannelId.SYSTEM -> R.string.channel_system_label
    }
)

@Composable
private fun MappingRow(entity: MappingEntity, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MfsSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MfsSpacing.xs)
        ) {
            Text(
                entity.fakeAddress,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                entity.conversationKey,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(R.string.diagnostics_mapping_created, formatTs(entity.createdAt)),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    stringResource(R.string.diagnostics_mapping_expires, formatTs(entity.expiresAt)),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(R.string.diagnostics_mapping_replies, entity.replyCount),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    if (entity.replyable) stringResource(R.string.diagnostics_mapping_replyable)
                    else stringResource(R.string.diagnostics_mapping_read_only),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entity.replyable) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun ReplyHistoryTab(history: List<ReplyHistoryEntity>) {
    if (history.isEmpty()) {
        EmptyHint(stringResource(R.string.diagnostics_reply_history_empty))
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(MfsSpacing.sm)) {
            items(history, key = { it.id }) { ReplyHistoryRow(it, Modifier.animateItem()) }
        }
    }
}

@Composable
private fun ReplyHistoryRow(entity: ReplyHistoryEntity, modifier: Modifier = Modifier) {
    val resultStatus = when (entity.result) {
        "SUCCESS" -> MfsStatus.Success
        "PI_CANCELED", "EXPIRED", "NO_ACTION" -> MfsStatus.Error
        else -> MfsStatus.Neutral
    }
    val channelText = ChannelId.fromCode(entity.channel)?.let { channelLabel(it) }
        ?: stringResource(R.string.diagnostics_reply_history_unknown_channel, entity.channel)
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MfsSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MfsSpacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    entity.result,
                    style = MaterialTheme.typography.labelMedium,
                    color = resultStatus.accentColor()
                )
                Text(formatTs(entity.attemptedAt), style = MaterialTheme.typography.labelSmall)
            }
            Text(entity.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            Text(
                "$channelText · mapping ${entity.mappingId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace
            )
            entity.errorDetail?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun LogTab(logs: List<LogBuffer.LogEntry>) {
    var filterErrors by remember { mutableStateOf(false) }
    val shown = if (filterErrors) {
        logs.filter { it.level == LogBuffer.Level.Warn || it.level == LogBuffer.Level.Error }
    } else logs

    Column(verticalArrangement = Arrangement.spacedBy(MfsSpacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)) {
            FilterChip(
                selected = !filterErrors,
                onClick = { filterErrors = false },
                label = { Text(stringResource(R.string.diagnostics_log_filter_all)) }
            )
            FilterChip(
                selected = filterErrors,
                onClick = { filterErrors = true },
                label = { Text(stringResource(R.string.diagnostics_log_filter_warnings_errors)) }
            )
        }
        if (shown.isEmpty()) {
            EmptyHint(stringResource(R.string.diagnostics_log_empty))
        } else {
            // Kein content-basierter key: Burst-Logs können dieselbe Millisekunde +
            // Tag + message.hashCode() teilen → doppelte LazyColumn-Keys → Compose-
            // Crash. Position als Key (Default ohne key-Lambda) ist hier eindeutig;
            // das Tail wächst nur vorne, eine Item-Identität über Zeit ist unnötig.
            LazyColumn(verticalArrangement = Arrangement.spacedBy(MfsSpacing.xs)) {
                items(shown) { LogRow(it) }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogBuffer.LogEntry) {
    val color = when (entry.level) {
        LogBuffer.Level.Error -> MaterialTheme.colorScheme.error
        LogBuffer.Level.Warn -> MfsColors.status.warning
        LogBuffer.Level.Info -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
    ) {
        Text(
            entry.formattedTime(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            entry.tag,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline
        )
        Text(entry.message, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SenderResolutionCard(state: DiagnosticsUiState, onTest: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var showDetails by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MfsSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.diagnostics_sender_test_title), style = MaterialTheme.typography.titleSmall)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
                ) {
                    if (state.senderTestRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    }
                    io.github.lycheeappf.tmm.ui.component.PrimaryActionButton(
                        text = stringResource(R.string.diagnostics_sender_test_action),
                        onClick = onTest,
                        loading = state.senderTestRunning
                    )
                }
            }
            Text(
                stringResource(R.string.diagnostics_sender_test_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val test = state.senderTest
            if (test?.error != null) {
                StatusPill(text = stringResource(R.string.diagnostics_sender_test_error), status = MfsStatus.Error)
                Text(test.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else if (test?.result != null) {
                val r = test.result
                val (verdictLabel, verdictStatus) = when {
                    r.phoneLookupFound && r.resolvedName == test.displayName ->
                        stringResource(R.string.diagnostics_sender_test_verdict_name_ok) to MfsStatus.Success
                    !r.hasWrite ->
                        stringResource(R.string.diagnostics_sender_test_verdict_no_write) to MfsStatus.Warning
                    else ->
                        stringResource(R.string.diagnostics_sender_test_verdict_number_only) to MfsStatus.Error
                }
                StatusPill(text = verdictLabel, status = verdictStatus)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showDetails = !showDetails }) {
                        Text(
                            if (showDetails) stringResource(R.string.diagnostics_sender_test_details_hide)
                            else stringResource(R.string.diagnostics_sender_test_details_show)
                        )
                    }
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(buildSenderTestReport(state)))
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.diagnostics_sender_test_copy))
                    }
                }

                AnimatedVisibility(visible = showDetails, enter = mfsExpandEnter(), exit = mfsExpandExit()) {
                    val yes = stringResource(R.string.diagnostics_sender_test_yes)
                    val no = stringResource(R.string.diagnostics_sender_test_no)
                    Column(verticalArrangement = Arrangement.spacedBy(MfsSpacing.xs)) {
                        HorizontalDivider()
                        Text(test.fakeAddress, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        KeyValueRow("READ_CONTACTS", if (r.hasRead) yes else no, ok = r.hasRead)
                        KeyValueRow("WRITE_CONTACTS", if (r.hasWrite) yes else no, ok = r.hasWrite)
                        KeyValueRow(stringResource(R.string.diagnostics_sender_test_contacts_in_account), r.contactCount.toString(), ok = r.contactCount > 0)
                        KeyValueRow(stringResource(R.string.diagnostics_sender_test_contact_created), if (r.upsertOk) yes else no, ok = r.upsertOk)
                        KeyValueRow("contactId (JOIN)", r.contactId?.toString() ?: "NULL", ok = r.contactId != null)
                        KeyValueRow(
                            stringResource(R.string.diagnostics_sender_test_e164_label),
                            r.computedE164 ?: if (r.phoneLookupFound) stringResource(R.string.diagnostics_sender_test_e164_null_irrelevant)
                            else stringResource(R.string.diagnostics_sender_test_e164_null),
                            ok = r.computedE164 != null || r.phoneLookupFound
                        )
                        KeyValueRow(
                            "PhoneLookup",
                            if (r.phoneLookupFound) stringResource(R.string.diagnostics_sender_test_phonelookup_found, r.resolvedName ?: "")
                            else stringResource(R.string.diagnostics_sender_test_phonelookup_not_found),
                            ok = r.phoneLookupFound && r.resolvedName == test.displayName
                        )
                        Text(
                            technicalVerdict(r),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyValueRow(label: String, value: String, ok: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = if (ok) MfsColors.status.success else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = MfsSpacing.lg)
    )
}

@Composable
private fun technicalVerdict(
    r: io.github.lycheeappf.tmm.contact.SenderResolutionResult
): String = when {
    !r.hasWrite ->
        stringResource(R.string.diagnostics_verdict_no_write)
    r.phoneLookupFound && r.resolvedName != null ->
        stringResource(R.string.diagnostics_verdict_phone_ok)
    r.contactId == null ->
        stringResource(R.string.diagnostics_verdict_contact_id_null)
    r.computedE164 == null ->
        stringResource(R.string.diagnostics_verdict_e164_null)
    else ->
        stringResource(R.string.diagnostics_verdict_lookup_fails)
}

private fun buildSenderTestReport(state: DiagnosticsUiState): String {
    val test = state.senderTest ?: return ""
    val r = test.result ?: return "fakeAddress=${test.fakeAddress}\nerror=${test.error}"
    return buildString {
        appendLine("fakeAddress=${test.fakeAddress}")
        appendLine("displayName=${test.displayName}")
        appendLine("hasRead=${r.hasRead}")
        appendLine("hasWrite=${r.hasWrite}")
        appendLine("contactCount=${r.contactCount}")
        appendLine("upsertOk=${r.upsertOk}")
        appendLine("contactId=${r.contactId}")
        appendLine("computedE164=${r.computedE164}")
        appendLine("phoneLookupFound=${r.phoneLookupFound}")
        appendLine("resolvedName=${r.resolvedName}")
    }
}

private val tsFormat = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY)
private fun formatTs(ts: Long): String = tsFormat.format(Date(ts))
