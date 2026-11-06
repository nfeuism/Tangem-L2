package com.example.tangeml2.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.tangeml2.core.util.Validators
import com.example.tangeml2.domain.Async
import com.example.tangeml2.domain.CardState
import com.example.tangeml2.domain.RecoveryActions
import com.example.tangeml2.domain.RecoveryUiState
import com.example.tangeml2.domain.model.AssetType
import com.example.tangeml2.domain.model.TxPhase
import com.example.tangeml2.ui.components.CopyableMonoText
import com.example.tangeml2.ui.components.Gap
import com.example.tangeml2.ui.components.InfoPill
import com.example.tangeml2.ui.components.LabelledField
import com.example.tangeml2.ui.components.NoticeCard
import com.example.tangeml2.ui.components.NoticeTone
import com.example.tangeml2.ui.components.SectionCard
import com.example.tangeml2.ui.components.SectionSpacer
import com.example.tangeml2.ui.components.SegmentedControl
import com.example.tangeml2.ui.components.StatusBanner
import com.example.tangeml2.ui.theme.MonoAmountStyle
import com.example.tangeml2.ui.theme.MonoStyle
import com.example.tangeml2.ui.theme.appColors
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The single screen of the app: pick a chain, tap the card, choose an asset, enter a
 * recipient, sign.
 *
 * Purely a function of [RecoveryUiState]; every intent goes through [RecoveryActions].
 */
@Composable
fun RecoveryScreen(
    state: RecoveryUiState,
    actions: RecoveryActions,
    onScanRecipientQr: () -> Unit,
    onScanContractQr: () -> Unit,
) {
    var networkSheetOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppHeader(
            state = state,
            onNetworkClick = { networkSheetOpen = true },
            onLogClick = actions::openLog,
        )
        StatusBanner(
            status = state.status,
            onDismiss = actions::dismissStatus,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                Gap(12)
                BalanceHero(state = state, actions = actions)
                Gap(12)
                QrHeroCard(onClick = onScanContractQr)
                Gap(12)
                CardSection(state = state, actions = actions)
                SectionSpacer()
                AssetSection(
                    state = state,
                    actions = actions,
                    onScanContractQr = onScanContractQr,
                )
                SectionSpacer()
                RecipientSection(
                    state = state,
                    actions = actions,
                    onScanRecipientQr = onScanRecipientQr,
                )
                SectionSpacer()
                LogEntry(actions = actions)
                Gap(20)
            }
        }
        BottomActionBar(state = state, actions = actions)
    }

    if (networkSheetOpen) {
        NetworkSheet(
            state = state,
            actions = actions,
            onDismiss = { networkSheetOpen = false },
        )
    }
    if (state.addNetworkOpen) {
        AddNetworkDialog(state = state, actions = actions)
    }
    if (state.qrReview != null) {
        QrReviewSheet(state = state, actions = actions)
    }
    if (state.confirmOpen && state.confirmSummary != null) {
        ConfirmSheet(state = state, actions = actions)
    }
    if (state.logOpen) {
        LogSheet(state = state, actions = actions)
    }
}

// ── header ───────────────────────────────────────────────────────────────────

