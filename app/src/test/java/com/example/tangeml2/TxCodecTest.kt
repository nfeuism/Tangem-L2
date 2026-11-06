package com.example.tangeml2

import com.example.tangeml2.data.tx.TxCodec
import com.example.tangeml2.domain.model.UnsignedTx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.Sign
import org.web3j.crypto.TransactionDecoder
import org.web3j.crypto.TransactionEncoder
import org.web3j.crypto.SignedRawTransaction
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * Verifies the highest-risk code in the app: recovering the ECDSA recovery id that
 * Tangem does not return, and assembling an EIP-155 signed transaction.
 *
 * Uses the official EIP-155 test vector plus a differential check against web3j's
 * own reference signer.
 */
class TxCodecTest {

    private val privateKey = BigInteger("4646464646464646464646464646464646464646464646464646464646464646", 16)
    private val keyPair = ECKeyPair.create(privateKey)
    private val publicKey65: ByteArray =
        Numeric.hexStringToByteArray("04" + Numeric.toHexStringNoPrefixZeroPadded(keyPair.publicKey, 128))

    private fun vectorTx(chainId: Long) = UnsignedTx(
        chainId = chainId,
        nonce = BigInteger.valueOf(9),
        gasPrice = BigInteger.valueOf(20_000_000_000L),
        gasLimit = BigInteger.valueOf(21_000L),
        to = "0x3535353535353535353535353535353535353535",
        valueWei = BigInteger("1000000000000000000"),
        data = "0x",
    )

    /** The signing hash published in EIP-155 for its reference transaction. */
    @Test
    fun `EIP-155 reference signing hash matches spec`() {
        val (_, hash) = TxCodec.encodeAndHash(vectorTx(chainId = 1))
        assertEquals(
            "0xdaf5a779ae972f972197303d7b574746c7ef83eadac0f2791ad23db92e4c8e53",
            Numeric.toHexString(hash),
        )
    }

    /** Our assembly must byte-for-byte match web3j's reference signer. */
    @Test
    fun `assembled signature matches web3j reference encoder`() {
        val tx = vectorTx(chainId = 1)
        val (_, hash) = TxCodec.encodeAndHash(tx)

        val cardSignature = Sign.signMessage(hash, keyPair, false)
        val raw64 = cardSignature.r + cardSignature.s
        assertEquals(64, raw64.size)

        val ours = TxCodec.assembleSigned(tx, raw64, publicKey65)

        val referenceRaw = RawTransaction.createTransaction(
            tx.nonce, tx.gasPrice, tx.gasLimit, tx.to, tx.valueWei, "0x",
        )
        val reference = Numeric.toHexString(
            TransactionEncoder.signMessage(referenceRaw, 1L, Credentials.create(keyPair)),
        )

        assertEquals(reference, ours)
    }

    /** Scroll's chainId makes v exceed one byte; it must survive RLP encoding. */
    @Test
    fun `large chain id produces a valid EIP-155 v`() {
        val chainId = 534_352L
        val tx = vectorTx(chainId)
        val (_, hash) = TxCodec.encodeAndHash(tx)
        val cardSignature = Sign.signMessage(hash, keyPair, false)
        val signed = TxCodec.assembleSigned(tx, cardSignature.r + cardSignature.s, publicKey65)

        val decoded = TransactionDecoder.decode(signed) as SignedRawTransaction
        val v = BigInteger(decoded.signatureData.v)

        // v = recId + 35 + chainId * 2
        val recId = v.subtract(BigInteger.valueOf(35L))
            .subtract(BigInteger.valueOf(chainId).multiply(BigInteger.valueOf(2L)))
        assertTrue("recovery id must be 0 or 1 but v=$v", recId == BigInteger.ZERO || recId == BigInteger.ONE)
        assertTrue("v must not fit in one byte for Scroll, v=$v", v > BigInteger.valueOf(255L))

        // The node derives the sender from v/r/s, so a wrong v means a wrong sender.
        assertEquals("0x" + org.web3j.crypto.Keys.getAddress(keyPair), decoded.from)
    }

    /** A mismatched public key must be rejected instead of broadcasting a bad signature. */
    @Test(expected = IllegalArgumentException::class)
    fun `wrong public key is rejected`() {
        val tx = vectorTx(chainId = 1)
        val (_, hash) = TxCodec.encodeAndHash(tx)
        val cardSignature = Sign.signMessage(hash, keyPair, false)
        val otherKey = ECKeyPair.create(BigInteger.valueOf(123456789L))
        val wrongPublicKey =
            Numeric.hexStringToByteArray("04" + Numeric.toHexStringNoPrefixZeroPadded(otherKey.publicKey, 128))

        TxCodec.assembleSigned(tx, cardSignature.r + cardSignature.s, wrongPublicKey)
    }

    @Test
    fun `hashHex matches encodeAndHash`() {
        val tx = vectorTx(chainId = 1)
        assertEquals(Numeric.toHexString(TxCodec.encodeAndHash(tx).second), TxCodec.hashHex(tx))
    }
}
