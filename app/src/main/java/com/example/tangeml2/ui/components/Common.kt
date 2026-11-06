package com.example.tangeml2.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.tangeml2.ui.theme.MonoAmountStyle
import com.example.tangeml2.ui.theme.MonoStyle
import com.example.tangeml2.ui.theme.appColors

/** Section container with an optional numbered step badge. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    step: String? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (step != null) {
                    StepBadge(step)
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (trailing != null) trailing()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** Small numbered circle used by the step header. */
@Composable
fun StepBadge(number: String) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Labelled text input with consistent styling and inline error text. */
@Composable
fun LabelledField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    helper: String? = null,
    error: String? = null,
    mono: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder?.let { text ->
                { Text(text, style = MaterialTheme.typography.bodyMedium) }
            },
            textStyle = if (mono) MonoStyle else MaterialTheme.typography.bodyLarge,
            singleLine = singleLine,
            minLines = minLines,
            isError = error != null,
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            trailingIcon = trailing,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                errorContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
            ),
        )
        val message = error ?: helper
        if (message != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** Compact key/value row used inside review sheets. */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    emphasise: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = when {
                mono -> MonoStyle
                emphasise -> MonoAmountStyle
                else -> MaterialTheme.typography.bodyMedium
            },
            color = if (emphasise) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (emphasise) FontWeight.SemiBold else null,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Monospace address/value line that copies itself on tap. */
@Composable
fun CopyableMonoText(
    value: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    maxLines: Int = 2,
) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { clipboard.setText(AnnotatedString(value)) }
            .padding(vertical = 2.dp),
    ) {
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = value,
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Inline tonal notice: success / warning / danger / neutral. */
enum class NoticeTone { SUCCESS, WARNING, DANGER, NEUTRAL }

@Composable
fun NoticeCard(
    tone: NoticeTone,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.appColors
    val container = when (tone) {
        NoticeTone.SUCCESS -> colors.successContainer
        NoticeTone.WARNING -> colors.warningContainer
        NoticeTone.DANGER -> colors.dangerContainer
        NoticeTone.NEUTRAL -> colors.neutralContainer
    }
    val content = when (tone) {
        NoticeTone.SUCCESS -> colors.onSuccessContainer
        NoticeTone.WARNING -> colors.onWarningContainer
        NoticeTone.DANGER -> colors.onDangerContainer
        NoticeTone.NEUTRAL -> colors.onNeutralContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = container,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = content,
                )
                if (message != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = content.copy(alpha = 0.86f),
                    )
                }
            }
            if (action != null) {
                Spacer(Modifier.width(8.dp))
                action()
            }
        }
    }
}

/** Monospace block used for calldata / log output. */
@Composable
fun CodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    maxHeight: Int = 180,
) {
    val colors = MaterialTheme.appColors
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = colors.codeSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Text(
            text = text,
            style = MonoStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .height(maxHeight.dp.coerceAtMost(320.dp))
                .padding(10.dp),
            maxLines = 40,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Tiny pill used for chain id / firmware / status chips. */
@Composable
fun InfoPill(text: String, modifier: Modifier = Modifier, tint: Color? = null) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = (tint ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.12f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = tint ?: MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

/** Horizontal label/value pair with a trailing check mark (used for verified fields). */
@Composable
fun VerifiedField(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = value, style = MonoStyle, color = MaterialTheme.colorScheme.onSurface)
        }
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            tint = MaterialTheme.appColors.success,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** Vertical spacer helper. */
@Composable
fun Gap(height: Int) {
    Spacer(Modifier.height(height.dp))
}

/** Row spacer helper. */
@Composable
fun HGap(width: Int) {
    Spacer(Modifier.width(width.dp))
}

/** Standard column arrangement used between section cards. */
@Composable
fun SectionSpacer() {
    Spacer(Modifier.height(12.dp))
}

/** Evenly spaced row used for paired actions. */
@Composable
fun ActionRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}
