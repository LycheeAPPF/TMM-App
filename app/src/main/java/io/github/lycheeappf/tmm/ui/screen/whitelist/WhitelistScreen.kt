package io.github.lycheeappf.tmm.ui.screen.whitelist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.ui.component.MfsListItem
import io.github.lycheeappf.tmm.ui.component.MfsScaffold
import io.github.lycheeappf.tmm.ui.theme.MfsSpacing

@Composable
fun WhitelistScreen(
    onBack: () -> Unit,
    viewModel: WhitelistViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val filtered = state.items
        .filter { state.showSystemApps || !it.isSystemApp }
        .filter {
            state.filter.isBlank() ||
                it.displayName.contains(state.filter, ignoreCase = true) ||
                it.packageName.contains(state.filter, ignoreCase = true)
        }

    MfsScaffold(title = stringResource(R.string.whitelist_title), onBack = onBack) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = MfsSpacing.xl, vertical = MfsSpacing.md),
            verticalArrangement = Arrangement.spacedBy(MfsSpacing.md)
        ) {
            Text(
                stringResource(R.string.whitelist_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = state.filter,
                onValueChange = viewModel::setFilter,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.whitelist_search_hint)) },
                singleLine = true
            )

            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
            ) {
                FilterChip(
                    selected = state.showSystemApps,
                    onClick = { viewModel.toggleSystemApps() },
                    label = { Text(stringResource(R.string.whitelist_show_system_apps_label)) }
                )
                Text(
                    pluralStringResource(
                        R.plurals.whitelist_visible_count,
                        filtered.size,
                        filtered.size
                    ),
                    style = MaterialTheme.typography.labelMedium
                )
            }

            when {
                state.loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(top = MfsSpacing.xxl),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                filtered.isEmpty() -> Text(
                    stringResource(R.string.whitelist_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MfsSpacing.lg)
                )

                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = MfsSpacing.listBottom)
                ) {
                    itemsIndexed(filtered, key = { _, item -> item.packageName }) { index, item ->
                        MfsListItem(
                            title = item.displayName,
                            subtitle = item.packageName,
                            subtitleMonospace = true,
                            trailing = {
                                Switch(
                                    checked = item.isWhitelisted,
                                    onCheckedChange = { value ->
                                        viewModel.setWhitelisted(item.packageName, value)
                                    }
                                )
                            },
                            modifier = Modifier.animateItem()
                        )
                        if (index < filtered.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }
}
