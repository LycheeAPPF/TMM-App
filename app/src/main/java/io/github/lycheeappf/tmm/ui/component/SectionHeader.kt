package io.github.lycheeappf.tmm.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.lycheeappf.tmm.ui.theme.MfsSpacing

/** Gruppen-Überschrift für Screen-Abschnitte. */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = MfsSpacing.sm, bottom = MfsSpacing.xs)
    )
}
