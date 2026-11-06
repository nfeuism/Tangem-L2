package com.example.tangeml2.data.abi

import org.web3j.crypto.Hash
import org.web3j.utils.Numeric
import java.math.BigInteger

/** ERC-20 ABI encoding/decoding without pulling in a full ABI codec. */
object Erc20 {

    private fun selector(signature: String): ByteArray =
        Hash.sha3(signature.toByteArray(Charsets.US_ASCII)).copyOfRange(0, 4)

    private val SEL_TRANSFER = selector("transfer(address,uint256)")
    private val SEL_BALANCE_OF = selector("balanceOf(address)")
    private val SEL_DECIMALS = selector("decimals()")
    private val SEL_SYMBOL = selector("symbol()")
    private val SEL_NAME = selector("name()")

    fun transferData(to: String, amountRaw: BigInteger): String {
        val toWord = Numeric.toBytesPadded(Numeric.toBigInt(to.trim()), 32)
        val amountWord = Numeric.toBytesPadded(amountRaw, 32)
        return Numeric.toHexString(SEL_TRANSFER + toWord + amountWord)
    }

    fun balanceOfData(owner: String): String {
        val ownerWord = Numeric.toBytesPadded(Numeric.toBigInt(owner.trim()), 32)
        return Numeric.toHexString(SEL_BALANCE_OF + ownerWord)
    }

    fun decimalsData(): String = Numeric.toHexString(SEL_DECIMALS)

    fun symbolData(): String = Numeric.toHexString(SEL_SYMBOL)

    fun nameData(): String = Numeric.toHexString(SEL_NAME)

    /** Decodes a single uint256 return value. */
    fun decodeUint(hex: String?): BigInteger? {
        val body = hexBody(hex) ?: return null
        if (body.isEmpty()) return null
        return try {
            Numeric.toBigInt("0x" + body)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decodes a `string` return value.
     *
     * Handles both the dynamic `string` ABI layout (offset/length/bytes) and the
     * legacy `bytes32` layout that older tokens such as MKR use.
     */
    fun decodeString(hex: String?): String? {
        val body = hexBody(hex) ?: return null
        val bytes = try {
            Numeric.hexStringToByteArray("0x" + body)
        } catch (_: Exception) {
            return null
        }
        if (bytes.isEmpty()) return null

        // Legacy bytes32: exactly one word, right-padded with zeros.
        if (bytes.size == 32) {
            val text = String(bytes, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
            if (text.isNotEmpty() && text.all { it.code in 32..126 || it.code > 160 }) return text
        }

        // Dynamic string: [0..32)=offset, [32..64)=length, then the payload.
        if (bytes.size >= 64) {
            val length = try {
                BigInteger(1, bytes.copyOfRange(32, 64)).toInt()
            } catch (_: Exception) {
                -1
            }
            if (length in 0..(bytes.size - 64)) {
                return String(bytes.copyOfRange(64, 64 + length), Charsets.UTF_8).trim()
            }
        }

        // Fall back to the bytes32 interpretation for short payloads.
        return String(bytes, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }.ifEmpty { null }
    }

    private fun hexBody(hex: String?): String? {
        if (hex == null) return null
        val v = hex.trim()
        if (v.isEmpty() || v == "0x") return null
        val body = if (v.startsWith("0x") || v.startsWith("0X")) v.substring(2) else v
        if (body.isEmpty() || body.length % 2 != 0) return null
        if (!body.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return body
    }
}
