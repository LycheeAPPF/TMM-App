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
 * Ohne BLUETOOTH_CONNECT-Permission gibt es nur den Erteilen-Button. Geteilt
 * zwischen Einstellungen und Onboarding, damit beide Stellen synchron bleiben.
 */
@Composable
fun TeslaConnectionCard(
    deviceName: String?,
    hasPermission: Boolean,
    onGrantPermission: () -> Unit,
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
        if (!hasPermission) {
            Text(
                stringResource(R.string.settings_tesla_conn_perm_missing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            PrimaryActionButton(
                text = stringResource(R.string.settings_tesla_conn_grant),
                onClick = onGrantPermission
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onSelectDevice) {
                    Text(
                        if (deviceName != null) stringResource(R.string.settings_tesla_conn_change)
                        else stringResource(R.string.settings_tesla_conn_select)
                    )
                }
                if (deviceName != null) {
                    TextButton(onClick = onClearDevice) {
                        Text(stringResource(R.string.settings_tesla_conn_clear))
                    }
                }
            }
        }
    }
}

/** Auswahl-Dialog der gekoppelten Geräte; Tippen auf ein Gerät bestätigt sofort. */
@Composable
fun TeslaDevicePickerDialog(
    devices: List<PairedBtDevice>,
    selectedAddress: String?,
    onSelect: (PairedBtDevice) -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.settings_tesla_conn_dialog_title)) },
        text = {
            if (devices.isEmpty()) {
                Text(stringResource(R.string.settings_tesla_conn_dialog_empty))
            } else {
                Column(modifier = Modifier.selectableGroup()) {
                    devices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = device.address == selectedAddress,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(device) }
                                )
                                .padding(vertical = MfsSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
                        ) {
                            RadioButton(selected = device.address == selectedAddress, onClick = null)
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
