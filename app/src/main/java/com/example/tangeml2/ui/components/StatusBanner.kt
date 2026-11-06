package com.example.tangeml2.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.tangeml2.domain.model.StatusMessage
import com.example.tangeml2.domain.model.StatusTone
import com.example.tangeml2.ui.theme.appColors

/**
 * Always-visible status strip directly under the header.
 *
 * v1 rendered status at the bottom of a long scroll view, so failures were invisible
 * unless the user scrolled down after tapping send.
 */
@Composable
fun StatusBanner(
    status: StatusMessage,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.appColors
    val accent: Color = when (status.tone) {
        StatusTone.SUCCESS -> colors.success
        StatusTone.WARNING -> colors.warning
        StatusTone.ERROR -> colors.danger
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.primary
    }
    val container: Color = when (status.tone) {
        StatusTone.SUCCESS -> colors.successContainer
        StatusTone.WARNING -> colors.warningContainer
        StatusTone.ERROR -> colors.dangerContainer
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    val icon: ImageVector = when (status.tone) {
        StatusTone.SUCCESS -> Icons.Rounded.CheckCircle
        StatusTone.WARNING -> Icons.Rounded.WarningAmber
        StatusTone.ERROR -> Icons.Rounded.ErrorOutline
        StatusTone.NEUTRAL -> Icons.Rounded.Info
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = container,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.25f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            AnimatedContent(
                targetState = status.text,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "status",
                modifier = Modifier.weight(1f),
            ) { text ->
                Column {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "关闭提示",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}
