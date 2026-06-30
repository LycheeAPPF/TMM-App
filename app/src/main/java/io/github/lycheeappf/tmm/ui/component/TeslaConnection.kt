package io.github.lycheeappf.tmm.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.platform.bluetooth.PairedBtDevice
import io.github.lycheeappf.tmm.ui.theme.MfsSpacing

/**
 * Karte „Tesla-Verbindung": zeigt das gewählte Gerät und steuert die Auswahl.
 * Geteilt zwischen Einstellungen und Onboarding, damit beide Stellen synchron bleiben.
 *
 * - [deviceMissing]: das gespeicherte Gerät ist nicht mehr unter den gekoppelten
 *   Geräten (entkoppelt) → Warnhinweis, sonst würde stillschweigend alles gedroppt.
 * - Ohne Permission: Erteilen-Button, oder (bei dauerhaft verweigert) ein
 *   „App-Einstellungen öffnen"-Button, statt eines toten Erteilen-Buttons.
 * - Das gewählte Gerät bleibt IMMER entfernbar (auch ohne Permission).
 */
@Composable
fun TeslaConnectionCard(
    deviceName: String?,
    deviceMissing: Boolean,
    hasPermission: Boolean,
    permanentlyDenied: Boolean,
    onGrantPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onSelectDevice: () -> Unit,
    onClearDevice: () -> Unit
) {
    SettingCard(
        title = stringResource(R.string.settings_tesla_conn_title),
        description = stringResource(R.string.settings_tesla_conn_desc)
    ) {
        Text(
            if (deviceName != null) stringResource(R.string.settings_tesla_conn_selected, deviceName)
            else stringResource(R.string.settings_tesla_conn_none),
            style = MaterialTheme.typography.bodyMedium,
            color = if (deviceName != null) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (deviceMissing) {
            Text(
                stringResource(R.string.settings_tesla_conn_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (!hasPermission) {
            Text(
                stringResource(R.string.settings_tesla_conn_perm_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            if (permanentlyDenied) {
                PrimaryActionButton(
                    text = stringResource(R.string.settings_tesla_conn_open_settings),
                    onClick = onOpenAppSettings
                )
            } else {
                PrimaryActionButton(
                    text = stringResource(R.string.settings_tesla_conn_grant),
                    onClick = onGrantPermission
                )
            }
        } else {
            TextButton(onClick = onSelectDevice) {
                Text(
                    if (deviceName != null) stringResource(R.string.settings_tesla_conn_change)
                    else stringResource(R.string.settings_tesla_conn_select)
                )
            }
        }
        // Gewähltes Gerät bleibt immer entfernbar — auch wenn die Permission später
        // entzogen wurde (sonst säße man auf einer toten Auswahl fest).
        if (deviceName != null) {
            TextButton(onClick = onClearDevice) {
                Text(stringResource(R.string.settings_tesla_conn_clear))
            }
        }
    }
}

/**
 * Auswahl-Dialog der gekoppelten Geräte; Tippen auf ein Gerät bestätigt sofort.
 * [selectedAddress] hebt das aktuell gewählte Gerät hervor. [loading] zeigt einen
 * Lade-Hinweis, solange die (asynchrone) Geräteliste noch nicht da ist — sonst
 * blitzte beim Öffnen kurz fälschlich „keine Geräte" auf.
 */
@Composable
fun TeslaDevicePickerDialog(
    devices: List<PairedBtDevice>,
    selectedAddress: String?,
    loading: Boolean,
    onSelect: (PairedBtDevice) -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.settings_tesla_conn_dialog_title)) },
        text = {
            when {
                loading -> Text(stringResource(R.string.settings_tesla_conn_dialog_loading))
                devices.isEmpty() -> Text(stringResource(R.string.settings_tesla_conn_dialog_empty))
                else -> Column(modifier = Modifier.selectableGroup()) {
                    devices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = device.address.equals(selectedAddress, ignoreCase = true),
                                    role = Role.RadioButton,
                                    onClick = { onSelect(device) }
                                )
                                .padding(vertical = MfsSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
                        ) {
                            RadioButton(
                                selected = device.address.equals(selectedAddress, ignoreCase = true),
                                onClick = null
                            )
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.settings_tesla_conn_dialog_cancel))
            }
        }
    )
}
