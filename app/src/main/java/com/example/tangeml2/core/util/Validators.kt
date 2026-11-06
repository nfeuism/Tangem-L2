package com.example.tangeml2.core.util

import org.web3j.crypto.Hash
import org.web3j.utils.Numeric

/** Input validation and address formatting helpers. */
object Validators {

    private val ADDRESS = Regex("^0x[0-9a-fA-F]{40}$")
    private val CALLDATA = Regex("^0x[0-9a-fA-F]*$")

    /** `0x` + exactly 40 hex characters. */
    fun isValidAddress(value: String): Boolean = ADDRESS.matches(value.trim())

    /** `0x` + at least 4 bytes, even length, hex only. */
    fun isValidCalldata(value: String): Boolean {
        val v = value.trim()
        if (!CALLDATA.matches(v)) return false
        val body = v.length - 2
        return body >= 8 && body % 2 == 0
    }

    /** Trims, lower-cases the `0x` prefix, keeps the rest untouched. */
    fun normalizeAddress(value: String): String {
        val v = value.trim()
        return when {
            v.startsWith("0x") -> "0x" + v.substring(2)
            v.startsWith("0X") -> "0x" + v.substring(2)
            else -> v
        }
    }

    /** Strips all whitespace/newlines so pasted calldata can be used verbatim. */
    fun normalizeCalldata(value: String): String =
        value.replace(Regex("\\s"), "")

    /** EIP-55 mixed-case checksum representation. */
    fun toChecksumAddress(value: String): String {
        val v = normalizeAddress(value)
        if (!isValidAddress(v)) return v
        val body = v.substring(2).lowercase()
        val hash = Numeric.toHexStringNoPrefix(Hash.sha3(body.toByteArray(Charsets.US_ASCII)))
        val out = StringBuilder("0x")
        body.forEachIndexed { index, ch ->
            if (ch in '0'..'9') {
                out.append(ch)
            } else {
                val nibble = Character.digit(hash[index], 16)
                out.append(if (nibble >= 8) ch.uppercaseChar() else ch)
            }
        }
        return out.toString()
    }

    /** `0x1234…abcd` for compact display. */
    fun shortAddress(value: String, head: Int = 6, tail: Int = 4): String {
        val v = value.trim()
        if (v.length <= head + tail + 1) return v
        return v.take(head) + "…" + v.takeLast(tail)
    }

    /** True when the value is a valid EIP-55 checksum (all-lower/all-upper input is not). */
    fun hasValidChecksum(value: String): Boolean {
        val v = normalizeAddress(value)
        if (!isValidAddress(v)) return false
        val body = v.substring(2)
        if (body == body.lowercase() || body == body.uppercase()) return false
        return toChecksumAddress(v) == v
    }
}
