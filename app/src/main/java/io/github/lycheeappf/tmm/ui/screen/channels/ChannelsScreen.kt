package io.github.lycheeappf.tmm.ui.screen.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.core.model.AddressScheme
import io.github.lycheeappf.tmm.ui.component.MfsScaffold
import io.github.lycheeappf.tmm.ui.component.MfsStatus
import io.github.lycheeappf.tmm.ui.component.SectionHeader
import io.github.lycheeappf.tmm.ui.component.StatusPill
import io.github.lycheeappf.tmm.ui.theme.MfsSpacing

@Composable
fun ChannelsScreen(
    onBack: () -> Unit,
    viewModel: ChannelsViewModel = hiltViewModel()
) {
    val rows = remember { viewModel.rows() }

    MfsScaffold(title = stringResource(R.string.nav_channels_label), onBack = onBack) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MfsSpacing.xl, vertical = MfsSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MfsSpacing.lg)
        ) {
            Text(
                stringResource(R.string.channels_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            rows.forEach { row -> ChannelCard(row) }

            SectionHeader(stringResource(R.string.channels_address_scheme_header))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.channels_address_scheme_diagram),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(MfsSpacing.md)
                )
            }
        }
    }
}

@Composable
private fun ChannelCard(row: ChannelRow) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MfsSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
            ) {
                Text(
                    row.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                StatusPill(
                    text = if (row.isReserved) stringResource(R.string.channels_status_reserved)
                    else if (row.isRegistered) stringResource(R.string.channels_status_active)
                    else stringResource(R.string.channels_status_channel, row.id.code),
                    status = if (row.isRegistered) MfsStatus.Success else MfsStatus.Neutral
                )
            }
            Text(
                row.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(
                    R.string.channels_address_line,
                    AddressScheme.Itu888.displayLabel,
                    AddressScheme.Itu888.prefix,
                    row.id.code
                ),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
