package com.example.tangeml2.domain.model

import java.math.BigInteger

/** What the user is about to sign. */
enum class AssetType {
    /** Chain-native coin (ETH, MATIC, …). */
    NATIVE,

    /** ERC-20 token transfer via `transfer(address,uint256)`. */
    TOKEN,

    /** Arbitrary contract call with caller-supplied calldata (blind signing). */
    CONTRACT,
}

/** ERC-20 metadata plus the holder balance. */
data class TokenInfo(
    val address: String,
    val symbol: String,
    val decimals: Int,
    val balanceRaw: BigInteger,
)

/** Result of a successful NFC scan + key derivation. */
data class CardSnapshot(
    val cardId: String,
    val firmware: String,
    val isAccessCodeSet: Boolean,
    val address: String,
    /** 65-byte uncompressed public key of the derived key, hex encoded. */
    val publicKeyHex: String,
)

/** A fully priced, not-yet-signed transaction. */
data class UnsignedTx(
    val chainId: Long,
    val nonce: BigInteger,
    val gasPrice: BigInteger,
    val gasLimit: BigInteger,
    val to: String,
    val valueWei: BigInteger,
    /** `0x` for a plain value transfer. */
    val data: String,
) {
    /** Worst-case fee in wei. */
    val maxFeeWei: BigInteger get() = gasPrice.multiply(gasLimit)
}

/** Fee estimate shown before the user commits. */
data class TxQuote(
    val gasPriceWei: BigInteger,
    val gasLimit: BigInteger,
    val feeWei: BigInteger,
    val nonce: BigInteger,
)

/** Terminal state of a broadcast attempt. */
sealed interface TxOutcome {
    data class Confirmed(val txHash: String) : TxOutcome
    data class Reverted(val txHash: String) : TxOutcome
    data class Pending(val txHash: String) : TxOutcome
}

/** Progress of the multi-step send flow, surfaced to the UI. */
enum class TxPhase {
    IDLE,
    QUOTING,
    AWAITING_CARD,
    BROADCASTING,
    CONFIRMING,
}

/** Tone of the status banner. */
enum class StatusTone { NEUTRAL, SUCCESS, WARNING, ERROR }

data class StatusMessage(
    val text: String,
    val tone: StatusTone = StatusTone.NEUTRAL,
) {
    companion object {
        fun info(text: String) = StatusMessage(text, StatusTone.NEUTRAL)
        fun ok(text: String) = StatusMessage(text, StatusTone.SUCCESS)
        fun warn(text: String) = StatusMessage(text, StatusTone.WARNING)
        fun error(text: String) = StatusMessage(text, StatusTone.ERROR)
    }
}
