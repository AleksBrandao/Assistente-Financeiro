package br.com.assistentefinanceiro.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.com.assistentefinanceiro.ui.theme.FinanceSpacing

@Composable
fun FinanceIconTile(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    iconColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    size: Int = 40,
) {
    Surface(
        modifier = modifier.size(size.dp),
        shape = CircleShape,
        color = containerColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size((size / 2).dp),
                tint = iconColor,
            )
        }
    }
}

@Composable
fun FinanceStatusPill(
    text: String,
    foreground: Color,
    background: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(100.dp),
        color = background,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = FinanceSpacing.xs,
                vertical = FinanceSpacing.xxs,
            ),
            horizontalArrangement = Arrangement.spacedBy(FinanceSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = foreground,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = foreground,
            )
        }
    }
}

@Composable
fun FinanceEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FinanceSpacing.lg, vertical = FinanceSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(FinanceSpacing.sm),
        ) {
            FinanceIconTile(
                icon = icon,
                contentDescription = null,
                size = 64,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                FilledTonalButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun FinanceNoticeCard(
    icon: ImageVector,
    title: String,
    description: String,
    foreground: Color,
    background: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = background,
    ) {
        Row(
            modifier = Modifier.padding(FinanceSpacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            FinanceIconTile(
                icon = icon,
                contentDescription = null,
                containerColor = foreground.copy(alpha = 0.12f),
                iconColor = foreground,
            )
            Spacer(Modifier.width(FinanceSpacing.sm))
            Column(verticalArrangement = Arrangement.spacedBy(FinanceSpacing.xxs)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = foreground,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = foreground,
                )
            }
        }
    }
}
