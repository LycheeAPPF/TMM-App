package io.github.lycheeappf.tmm.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lycheeappf.tmm.R
import io.github.lycheeappf.tmm.ui.theme.MfsSpacing

/**
 * Standard-Primäraktion mit Lade-Zustand. Der Label-Wechsel (z.B. "Starten" →
 * "läuft…") wird animiert, optional mit Inline-Spinner. Vereinheitlicht die
 * wiederholten `Button { Text(if (inFlight) "läuft…" else …) }`-Blöcke.
 */
@Composable
fun PrimaryActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
            val loadingLabel = stringResource(R.string.component_button_loading)
            AnimatedContent(targetState = if (loading) loadingLabel else text, label = "btnLabel") { label ->
                Text(label)
            }
        }
    }
}
