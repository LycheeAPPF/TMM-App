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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lycheeappf.tmm.core.model.ChannelId
import io.github.lycheeappf.tmm.core.util.LogBuffer
import io.github.lycheeappf.tmm.data.db.MappingEntity
import io.github.lycheeappf.tmm.data.db.ReplyHistoryEntity
import io.github.lycheeappf.tmm.data.store.SettingsStore
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
        title = "Diagnose",
        onBack = onBack,
        actions = {
            if (state.exportInFlight) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
            }
            IconButton(
                onClick = { viewModel.exportDiagnostics() },
                enabled = !state.exportInFlight
            ) {
                Icon(Icons.Outlined.Download, contentDescription = "Export")
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
                    "Letzter Export: $path",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SenderResolutionCard(state = state, onTest = viewModel::runSenderResolutionTest)

            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Mappings") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Reply-Verlauf") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Live-Log") })
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
                    label = { Text(ch.label) }
                )
            }
        }
        if (state.mappings.isEmpty()) {
            EmptyHint("Keine Mappings für ${state.selectedChannel.label}. Wird befüllt, sobald Notifications eintreffen.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(MfsSpacing.sm)) {
                items(state.mappings, key = { it.mappingId }) { MappingRow(it, Modifier.animateItem()) }
            }
        }
    }
}

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
                Text("created: ${formatTs(entity.createdAt)}", style = MaterialTheme.typography.labelSmall)
                Text("expires: ${formatTs(entity.expiresAt)}", style = MaterialTheme.typography.labelSmall)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("replies: ${entity.replyCount}", style = MaterialTheme.typography.labelSmall)
                Text(
                    if (entity.replyable) "replyable" else "read-only",
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
        EmptyHint("Noch keine Reply-Versuche. Diktiere im Tesla eine Antwort, um diese Liste zu füllen.")
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
    val channelLabel = ChannelId.fromCode(entity.channel)?.label ?: "Unknown ${entity.channel}"
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
                "$channelLabel · mapping ${entity.mappingId}",
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
                label = { Text("Alle") }
            )
            FilterChip(
                selected = filterErrors,
                onClick = { filterErrors = true },
                label = { Text("Nur Warnungen/Fehler") }
            )
        }
        if (shown.isEmpty()) {
            EmptyHint("Log ist leer. Sobald Notifications oder Tesla-Antworten eintreffen, erscheinen hier die Events.")
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
                Text("Sender-Test", style = MaterialTheme.typography.titleSmall)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
                ) {
                    if (state.senderTestRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    }
                    io.github.lycheeappf.tmm.ui.component.PrimaryActionButton(
                        text = "Testen",
                        onClick = onTest,
                        loading = state.senderTestRunning
                    )
                }
            }
            Text(
                "Prüft, ob dein Tesla für die Grok-Adresse den Namen statt der Nummer bekommt. " +
                    "Es wird keine SMS gesendet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val test = state.senderTest
            if (test?.error != null) {
                StatusPill(text = "Fehler beim Test", status = MfsStatus.Error)
                Text(test.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else if (test?.result != null) {
                val r = test.result
                val (verdictLabel, verdictStatus) = when {
                    r.phoneLookupFound && r.resolvedName == test.displayName ->
                        "Tesla zeigt den Namen korrekt" to MfsStatus.Success
                    test.mode != SettingsStore.DISPLAY_NUMERIC ->
                        "Anzeige-Modus ist nicht 'Nur Nummer'" to MfsStatus.Warning
                    !r.hasWrite ->
                        "Schreibrecht für Kontakte fehlt" to MfsStatus.Warning
                    else ->
                        "Tesla zeigt nur die Nummer" to MfsStatus.Error
                }
                StatusPill(text = verdictLabel, status = verdictStatus)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showDetails = !showDetails }) {
                        Text(if (showDetails) "Details verbergen" else "Details")
                    }
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(buildSenderTestReport(state)))
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = "Kopieren")
                    }
                }

                AnimatedVisibility(visible = showDetails, enter = mfsExpandEnter(), exit = mfsExpandExit()) {
                    Column(verticalArrangement = Arrangement.spacedBy(MfsSpacing.xs)) {
                        HorizontalDivider()
                        Text(test.fakeAddress, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        KeyValueRow("Display-Modus", test.mode, ok = test.mode == SettingsStore.DISPLAY_NUMERIC)
                        KeyValueRow("READ_CONTACTS", if (r.hasRead) "ja" else "nein", ok = r.hasRead)
                        KeyValueRow("WRITE_CONTACTS", if (r.hasWrite) "ja" else "nein", ok = r.hasWrite)
                        KeyValueRow("Kontakte im Account", r.contactCount.toString(), ok = r.contactCount > 0)
                        KeyValueRow("Kontakt angelegt", if (r.upsertOk) "ja" else "nein", ok = r.upsertOk)
                        KeyValueRow("contactId (JOIN)", r.contactId?.toString() ?: "NULL", ok = r.contactId != null)
                        KeyValueRow(
                            "E164 (nur Abkürzung)",
                            r.computedE164 ?: if (r.phoneLookupFound) "null — egal (Lookup ok)" else "null",
                            ok = r.computedE164 != null || r.phoneLookupFound
                        )
                        KeyValueRow(
                            "PhoneLookup",
                            if (r.phoneLookupFound) "→ ${r.resolvedName}" else "nicht gefunden",
                            ok = r.phoneLookupFound && r.resolvedName == test.displayName
                        )
                        Text(
                            technicalVerdict(test.mode, r),
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

private fun technicalVerdict(
    mode: String,
    r: io.github.lycheeappf.tmm.contact.SenderResolutionResult
): String = when {
    mode != SettingsStore.DISPLAY_NUMERIC ->
        "Modus ist nicht 'Nur Nummer': Tesla zeigt die ADDRESS-Form direkt. Für sauberen Namen in Einstellungen auf 'Nur Nummer' stellen."
    !r.hasWrite ->
        "WRITE_CONTACTS fehlt — ohne das kann kein Kontakt angelegt werden."
    r.phoneLookupFound && r.resolvedName != null ->
        "Telefonseite OK. Falls Tesla weiter die Nummer zeigt: 'Tesla-Kontakte neu synchronisieren' + Bluetooth neu verbinden."
    r.contactId == null ->
        "contactId ist NULL. AGGREGATION_MODE_DISABLED erzeugt keine Aggregat-Contact-Zeile, daher bricht der PhoneLookup-JOIN."
    r.computedE164 == null ->
        "contactId OK, aber E164=null (Test-Range ohne gültigen Ländercode) → Strict-Match tot. Fix: Nummernschema."
    else ->
        "contactId+E164 OK, Lookup scheitert dennoch — Details im Live-Log (storedNorm / min_match)."
}

private fun buildSenderTestReport(state: DiagnosticsUiState): String {
    val test = state.senderTest ?: return ""
    val r = test.result ?: return "fakeAddress=${test.fakeAddress}\nerror=${test.error}"
    return buildString {
        appendLine("fakeAddress=${test.fakeAddress}")
        appendLine("displayName=${test.displayName}")
        appendLine("mode=${test.mode}")
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
