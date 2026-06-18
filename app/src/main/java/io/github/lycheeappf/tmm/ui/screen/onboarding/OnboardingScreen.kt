package io.github.lycheeappf.tmm.ui.screen.onboarding

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.data.store.SettingsStore
import io.github.lycheeappf.tmm.ui.component.MfsMotion
import io.github.lycheeappf.tmm.ui.component.MfsScaffold
import io.github.lycheeappf.tmm.ui.component.SettingCard
import io.github.lycheeappf.tmm.ui.component.StatusPill
import io.github.lycheeappf.tmm.ui.component.StepCard
import io.github.lycheeappf.tmm.ui.component.mfsExpandEnter
import io.github.lycheeappf.tmm.ui.component.mfsExpandExit
import io.github.lycheeappf.tmm.ui.component.preflightStatusUi
import io.github.lycheeappf.tmm.ui.theme.MfsSpacing

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    onOpenWhitelist: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showPreFlightDialog by remember { mutableStateOf(false) }

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refresh() }
    val postNotifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refresh() }
    val contactsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        if (granted) viewModel.onContactsPermissionGranted() else viewModel.refresh()
    }
    val nlsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refresh() }

    if (showPreFlightDialog) {
        PreFlightConfirmDialog(
            targetAddress = state.preflightTargetAddress,
            onConfirm = {
                showPreFlightDialog = false
                viewModel.runPreFlight()
            },
            onCancel = { showPreFlightDialog = false }
        )
    }

    val isPreflightRisk = state.preflightStatus == SettingsStore.PREFLIGHT_RISK
    val canComplete = state.currentStep == OnboardingStep.Done

    MfsScaffold(title = stringResource(R.string.onboarding_screen_title)) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MfsSpacing.xl, vertical = MfsSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MfsSpacing.lg)
        ) {
            Text(
                stringResource(R.string.onboarding_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            StepIndicator(state.currentStep)

            StepCard(
                number = 1,
                title = stringResource(R.string.onboarding_title_role),
                description = stringResource(R.string.onboarding_step_role_desc),
                done = state.isDefaultSmsApp
            ) {
                Button(onClick = { roleLauncher.launch(viewModel.defaultSmsIntent()) }) {
                    Text(stringResource(R.string.onboarding_step_role_action))
                }
                if (!state.isDefaultSmsApp && state.ourPackage.isNotBlank()) {
                    DefaultSmsDiagnostic(
                        roleManagerHeld = state.roleManagerHeld,
                        telephonyMatches = state.telephonyMatches,
                        systemDefault = state.currentDefaultPackage,
                        ourPackage = state.ourPackage
                    )
                }
            }

            StepCard(
                number = 2,
                title = stringResource(R.string.onboarding_step_listener_title),
                description = stringResource(R.string.onboarding_step_listener_desc),
                done = state.hasNotificationAccess
            ) {
                Button(onClick = {
                    nlsLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }) { Text(stringResource(R.string.onboarding_step_listener_action)) }
            }

            StepCard(
                number = 3,
                title = stringResource(R.string.onboarding_step_post_title),
                description = stringResource(R.string.onboarding_step_post_desc),
                done = state.hasPostNotifications
            ) {
                Button(onClick = {
                    postNotifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }) { Text(stringResource(R.string.onboarding_step_post_action)) }
            }

            StepCard(
                number = 4,
                title = stringResource(R.string.onboarding_step_contacts_title),
                description = stringResource(R.string.onboarding_step_contacts_desc),
                done = state.hasContactsAccess || state.contactsSkipped
            ) {
                if (!state.hasContactsAccess) {
                    Button(onClick = {
                        contactsLauncher.launch(
                            arrayOf(
                                android.Manifest.permission.READ_CONTACTS,
                                android.Manifest.permission.WRITE_CONTACTS
                            )
                        )
                    }) { Text(stringResource(R.string.onboarding_step_contacts_action)) }
                    TextButton(onClick = { viewModel.skipContactsStep() }) {
                        Text(stringResource(R.string.onboarding_step_contacts_skip))
                    }
                } else {
                    Text(
                        stringResource(R.string.onboarding_step_contacts_granted),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            StepCard(
                number = 5,
                title = stringResource(R.string.onboarding_step_preflight_title),
                description = stringResource(
                    R.string.onboarding_step_preflight_desc,
                    state.preflightTargetAddress
                ),
                done = state.preflightStatus == SettingsStore.PREFLIGHT_OK ||
                    (state.preflightStatus == SettingsStore.PREFLIGHT_RISK && state.riskAcknowledged)
            ) {
                when {
                    state.preflightRunning -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.onboarding_step_preflight_running))
                    }
                    state.preflightStatus == null -> Button(onClick = { showPreFlightDialog = true }) {
                        Text(stringResource(R.string.onboarding_action_run_preflight))
                    }
                    else -> Column(verticalArrangement = Arrangement.spacedBy(MfsSpacing.sm)) {
                        val (label, status) = preflightStatusUi(state.preflightStatus)
                        StatusPill(text = label, status = status)
                        OutlinedButton(onClick = { showPreFlightDialog = true }) {
                            Text(stringResource(R.string.onboarding_step_preflight_retry))
                        }
                    }
                }
            }

            AnimatedVisibility(visible = isPreflightRisk, enter = mfsExpandEnter(), exit = mfsExpandExit()) {
                RiskBanner(
                    acknowledged = state.riskAcknowledged,
                    targetAddress = state.preflightTargetAddress,
                    onAcknowledge = { viewModel.acknowledgeRisk() }
                )
            }

            // Optionaler Schritt: jetzt schon wählen, welche Messenger-Apps ans Tesla
            // gehen. Standardmäßig ist keine App aktiv. Nicht erforderlich, um das
            // Setup abzuschließen — der Grok-Assistent läuft auch ohne freigegebene App.
            SettingCard(
                title = stringResource(R.string.onboarding_title_whitelist),
                description = stringResource(R.string.onboarding_body_whitelist)
            ) {
                OutlinedButton(onClick = onOpenWhitelist) {
                    Text(stringResource(R.string.onboarding_action_configure_whitelist))
                }
            }

            Button(
                enabled = canComplete,
                onClick = { viewModel.markOnboarded(onFinished) },
                modifier = Modifier.fillMaxWidth().padding(top = MfsSpacing.sm)
            ) {
                Text(stringResource(R.string.onboarding_finish_action))
            }
        }
    }
}

