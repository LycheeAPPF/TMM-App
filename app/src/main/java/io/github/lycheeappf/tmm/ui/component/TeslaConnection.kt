package io.github.lycheeappf.tmm.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.domain.tesla.TeslaDevice
import io.github.lycheeappf.tmm.platform.bluetooth.PairedBtDevice
import io.github.lycheeappf.tmm.platform.bluetooth.TeslaDeviceStatus
import io.github.lycheeappf.tmm.platform.tesla.api.VehicleInfo
import io.github.lycheeappf.tmm.ui.theme.MfsSpacing

/** VIN ist PII — in der UI nur die letzten 4 Zeichen zeigen (wie im Diagnostics-Export). */
fun maskVin(vin: String): String = "…" + vin.takeLast(4)

/**
 * Karte „Tesla-Verbindung": zeigt die gewählten Tesla-Geräte (eins pro Zeile) und
 * steuert die Auswahl. Geteilt zwischen Einstellungen und Onboarding, damit beide
 * Stellen synchron bleiben.
 *
 * - Zeile: Name, verknüpftes Fahrzeug (VIN maskiert) bzw. Warnung „nicht mehr
 *   gekoppelt", Pill „Verbunden" (Snapshot beim Refresh) und ein Entfernen-Button.
 * - Sind ALLE Geräte entkoppelt, zusätzlich ein Hinweis — sonst würde
 *   stillschweigend alles gedroppt.
 * - Ohne Permission: Erteilen-Button, oder (bei dauerhaft verweigert) ein
 *   „App-Einstellungen öffnen"-Button, statt eines toten Erteilen-Buttons.
 * - Gewählte Geräte bleiben IMMER entfernbar (auch ohne Permission).
 */
@Composable
fun TeslaConnectionCard(
    devices: List<TeslaDeviceStatus>,
    hasPermission: Boolean,
    permanentlyDenied: Boolean,
    onGrantPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onSelectDevices: () -> Unit,
    onRemoveDevice: (address: String) -> Unit
) {
    SettingCard(
        title = stringResource(R.string.settings_tesla_conn_title),
        description = stringResource(R.string.settings_tesla_conn_desc)
    ) {
        if (devices.isEmpty()) {
            Text(
                stringResource(R.string.settings_tesla_conn_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column {
                devices.forEach { status ->
                    TeslaDeviceRow(status = status, onRemove = { onRemoveDevice(status.device.address) })
                }
            }
            if (devices.all { it.missing }) {
                Text(
                    stringResource(R.string.settings_tesla_conn_all_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
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
            TextButton(onClick = onSelectDevices) {
                Text(
                    if (devices.isNotEmpty()) stringResource(R.string.settings_tesla_conn_change)
                    else stringResource(R.string.settings_tesla_conn_select)
                )
            }
        }
    }
}

@Composable
private fun TeslaDeviceRow(status: TeslaDeviceStatus, onRemove: () -> Unit) {
    val device = status.device
    val vin = device.vin
    val subtitle = when {
        status.missing -> stringResource(R.string.settings_tesla_conn_missing)
        vin != null -> stringResource(R.string.settings_tesla_conn_linked_vehicle, maskVin(vin))
        else -> null
    }
    MfsListItem(
        title = device.name,
        subtitle = subtitle,
        subtitleColor = if (status.missing) MaterialTheme.colorScheme.error else null,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
            ) {
                if (status.connected) {
                    StatusPill(
                        text = stringResource(R.string.tesla_api_status_connected),
                        status = MfsStatus.Success
                    )
                }
                // Entfernen bleibt immer möglich — auch wenn die Permission später
                // entzogen wurde (sonst säße man auf einer toten Auswahl fest).
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.settings_tesla_conn_clear)
                    )
                }
            }
        }
    )
}

/**
 * Mehrfach-Auswahl der gekoppelten Geräte (Checkboxen), bestätigt per „Übernehmen".
 * Die lokale Auswahl wird aus [selectedAddresses] geseedet; [loading] zeigt einen
 * Lade-Hinweis, solange die (asynchrone) Geräteliste noch nicht da ist — sonst
 * blitzte beim Öffnen kurz fälschlich „keine Geräte" auf. [onConfirm] liefert die
 * angehakten Geräte in Listenreihenfolge.
 */
@Composable
fun TeslaDevicePickerDialog(
    devices: List<PairedBtDevice>,
    selectedAddresses: Set<String>,
    loading: Boolean,
    onConfirm: (List<PairedBtDevice>) -> Unit,
    onCancel: () -> Unit
) {
    // Nur `remember`: der Dialog ist transient (sein Sichtbarkeits-Flag im Screen ist es auch).
    var checked by remember(selectedAddresses) {
        mutableStateOf(selectedAddresses.map { it.uppercase() }.toSet())
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.settings_tesla_conn_dialog_title)) },
        text = {
            when {
                loading -> Text(stringResource(R.string.settings_tesla_conn_dialog_loading))
                devices.isEmpty() -> Text(stringResource(R.string.settings_tesla_conn_dialog_empty))
                else -> Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    devices.forEach { device ->
                        val address = device.address.uppercase()
                        val isChecked = address in checked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = isChecked,
                                    role = Role.Checkbox,
                                    onValueChange = { now -> checked = if (now) checked + address else checked - address }
                                )
                                .padding(vertical = MfsSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
                        ) {
                            Checkbox(checked = isChecked, onCheckedChange = null)
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(devices.filter { it.address.uppercase() in checked }) },
                enabled = !loading
            ) {
                Text(stringResource(R.string.settings_tesla_conn_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.settings_tesla_conn_dialog_cancel))
            }
        }
    )
}

/**
 * Ordnet ein Fleet-API-Fahrzeug einem der gewählten Tesla-Geräte zu (oder hebt die
 * Zuordnung auf). Der Radio-Zustand spiegelt die TATSÄCHLICHE Verknüpfung; ein
 * Gerät, dessen Bluetooth-Name dem Fahrzeugnamen entspricht (Tesla nutzt den
 * Autonamen als BT-Namen), bekommt nur einen Hinweis. Tippen bestätigt sofort.
 */
@Composable
fun TeslaVehicleLinkDialog(
    vehicle: VehicleInfo,
    devices: List<TeslaDevice>,
    onLink: (address: String?) -> Unit,
    onCancel: () -> Unit
) {
    val linkedAddress = devices.firstOrNull { it.vin == vehicle.vin }?.address
    val vehicleLabel = vehicle.displayName.ifBlank { maskVin(vehicle.vin) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.tesla_api_link_dialog_title, vehicleLabel)) },
        text = {
            Column(
                modifier = Modifier
                    .selectableGroup()
                    .verticalScroll(rememberScrollState())
            ) {
                LinkOption(
                    label = stringResource(R.string.tesla_api_link_none),
                    hint = null,
                    selected = linkedAddress == null,
                    onClick = { onLink(null) }
                )
                devices.forEach { device ->
                    val nameMatches = vehicle.displayName.isNotBlank() &&
                        device.name.equals(vehicle.displayName, ignoreCase = true)
                    LinkOption(
                        label = device.name,
                        hint = if (nameMatches) stringResource(R.string.tesla_api_link_name_match) else null,
                        selected = linkedAddress != null && device.sameAddress(linkedAddress),
                        onClick = { onLink(device.address) }
                    )
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

@Composable
private fun LinkOption(label: String, hint: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = MfsSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
    ) {
        RadioButton(selected = selected, onClick = null)
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
