package io.github.lycheeappf.tmm.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.lycheeappf.tmm.ui.theme.MfsSpacing

enum class MfsCardVariant { Filled, Elevated, Outlined }

/**
 * Karte mit Titel, Beschreibung, Trennlinie und Inhalt. Geteilt von Settings- und
 * Assistant-Screen. Token-basiert (kein hartes dp) und mit Varianten für visuelle
 * Hierarchie. Titel-Gewicht kommt jetzt aus `titleMedium` (kein manuelles
 * `fontWeight` mehr nötig).
 */
@Composable
fun SettingCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    variant: MfsCardVariant = MfsCardVariant.Filled,
    content: @Composable ColumnScope.() -> Unit
) {
    val body: @Composable ColumnScope.() -> Unit = {
        Column(
            modifier = Modifier.padding(MfsSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = MfsSpacing.xs))
            content()
        }
    }
    when (variant) {
        MfsCardVariant.Filled -> Card(modifier = modifier.fillMaxWidth(), content = body)
        MfsCardVariant.Elevated -> ElevatedCard(modifier = modifier.fillMaxWidth(), content = body)
        MfsCardVariant.Outlined -> OutlinedCard(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.outlinedCardColors(),
            content = body
        )
    }
}