@Composable
private fun PreFlightConfirmDialog(
    targetAddress: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
        title = { Text(stringResource(R.string.onboarding_preflight_dialog_title)) },
        text = {
            Text(stringResource(R.string.onboarding_preflight_dialog_text, targetAddress))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.onboarding_preflight_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.onboarding_preflight_dialog_cancel))
            }
        }
    )
}

@Composable
private fun RiskBanner(
    acknowledged: Boolean,
    targetAddress: String,
    onAcknowledge: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier.padding(MfsSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
            ) {
                Icon(
                    Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    stringResource(R.string.onboarding_risk_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                stringResource(R.string.onboarding_risk_body, targetAddress),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (!acknowledged) {
                Button(
                    onClick = onAcknowledge,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.onboarding_risk_accept)) }
            } else {
                Text(
                    stringResource(R.string.onboarding_risk_accepted),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun DefaultSmsDiagnostic(
    roleManagerHeld: Boolean,
    telephonyMatches: Boolean,
    systemDefault: String?,
    ourPackage: String
) {
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(
            if (expanded) stringResource(R.string.onboarding_diag_hide)
            else stringResource(R.string.onboarding_diag_show)
        )
    }
    AnimatedVisibility(visible = expanded, enter = mfsExpandEnter(), exit = mfsExpandExit()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small
        ) {
            Column(
                modifier = Modifier.padding(MfsSpacing.md),
                verticalArrangement = Arrangement.spacedBy(MfsSpacing.xs)
            ) {
                Text(
                    stringResource(R.string.onboarding_diag_title),
                    style = MaterialTheme.typography.labelMedium
                )
                DiagnosticRow("RoleManager.isRoleHeld(SMS)", roleManagerHeld.toString())
                DiagnosticRow(
                    stringResource(R.string.onboarding_diag_telephony_match),
                    telephonyMatches.toString()
                )
                DiagnosticRow(
                    stringResource(R.string.onboarding_diag_system_sees),
                    systemDefault ?: stringResource(R.string.onboarding_diag_null)
                )
                DiagnosticRow(stringResource(R.string.onboarding_diag_our_package), ourPackage)
                if (systemDefault != null && systemDefault != ourPackage) {
                    Text(
                        stringResource(R.string.onboarding_diag_other_default),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StepIndicator(current: OnboardingStep) {
    val target = when (current) {
        OnboardingStep.DefaultSmsApp -> 0.05f
        OnboardingStep.NotificationAccess -> 0.25f
        OnboardingStep.PostNotifications -> 0.45f
        OnboardingStep.ContactsAccess -> 0.65f
        OnboardingStep.PreFlightTest -> 0.85f
        OnboardingStep.Done -> 1f
    }
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = MfsMotion.spatial(),
        label = "stepProgress"
    )
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth()
    )
}
