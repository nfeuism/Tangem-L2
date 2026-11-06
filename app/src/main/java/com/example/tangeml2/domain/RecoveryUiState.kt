package com.example.tangeml2.domain

import com.example.tangeml2.domain.model.AssetType
import com.example.tangeml2.domain.model.CardSnapshot
import com.example.tangeml2.domain.model.Network
import com.example.tangeml2.domain.model.QrContractCall
import com.example.tangeml2.domain.model.StatusMessage
import com.example.tangeml2.domain.model.TokenInfo
import com.example.tangeml2.domain.model.TxPhase
import com.example.tangeml2.domain.model.TxQuote

/** Card / NFC connection state. */
sealed interface CardState {
    data object None : CardState
    data object Scanning : CardState
    data class Ready(val snapshot: CardSnapshot) : CardState
    data class Failed(val message: String) : CardState
}

/** Async value holder used for balances and token lookups. */
sealed interface Async<out T> {
    data object Idle : Async<Nothing>
    data object Loading : Async<Nothing>
    data class Data<T>(val value: T) : Async<T>
    data class Failed(val message: String) : Async<Nothing>
}

/** Everything the single screen renders. */
data class RecoveryUiState(
    val networks: List<Network> = Network.PRESETS,
    val selectedNetwork: Network = Network.PRESETS.first(),
    val card: CardState = CardState.None,

    /** Formatted native balance, e.g. "0.0421". */
    val nativeBalance: Async<String> = Async.Idle,

    val assetType: AssetType = AssetType.NATIVE,
    val tokenAddressInput: String = "",
    val token: Async<TokenInfo> = Async.Idle,

    val recipientInput: String = "",
    val amountInput: String = "",
    /** ETH value sent along with a CONTRACT call. */
    val callValueInput: String = "",
    val calldataInput: String = "",

    /** Live fee preview for the current form. */
    val quote: TxQuote? = null,
    val quoteLoading: Boolean = false,

    val phase: TxPhase = TxPhase.IDLE,
    val busy: Boolean = false,
    val status: StatusMessage = StatusMessage.info("请先扫描 Tangem 卡片"),

    /** Review sheet for a scanned QR contract call. */
    val qrReview: QrContractCall? = null,
    /** Set when the scanned payload targets a different chain than the selection. */
    val qrChainMismatch: String? = null,

    /** Confirmation sheet shown right before NFC signing. */
    val confirmOpen: Boolean = false,
    val confirmSummary: ConfirmSummary? = null,

    val addNetworkOpen: Boolean = false,
    val logOpen: Boolean = false,
    val logText: String = "",

    val canSign: Boolean = false,
) {
    val hasCard: Boolean get() = card is CardState.Ready
    val address: String? get() = (card as? CardState.Ready)?.snapshot?.address
}

/** Pre-signing review of the exact transaction that will be signed. */
data class ConfirmSummary(
    val networkLabel: String,
    val chainId: Long,
    val from: String,
    val to: String,
    val assetLabel: String,
    val amountLabel: String,
    val feeLabel: String,
    val calldataLabel: String?,
    val calldataPreview: String?,
    val totalLabel: String,
    val warnings: List<String> = emptyList(),
)
