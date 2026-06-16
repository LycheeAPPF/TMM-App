package io.github.lycheeappf.tmm.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.lycheeappf.tmm.ui.theme.MfsColors
import io.github.lycheeappf.tmm.ui.theme.MfsPillShape

/** Semantischer Status für Pills, Icons und Karten. */
enum class MfsStatus { Success, Warning, Error, Info, Neutral }

/** Container/On-Container-Farbpaar für einen [MfsStatus] (dark-mode-aware via [MfsColors]). */
@Composable
fun MfsStatus.containerColors(): Pair<Color, Color> {
    val status = MfsColors.status
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        MfsStatus.Success -> status.successContainer to status.onSuccessContainer
        MfsStatus.Warning -> status.warningContainer to status.onWarningContainer
        MfsStatus.Error -> scheme.errorContainer to scheme.onErrorContainer
        MfsStatus.Info -> status.infoContainer to status.onInfoContainer
        MfsStatus.Neutral -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
}

/** Akzentfarbe (für Icon-Tints / Text) eines [MfsStatus]. */
@Composable
fun MfsStatus.accentColor(): Color {
    val status = MfsColors.status
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        MfsStatus.Success -> status.success
        MfsStatus.Warning -> status.warning
        MfsStatus.Error -> scheme.error
        MfsStatus.Info -> status.info
        MfsStatus.Neutral -> scheme.onSurfaceVariant
    }
}

/**
 * Kompakte, vollgerundete Status-Pille. Ersetzt die pro-Screen duplizierten
 * Status-Chips (Onboarding `PreflightResultChip`, Settings-Inline-`when`,
 * Channels/Assistant `AssistChip`).
 */
@Composable
fun StatusPill(
    text: String,
    status: MfsStatus,
    modifier: Modifier = Modifier
) {
    val (container, onContainer) = status.containerColors()
    Surface(
        color = container,
        contentColor = onContainer,
        shape = MfsPillShape,
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
