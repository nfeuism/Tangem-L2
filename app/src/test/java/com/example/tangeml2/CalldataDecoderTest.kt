package com.example.tangeml2

import com.example.tangeml2.data.abi.CalldataDecoder
import com.example.tangeml2.data.abi.Erc20
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class CalldataDecoderTest {

    private val recipient = "0x2222222222222222222222222222222222222222"

    @Test
    fun `ERC-20 selectors match the spec`() {
        assertTrue(Erc20.transferData(recipient, BigInteger.ONE).startsWith("0xa9059cbb"))
        assertTrue(Erc20.balanceOfData(recipient).startsWith("0x70a08231"))
        assertTrue(Erc20.decimalsData().startsWith("0x313ce567"))
    }

    @Test
    fun `encodes transfer with correct word layout`() {
        val data = Erc20.transferData(recipient, BigInteger.valueOf(1_000_000L))
        assertEquals(2 + 8 + 64 + 64, data.length)
        assertEquals("0xa9059cbb", data.substring(0, 10))
        assertEquals(recipient.removePrefix("0x").padStart(64, '0'), data.substring(10, 74))
        assertEquals(BigInteger.valueOf(1_000_000L).toString(16).padStart(64, '0'), data.substring(74))
    }

    @Test
    fun `decodes a transfer call`() {
        val data = Erc20.transferData(recipient, BigInteger.valueOf(2_500_000L))
        val decoded = CalldataDecoder.decode(data)
        assertTrue(decoded.isKnown)
        assertEquals("transfer", decoded.name)
        assertEquals("transfer(address,uint256)", decoded.signature)
        assertEquals(2, decoded.params.size)
        assertEquals(recipient, decoded.params[0].value)
        assertEquals("2500000", decoded.params[1].value)
        assertNotNull(decoded.summary)
    }

    @Test
    fun `unknown selector is reported as blind signing`() {
        val decoded = CalldataDecoder.decode("0x12345678" + "0".repeat(64))
        assertFalse(decoded.isKnown)
        assertEquals("0x12345678", decoded.selector)
        assertEquals(null, decoded.signature)
    }

    @Test
    fun `malformed calldata never throws`() {
        listOf("", "0x", "0x12", "0xzzzzzzzz", "not-hex").forEach { raw ->
            val decoded = CalldataDecoder.decode(raw)
            assertFalse("'$raw' should not decode to a known call", decoded.isKnown)
        }
    }

    @Test
    fun `decodes bytes32 token symbols`() {
        // MKR-style legacy symbol: a single right-padded 32-byte word.
        val word = "4d4b5200000000000000000000000000000000000000000000000000000000"
        assertEquals("MKR", Erc20.decodeString("0x$word"))
    }

    @Test
    fun `decodes dynamic string token symbols`() {
        val offset = "0".repeat(62) + "20"
        val length = "0".repeat(62) + "03"
        val body = "555344" + "0".repeat(58) // "USD"
        assertEquals("USD", Erc20.decodeString("0x$offset$length$body"))
    }
}
