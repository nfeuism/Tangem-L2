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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.tangeml2.core.util.Units
import com.example.tangeml2.core.util.Validators
import com.example.tangeml2.domain.RecoveryActions
import com.example.tangeml2.domain.RecoveryUiState
import com.example.tangeml2.ui.components.CalldataPreview
import com.example.tangeml2.ui.components.InfoPill
import com.example.tangeml2.ui.components.KeyValueRow
import com.example.tangeml2.ui.components.NoticeCard
import com.example.tangeml2.ui.components.NoticeTone
import com.example.tangeml2.ui.theme.MonoStyle
import com.example.tangeml2.ui.theme.appColors
import java.math.BigInteger

/**
 * Review sheet for a scanned contract call.
 *
 * This is the trust boundary of the new QR feature: the user must be able to see the
 * exact target, chain, value and decoded calldata before the card is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrReviewSheet(state: RecoveryUiState, actions: RecoveryActions) {
    val call = state.qrReview ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val network = state.selectedNetwork

    ModalBottomSheet(
        onDismissRequest = actions::dismissQrReview,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "扫码结果",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                InfoPill(text = call.source.label)
                if (call.label != null) {
                    Spacer(Modifier.width(6.dp))
                    InfoPill(text = call.label, tint = MaterialTheme.appColors.success)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "请逐项核对后再贴卡签名。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            if (state.qrChainMismatch != null) {
                NoticeCard(
                    tone = NoticeTone.WARNING,
                    title = "链不一致",
                    message = state.qrChainMismatch,
                )
                Spacer(Modifier.height(12.dp))
            }

            KeyValueRow(
                label = "目标合约",
                value = if (call.to.isBlank()) "（未提供，需手动填写）" else Validators.toChecksumAddress(call.to),
                mono = true,
            )
            KeyValueRow(
                label = "链",
                value = call.chainId?.let { "chainId $it" } ?: "未指定（使用当前网络 ${network.displayName}）",
                mono = true,
            )
            KeyValueRow(
                label = "附带金额",
                value = call.valueWei?.let {
                    "${Units.formatExact(it, network.nativeDecimals)} ${network.nativeSymbol}"
                } ?: "0 ${network.nativeSymbol}",
                emphasise = true,
            )
            KeyValueRow(
                label = "Gas 上限",
                value = call.gasLimit?.toString() ?: "自动估算",
                mono = true,
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Spacer(Modifier.height(10.dp))

            Text(
                text = "Calldata 解析",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            CalldataPreview(calldata = call.data, showRaw = true)

            if (call.to.isBlank()) {
                Spacer(Modifier.height(10.dp))
                NoticeCard(
                    tone = NoticeTone.WARNING,
                    title = "缺少目标合约",
                    message = "这份二维码只有 calldata。确认后会填入表单，请手动填写合约地址再签署。",
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = actions::prefillFromQrReview,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("填入表单")
                }
                Button(
                    onClick = actions::acceptQrReview,
                    modifier = Modifier.weight(1.3f).height(48.dp),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("确认并签署")
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
                    text = "确认后会先弹出完整交易确认，再请求贴卡。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Shared helper so every surface formats native amounts identically. */
internal fun formatNative(wei: BigInteger?, decimals: Int, symbol: String): String {
    if (wei == null) return "0 $symbol"
    return "${Units.formatExact(wei, decimals)} $symbol"
}
