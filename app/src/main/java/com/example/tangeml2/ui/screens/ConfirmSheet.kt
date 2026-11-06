package com.example.tangeml2.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.tangeml2.core.util.Validators
import com.example.tangeml2.domain.RecoveryActions
import com.example.tangeml2.domain.RecoveryUiState
import com.example.tangeml2.ui.components.CodeBlock
import com.example.tangeml2.ui.components.KeyValueRow
import com.example.tangeml2.ui.components.NoticeCard
import com.example.tangeml2.ui.components.NoticeTone
import com.example.tangeml2.ui.theme.appColors

/**
 * Final pre-signature review. v1 signed straight from the form with no confirmation
 * step at all — one stray tap could move funds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(state: RecoveryUiState, actions: RecoveryActions) {
    val summary = state.confirmSummary ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = actions::dismissConfirm,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "确认交易",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "这是即将用硬件钱包签名的内容，请核对后再贴卡。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            KeyValueRow(label = "网络", value = "${summary.networkLabel}（chainId ${summary.chainId}）")
            KeyValueRow(label = "从", value = Validators.toChecksumAddress(summary.from), mono = true)
            KeyValueRow(label = "到", value = Validators.toChecksumAddress(summary.to), mono = true)
            KeyValueRow(label = "资产", value = summary.assetLabel)
            KeyValueRow(label = "数量", value = summary.amountLabel, emphasise = true)
            KeyValueRow(label = "手续费上限", value = summary.feeLabel)
            KeyValueRow(label = "合计", value = summary.totalLabel, emphasise = true)
            if (summary.calldataLabel != null) {
                KeyValueRow(label = "调用", value = summary.calldataLabel)
            }

            if (summary.calldataPreview != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Calldata",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                CodeBlock(text = summary.calldataPreview, maxHeight = 110)
            }

            if (summary.warnings.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                summary.warnings.forEach { warning ->
                    NoticeCard(tone = NoticeTone.WARNING, title = warning)
                    Spacer(Modifier.height(6.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Spacer(Modifier.height(12.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = actions::dismissConfirm,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("取消")
                }
                Button(
                    onClick = actions::confirmSend,
                    modifier = Modifier.weight(1.4f).height(50.dp),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Icon(Icons.Rounded.Nfc, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("贴卡签名")
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.appColors.warning,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "交易一旦广播无法撤回。建议先用小额测试。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
