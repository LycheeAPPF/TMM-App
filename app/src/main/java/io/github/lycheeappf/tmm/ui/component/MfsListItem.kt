package io.github.lycheeappf.tmm.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import io.github.lycheeappf.tmm.ui.theme.MfsSpacing

/**
 * Generische Listenzeile: Titel + optionaler Untertitel, optionales Leading/Trailing
 * und optionaler Klick. Konsolidiert die früheren privaten `NavRow` (Home),
 * `AppRow` (Whitelist) und `StatusRow` (Home).
 */
@Composable
fun MfsListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMonospace: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickModifier)
            .padding(horizontal = MfsSpacing.lg, vertical = MfsSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MfsSpacing.md)
    ) {
        if (leading != null) leading()
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall.let {
                        if (subtitleMonospace) it.copy(fontFamily = FontFamily.Monospace) else it
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) trailing()
    }
}
