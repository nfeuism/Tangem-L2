package com.example.tangeml2.data.tx

import com.example.tangeml2.domain.model.UnsignedTx
import org.web3j.crypto.ECDSASignature
import org.web3j.crypto.Hash
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.Sign
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * Legacy (type-0) EIP-155 transaction encoding.
 *
 * Every supported network (Mode, Scroll, zkLink Nova, Base, any custom EVM chain)
 * accepts legacy transactions, so one code path covers them all.
 */
object TxCodec {

    private const val CHAIN_ID_INC = 35L

    private fun raw(tx: UnsignedTx): RawTransaction = RawTransaction.createTransaction(
        tx.nonce,
        tx.gasPrice,
        tx.gasLimit,
        tx.to,
        tx.valueWei,
        tx.data.ifBlank { "0x" },
    )

    /** Returns (RLP pre-image, keccak256 of that pre-image) — the latter is what the card signs. */
    fun encodeAndHash(tx: UnsignedTx): Pair<ByteArray, ByteArray> {
        val encoded = TransactionEncoder.encode(raw(tx), tx.chainId)
        return encoded to Hash.sha3(encoded)
    }

    /** Hex tx hash of the unsigned transaction (useful for logging). */
    fun hashHex(tx: UnsignedTx): String = Numeric.toHexString(encodeAndHash(tx).second)

    /**
     * Assembles the signed raw transaction.
     *
     * @param rawSignature Tangem's 64-byte `r || s` (no recovery id).
     * @param publicKey65  65-byte uncompressed key, used to recover the recovery id.
     */
    fun assembleSigned(
        tx: UnsignedTx,
        rawSignature: ByteArray,
        publicKey65: ByteArray,
    ): String {
        require(rawSignature.size == 64) { "签名长度必须为 64 字节，实为 ${rawSignature.size}" }
        require(publicKey65.size == 65) { "公钥必须为 65 字节（0x04 || X || Y），实为 ${publicKey65.size}" }

        val r = BigInteger(1, rawSignature.copyOfRange(0, 32))
        val s = BigInteger(1, rawSignature.copyOfRange(32, 64))

        // EIP-2: canonicalise to low-S. Done *before* the recovery search so the
        // recovery id we find matches the signature we actually serialise.
        val canonical = ECDSASignature(r, s).toCanonicalised()

        val messageHash = encodeAndHash(tx).second
        val expected = BigInteger(1, publicKey65.copyOfRange(1, 65))

        var recoveryId = -1
        for (candidate in 0..3) {
            val recovered = try {
                Sign.recoverFromSignature(candidate, canonical, messageHash)
            } catch (_: Exception) {
                null
            }
            if (recovered != null && recovered == expected) {
                recoveryId = candidate
                break
            }
        }
        require(recoveryId >= 0) {
            "无法从签名恢复公钥：签名与派生地址不匹配，请确认派生路径 (m/44'/60'/0'/0/0)"
        }

        // EIP-155: v = recId + 35 + chainId * 2.
        // BigInteger is mandatory: Scroll (534352) yields v ≈ 1,068,739, far beyond one byte.
        val v = BigInteger.valueOf(recoveryId.toLong())
            .add(BigInteger.valueOf(CHAIN_ID_INC))
            .add(BigInteger.valueOf(tx.chainId).multiply(BigInteger.valueOf(2L)))

        val signatureData = Sign.SignatureData(
            v.toByteArray(),
            Numeric.toBytesPadded(canonical.r, 32),
            Numeric.toBytesPadded(canonical.s, 32),
        )

        return Numeric.toHexString(TransactionEncoder.encode(raw(tx), signatureData))
    }
}
