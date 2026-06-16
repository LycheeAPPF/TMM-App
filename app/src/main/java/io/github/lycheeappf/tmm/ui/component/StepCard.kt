package io.github.lycheeappf.tmm.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.lycheeappf.tmm.ui.theme.MfsSpacing

/**
 * Onboarding-Schrittkarte: nummeriertes Icon (offen/erledigt), Titel, Beschreibung
 * und Aktions-Slot. Container- und Icon-Farbe animieren beim Erledigen.
 */
@Composable
fun StepCard(
    number: Int,
    title: String,
    description: String,
    done: Boolean,
    modifier: Modifier = Modifier,
    action: @Composable ColumnScope.() -> Unit
) {
    val container by animateColorAsState(
        targetValue = if (done) MfsStatus.Success.containerColors().first
        else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = MfsMotion.effects(),
        label = "stepContainer"
    )
    val iconTint by animateColorAsState(
        targetValue = if (done) MfsStatus.Success.accentColor() else MaterialTheme.colorScheme.outline,
        animationSpec = MfsMotion.effects(),
        label = "stepIcon"
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(MfsSpacing.lg),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(MfsSpacing.md)
        ) {
            AnimatedContent(targetState = done, label = "stepIconSwap") { isDone ->
                Icon(
                    imageVector = if (isDone) Icons.Outlined.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(28.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MfsSpacing.sm)
            ) {
                Text("$number. $title", style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                action()
            }
        }
    }
}
