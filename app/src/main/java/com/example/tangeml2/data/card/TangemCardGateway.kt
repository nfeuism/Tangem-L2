package com.example.tangeml2.data.card

import androidx.fragment.app.FragmentActivity
import com.example.tangeml2.core.log.AppLog
import com.example.tangeml2.domain.model.CardSnapshot
import com.example.tangeml2.domain.model.ETH_DERIVATION_PATH
import com.tangem.TangemSdk
import com.tangem.common.CompletionResult
import com.tangem.common.card.Card
import com.tangem.common.card.CardWallet
import com.tangem.common.card.EllipticCurve
import com.tangem.common.core.Config
import com.tangem.common.extensions.toDecompressedPublicKey
import com.tangem.crypto.hdWallet.DerivationPath
import com.tangem.operations.attestation.AttestationTask
import com.tangem.sdk.extensions.init
import kotlinx.coroutines.suspendCancellableCoroutine
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric
import kotlin.coroutines.resume

/** Result of an NFC operation. */
sealed interface CardResult<out T> {
    data class Ok<T>(val value: T) : CardResult<T>
    data class Err(val message: String) : CardResult<Nothing>
}

/**
 * Tangem hardware-wallet bridge.
 *
 * The SDK is configured to derive the Ethereum path **inside the same NFC session**
 * as the card scan; opening a second session was the cause of `50003 Busy` errors.
 * Attestation runs offline (local cryptographic checks) because online attestation
 * requires a configured API base URL and is not needed to sign.
 *
 * The SDK hosts its NFC session in a `DialogFragment`, so it requires a
 * `FragmentActivity` rather than a plain `ComponentActivity`.
 */
class TangemCardGateway(private val activity: FragmentActivity) {

    private var sdk: TangemSdk? = null
    private var card: Card? = null
    private var masterWallet: CardWallet? = null
    private var derivedPublicKey: ByteArray? = null
    private var snapshot: CardSnapshot? = null

    /** Builds the SDK instance; safe to call repeatedly. */
    fun initialize() {
        if (sdk != null) return
        sdk = TangemSdk.init(
            activity,
            Config(
                attestationMode = AttestationTask.Mode.Offline,
                defaultDerivationPaths = mutableMapOf(
                    EllipticCurve.Secp256k1 to listOf(DerivationPath(ETH_DERIVATION_PATH)),
                ),
            ),
        )
        AppLog.app("card", "Tangem SDK 初始化完成（离线认证）")
    }

    /** One tap: reads the card and derives the secp256k1 address. */
    suspend fun scanAndDerive(): CardResult<CardSnapshot> {
        initialize()
        val tangem = sdk ?: return CardResult.Err("Tangem SDK 未初始化")
        reset()

        return suspendCancellableCoroutine { continuation ->
            tangem.scanCard { result ->
                when (result) {
                    is CompletionResult.Success -> {
                        val scanned = result.data
                        val wallet = scanned.wallets.firstOrNull { it.curve == EllipticCurve.Secp256k1 }
                        if (wallet == null) {
                            AppLog.app("card", "卡片无 secp256k1 钱包 cardId=${scanned.cardId}")
                            if (continuation.isActive) {
                                continuation.resume(CardResult.Err("卡片未找到 secp256k1 钱包，无法用于 EVM 链"))
                            }
                            return@scanCard
                        }

                        // The derivation already happened in this same NFC session
                        // (Config.defaultDerivationPaths) — never open a second one.
                        val path = DerivationPath(ETH_DERIVATION_PATH)
                        val childKey = wallet.derivedKeys[path]
                        if (childKey == null) {
                            AppLog.app(
                                "card",
                                "派生为空 path=$path keys=${wallet.derivedKeys.keys} " +
                                    "hdAllowed=${scanned.settings.isHDWalletAllowed} " +
                                    "fw=${scanned.firmwareVersion.stringValue}",
                            )
                            if (continuation.isActive) {
                                continuation.resume(
                                    CardResult.Err(
                                        "派生结果为空：卡片固件可能过旧或未启用 HD 钱包。请重试扫卡。",
                                    ),
                                )
                            }
                            return@scanCard
                        }

                        val publicKey = childKey.publicKey.toDecompressedPublicKey()
                        val address = addressFromPublicKey(publicKey)

                        card = scanned
                        masterWallet = wallet
                        derivedPublicKey = publicKey
                        val info = CardSnapshot(
                            cardId = scanned.cardId,
                            firmware = scanned.firmwareVersion.stringValue,
                            isAccessCodeSet = scanned.isAccessCodeSet,
                            address = address,
                            publicKeyHex = Numeric.toHexString(publicKey),
                        )
                        snapshot = info
                        AppLog.app("card", "派生成功 addr=$address")
                        if (continuation.isActive) continuation.resume(CardResult.Ok(info))
                    }

                    is CompletionResult.Failure -> {
                        val error = result.error
                        AppLog.app("card", "扫卡失败: ${error.customMessage} (code=${error.code})")
                        if (continuation.isActive) {
                            continuation.resume(CardResult.Err(describeFailure(error.customMessage, error.code)))
                        }
                    }
                }
            }
        }
    }

    /** Signs a 32-byte hash with the derived key; returns the raw 64-byte `r || s`. */
    suspend fun sign(txHash: ByteArray): CardResult<ByteArray> {
        val tangem = sdk ?: return CardResult.Err("Tangem SDK 未初始化")
        val currentCard = card ?: return CardResult.Err("请先扫描卡片")
        val wallet = masterWallet ?: return CardResult.Err("请先扫描卡片")

        return suspendCancellableCoroutine { continuation ->
            tangem.sign(
                hash = txHash,
                walletPublicKey = wallet.publicKey,
                cardId = currentCard.cardId,
                derivationPath = DerivationPath(ETH_DERIVATION_PATH),
                initialMessage = null,
            ) { result ->
                when (result) {
                    is CompletionResult.Success -> {
                        val signature = result.data.signature
                        AppLog.app("card", "NFC 签名返回 ${signature.size} bytes")
                        if (signature.size != 64) {
                            if (continuation.isActive) {
                                continuation.resume(
                                    CardResult.Err("签名长度异常：${signature.size} 字节（期望 64）"),
                                )
                            }
                        } else if (continuation.isActive) {
                            continuation.resume(CardResult.Ok(signature))
                        }
                    }

                    is CompletionResult.Failure -> {
                        val error = result.error
                        AppLog.app("card", "签名失败: ${error.customMessage} (code=${error.code})")
                        if (continuation.isActive) {
                            continuation.resume(CardResult.Err(describeFailure(error.customMessage, error.code)))
                        }
                    }
                }
            }
        }
    }

    /** Drops cached card state (e.g. after a failed scan). */
    fun reset() {
        card = null
        masterWallet = null
        derivedPublicKey = null
        snapshot = null
    }

    private fun describeFailure(message: String, code: Int): String = when (code) {
        50003 -> "NFC 会话繁忙（50003）：请将卡片贴近感应区，等待上一次会话结束后重试"
        else -> "$message（code=$code）"
    }

    private fun addressFromPublicKey(publicKey65: ByteArray): String {
        val withoutPrefix = publicKey65.copyOfRange(1, 65)
        val addressBytes = Hash.sha3(withoutPrefix).copyOfRange(12, 32)
        return Numeric.toHexString(addressBytes)
    }
}
