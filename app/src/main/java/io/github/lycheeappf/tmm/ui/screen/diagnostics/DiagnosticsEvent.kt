package io.github.lycheeappf.tmm.ui.screen.diagnostics

import java.io.File

/**
 * One-Shot-Events aus den Diagnose-/Settings-ViewModels an die UI. Werden über
 * einen Channel/`receiveAsFlow` emittiert und von der jeweiligen Compose-Schicht
 * (mit Activity-Kontext) in ein Share-Sheet bzw. einen Fehler-Toast übersetzt.
 */
sealed interface DiagnosticsEvent {
    data class Share(val file: File) : DiagnosticsEvent
    data object ExportFailed : DiagnosticsEvent
}
