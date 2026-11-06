package com.example.tangeml2.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.tangeml2.data.abi.CalldataDecoder
import com.example.tangeml2.data.abi.DecodedCall
import com.example.tangeml2.ui.theme.MonoStyle

/**
 * Human-readable preview of calldata.
 *
 * A wallet must never imply it understood a call it did not: unknown selectors get a
 * prominent blind-signing warning instead of a friendly-looking summary.
 */
@Composable
fun CalldataPreview(
    calldata: String,
    modifier: Modifier = Modifier,
    showRaw: Boolean = true,
) {
    val decoded: DecodedCall? = remember(calldata) {
        runCatching { CalldataDecoder.decode(calldata) }.getOrNull()
    }

    Column(modifier.fillMaxWidth()) {
        if (decoded == null) {
            NoticeCard(
                tone = NoticeTone.WARNING,
                title = "无法解析 calldata",
                message = "这是盲签：请确认 calldata 来源可信。",
            )
        } else if (!decoded.isKnown) {
            NoticeCard(
                tone = NoticeTone.WARNING,
                title = "未知函数 ${decoded.selector}",
                message = "应用无法识别该函数，属于盲签。请仅在完全信任来源时继续。",
            )
        } else {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = decoded.name ?: decoded.selector,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = decoded.signature ?: "",
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (decoded.summary != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = decoded.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (decoded.params.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                decoded.params.forEach { param ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            text = "${param.name}:${param.type}",
                            style = MonoStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(132.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = param.value,
                            style = MonoStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        if (showRaw && calldata.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "原始 calldata",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            CodeBlock(text = calldata, maxHeight = 120)
        }
    }
}
