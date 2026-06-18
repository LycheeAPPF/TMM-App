package io.github.lycheeappf.tmm.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.data.store.SettingsStore

/**
 * Einheitliche Abbildung des roh gespeicherten Pre-Flight-Ergebnisses auf
 * (Klartext-Label, [MfsStatus]). Eine Quelle für Onboarding UND Settings, damit
 * die Status-Texte nicht auseinanderlaufen. Bewusst klares Deutsch (keine
 * "Carrier rejected"-Fachbegriffe).
 */
@Composable
fun preflightStatusUi(raw: String?): Pair<String, MfsStatus> = when (raw) {
    SettingsStore.PREFLIGHT_OK ->
        stringResource(R.string.component_preflight_ok) to MfsStatus.Success
    SettingsStore.PREFLIGHT_RISK ->
        stringResource(R.string.component_preflight_risk) to MfsStatus.Error
    SettingsStore.PREFLIGHT_TIMEOUT ->
        stringResource(R.string.component_preflight_timeout) to MfsStatus.Warning
    SettingsStore.PREFLIGHT_ERROR ->
        stringResource(R.string.component_preflight_error) to MfsStatus.Error
    SettingsStore.PREFLIGHT_RUNNING ->
        stringResource(R.string.component_preflight_running) to MfsStatus.Info
    else ->
        stringResource(R.string.component_preflight_not_run) to MfsStatus.Neutral
}
