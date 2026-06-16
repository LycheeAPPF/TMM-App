package io.github.lycheeappf.tmm.ui.component

import io.github.lycheeappf.tmm.data.store.SettingsStore

/**
 * Einheitliche Abbildung des roh gespeicherten Pre-Flight-Ergebnisses auf
 * (Klartext-Label, [MfsStatus]). Eine Quelle für Onboarding UND Settings, damit
 * die Status-Texte nicht auseinanderlaufen. Bewusst klares Deutsch (keine
 * "Carrier rejected"-Fachbegriffe).
 */
fun preflightStatusUi(raw: String?): Pair<String, MfsStatus> = when (raw) {
    SettingsStore.PREFLIGHT_OK -> "Sicher: Netz hat die Test-SMS abgelehnt" to MfsStatus.Success
    SettingsStore.PREFLIGHT_RISK -> "Achtung: SMS wurde gesendet — nicht nutzen" to MfsStatus.Error
    SettingsStore.PREFLIGHT_TIMEOUT -> "Zeitüberschreitung — bitte erneut testen" to MfsStatus.Warning
    SettingsStore.PREFLIGHT_ERROR -> "Fehler — ist die App als Standard-SMS-App gesetzt?" to MfsStatus.Error
    SettingsStore.PREFLIGHT_RUNNING -> "läuft…" to MfsStatus.Info
    else -> "Noch nicht ausgeführt" to MfsStatus.Neutral
}