@Composable
private fun AppHeader(
    state: RecoveryUiState,
    onNetworkClick: () -> Unit,
    onLogClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "Tangem L2 Recovery",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "硬件钱包签名 · 自定义 EVM 链救援",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            onClick = onNetworkClick,
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .background(MaterialTheme.appColors.success, RoundedCornerShape(999.dp)),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = state.selectedNetwork.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = "切换网络",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        IconButton(onClick = onLogClick) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Article,
                contentDescription = "查看日志",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── balance hero ─────────────────────────────────────────────────────────────

@Composable
private fun BalanceHero(state: RecoveryUiState, actions: RecoveryActions) {
    val network = state.selectedNetwork
    val address = state.address
    val gradient = Brush.linearGradient(
        listOf(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            MaterialTheme.colorScheme.surface,
        ),
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
    ) {
        Box(Modifier.background(gradient).padding(16.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "原生币余额",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    if (state.hasCard) {
                        IconButton(
                            onClick = actions::refreshBalance,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "刷新余额",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                Gap(4)
                when (val balance = state.nativeBalance) {
                    is Async.Data -> Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = balance.value,
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = network.nativeSymbol,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 5.dp),
                        )
                    }

                    is Async.Loading -> Text(
                        text = "查询中…",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    is Async.Failed -> Text(
                        text = "余额读取失败",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.appColors.danger,
                    )

                    Async.Idle -> Text(
                        text = if (address == null) "—" else "点刷新查询",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Gap(10)
                if (address == null) {
                    Text(
                        text = "扫描卡片后显示地址",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    CopyableMonoText(value = address, label = "本卡地址（点击复制）", maxLines = 2)
                }
                if (state.nativeBalance is Async.Failed) {
                    Gap(6)
                    Text(
                        text = (state.nativeBalance as Async.Failed).message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.appColors.danger,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ── QR hero ──────────────────────────────────────────────────────────────────

@Composable
private fun QrHeroCard(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primary,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.QrCodeScanner,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "扫码签署合约",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Text(
                    text = "扫描 EIP-681 / JSON / calldata 二维码，解析后直接签名",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
                )
            }
            Icon(
                imageVector = Icons.Rounded.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ── card / NFC ───────────────────────────────────────────────────────────────

@Composable
private fun CardSection(state: RecoveryUiState, actions: RecoveryActions) {
    SectionCard(step = "1", title = "Tangem 卡片") {
        when (val card = state.card) {
            is CardState.None -> {
                Text(
                    text = "贴上卡片即可在设备内派生签名，私钥永不离开卡片。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Gap(10)
                Button(
                    onClick = actions::scanCard,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Icon(Icons.Rounded.Nfc, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("扫描卡片")
                }
            }

            is CardState.Scanning -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "请将卡片贴到 NFC 感应区…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            is CardState.Ready -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InfoPill(text = "固件 ${card.snapshot.firmware}")
                    Spacer(Modifier.width(6.dp))
                    InfoPill(
                        text = if (card.snapshot.isAccessCodeSet) "已设访问密码" else "无访问密码",
                        tint = if (card.snapshot.isAccessCodeSet) {
                            MaterialTheme.appColors.success
                        } else {
                            MaterialTheme.appColors.warning
                        },
                    )
                }
                Gap(8)
                CopyableMonoText(
                    value = card.snapshot.address,
                    label = "派生地址 ${Validators.shortAddress(card.snapshot.address)}",
                )
                Gap(8)
                TextButton(onClick = actions::scanCard) {
                    Icon(Icons.Rounded.Nfc, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重新扫描")
                }
            }

            is CardState.Failed -> {
                NoticeCard(
                    tone = NoticeTone.DANGER,
                    title = "读取卡片失败",
                    message = card.message,
                )
                Gap(8)
                Button(
                    onClick = actions::scanCard,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("重试")
                }
            }
        }
    }
}

// ── asset ────────────────────────────────────────────────────────────────────

@Composable
private fun AssetSection(
    state: RecoveryUiState,
    actions: RecoveryActions,
    onScanContractQr: () -> Unit,
) {
    SectionCard(step = "2", title = "资产类型") {
        SegmentedControl(
            options = listOf(
                AssetType.NATIVE to "原生币",
                AssetType.TOKEN to "ERC20",
                AssetType.CONTRACT to "合约调用",
            ),
            selected = state.assetType,
            onSelect = actions::setAssetType,
        )

        if (state.assetType == AssetType.TOKEN) {
            Gap(12)
            LabelledField(
                value = state.tokenAddressInput,
                onValueChange = actions::setTokenAddress,
                label = "代币合约地址",
                placeholder = "0x…",
                mono = true,
                error = if (state.tokenAddressInput.isNotBlank() &&
                    !Validators.isValidAddress(Validators.normalizeAddress(state.tokenAddressInput))
                ) {
                    "地址格式不正确"
                } else {
                    null
                },
            )
            Gap(8)
            Button(
                onClick = actions::loadToken,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = MaterialTheme.shapes.small,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Text("查询代币信息")
            }
            when (val token = state.token) {
                is Async.Data -> {
                    Gap(10)
                    NoticeCard(
                        tone = NoticeTone.NEUTRAL,
                        title = "${token.value.symbol} · 精度 ${token.value.decimals}",
                        message = "余额 ${formatTokenBalance(token.value.balanceRaw.toString(), token.value.decimals)} ${token.value.symbol}",
                    )
                }

                is Async.Loading -> {
                    Gap(8)
                    Text(
                        text = "查询中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is Async.Failed -> {
                    Gap(8)
                    NoticeCard(tone = NoticeTone.DANGER, title = "代币查询失败", message = token.message)
                }

                Async.Idle -> Unit
            }
        }

        if (state.assetType == AssetType.CONTRACT) {
            Gap(12)
            NoticeCard(
                tone = NoticeTone.WARNING,
                title = "合约调用是盲签",
                message = "应用无法验证合约逻辑。请仅使用可信来源的 calldata，或改用「扫码签署合约」。",
            )
            Gap(10)
            LabelledField(
                value = state.calldataInput,
                onValueChange = actions::setCalldata,
                label = "Calldata",
                placeholder = "0x…（从区块浏览器 Write Contract 或 dApp 复制）",
                mono = true,
                singleLine = false,
                minLines = 3,
                error = if (state.calldataInput.isNotBlank() &&
                    !Validators.isValidCalldata(Validators.normalizeCalldata(state.calldataInput))
                ) {
                    "calldata 应为 0x 开头、偶数长度、至少 4 字节"
                } else {
                    null
                },
            )
            Gap(10)
            LabelledField(
                value = state.callValueInput,
                onValueChange = actions::setCallValue,
                label = "随调用发送的 ${state.selectedNetwork.nativeSymbol}（通常为 0）",
                placeholder = "0",
                keyboardType = KeyboardType.Decimal,
            )
            Gap(10)
            TextButton(onClick = onScanContractQr) {
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("改为扫码填入")
            }
        }
    }
}

// ── recipient / amount ───────────────────────────────────────────────────────

@Composable
private fun RecipientSection(
    state: RecoveryUiState,
    actions: RecoveryActions,
    onScanRecipientQr: () -> Unit,
) {
    val symbol = when (state.assetType) {
        AssetType.TOKEN -> (state.token as? Async.Data)?.value?.symbol ?: "代币"
        AssetType.CONTRACT -> state.selectedNetwork.nativeSymbol
        AssetType.NATIVE -> state.selectedNetwork.nativeSymbol
    }
    SectionCard(step = "3", title = "收款与金额") {
        LabelledField(
            value = state.recipientInput,
            onValueChange = actions::setRecipient,
            label = if (state.assetType == AssetType.CONTRACT) "合约地址（调用目标）" else "收款地址",
            placeholder = "0x…",
            mono = true,
            error = if (state.recipientInput.isNotBlank() &&
                !Validators.isValidAddress(Validators.normalizeAddress(state.recipientInput))
            ) {
                "地址格式不正确（0x + 40 位十六进制）"
            } else {
                null
            },
            trailing = {
                IconButton(onClick = onScanRecipientQr) {
                    Icon(
                        imageVector = Icons.Rounded.QrCodeScanner,
                        contentDescription = "扫描收款地址二维码",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            },
        )
        Gap(6)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = actions::fillOwnAddress) {
                Text("填入本卡地址", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (state.assetType != AssetType.CONTRACT) {
            Gap(8)
            LabelledField(
                value = state.amountInput,
                onValueChange = actions::setAmount,
                label = "金额（$symbol）",
                placeholder = "0.0",
                keyboardType = KeyboardType.Decimal,
            )
            Gap(6)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = actions::fillMaxAmount) {
                    Text("全部余额", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.weight(1f))
                val quote = state.quote
                if (quote != null) {
                    Text(
                        text = "预计手续费 ${feeText(quote.feeWei.toString(), state)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                } else if (state.quoteLoading) {
                    Text(
                        text = "估算手续费…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            Gap(8)
            val quote = state.quote
            NoticeCard(
                tone = NoticeTone.NEUTRAL,
                title = "预计手续费",
                message = when {
                    quote != null -> feeText(quote.feeWei.toString(), state)
                    state.quoteLoading -> "估算中…"
                    else -> "填写完整 calldata 后自动估算"
                },
            )
        }
    }
}

// ── log entry ────────────────────────────────────────────────────────────────

@Composable
private fun LogEntry(actions: RecoveryActions) {
    TextButton(onClick = actions::openLog, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.Article,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("诊断日志 / 导出", style = MaterialTheme.typography.labelMedium)
    }
}

// ── bottom action bar ────────────────────────────────────────────────────────

@Composable
private fun BottomActionBar(state: RecoveryUiState, actions: RecoveryActions) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (state.phase != TxPhase.IDLE) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = phaseLabel(state.phase),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = actions::requestSend,
                enabled = state.canSign && !state.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (state.hasCard) "签名并发送" else "请先扫描卡片",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (state.assetType == AssetType.CONTRACT && state.hasCard) {
                Gap(4)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.appColors.warning,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "合约调用无法解析时将按盲签处理",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun phaseLabel(phase: TxPhase): String = when (phase) {
    TxPhase.IDLE -> ""
    TxPhase.QUOTING -> "正在估算交易…"
    TxPhase.AWAITING_CARD -> "等待贴卡签名…"
    TxPhase.BROADCASTING -> "正在广播…"
    TxPhase.CONFIRMING -> "等待链上确认…"
}

private fun feeText(feeWei: String, state: RecoveryUiState): String {
    val network = state.selectedNetwork
    val value = runCatching {
        BigDecimal(java.math.BigInteger(feeWei), network.nativeDecimals)
            .setScale(8, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }.getOrNull() ?: "—"
    return "$value ${network.nativeSymbol}"
}

private fun formatTokenBalance(raw: String, decimals: Int): String = runCatching {
    BigDecimal(java.math.BigInteger(raw), decimals)
        .setScale(minOf(decimals, 6), RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
}.getOrNull() ?: raw
