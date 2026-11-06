package com.example.tangeml2.domain.model

import java.math.BigInteger

/** Where a scanned payload came from, shown in the review sheet. */
enum class QrSource(val label: String) {
    EIP681("EIP-681 支付请求"),
    JSON("JSON 交易描述"),
    RAW_CALLDATA("原始 calldata"),
    URL("链接参数"),
    ADDRESS("地址二维码"),
    UNKNOWN("未识别"),
}

/**
 * A transaction request decoded from a QR code.
 *
 * [to] may be blank when the code only carried calldata; the UI then asks for the
 * target contract before the call can be built.
 */
data class QrContractCall(
    val to: String,
    val chainId: Long?,
    val valueWei: BigInteger?,
    val data: String,
    val gasLimit: BigInteger?,
    val label: String? = null,
    val source: QrSource = QrSource.UNKNOWN,
    val raw: String = "",
)

/** Outcome of parsing a scanned QR string. */
sealed interface QrPayload {
    /** A callable contract transaction. */
    data class ContractCall(val call: QrContractCall) : QrPayload

    /** A plain recipient address (no calldata). */
    data class Address(val address: String) : QrPayload

    /** Recognised as a QR payload we cannot execute (e.g. WalletConnect pairing). */
    data class Unsupported(val reason: String, val raw: String) : QrPayload
}
