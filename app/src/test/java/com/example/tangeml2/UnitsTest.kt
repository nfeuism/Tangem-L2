package com.example.tangeml2

import com.example.tangeml2.core.util.AmountFormatException
import com.example.tangeml2.core.util.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigInteger

class UnitsTest {

    @Test
    fun `parses whole and fractional amounts exactly`() {
        assertEquals(BigInteger("1500000000000000000"), Units.parseAmountToRaw("1.5", 18))
        assertEquals(BigInteger("1000000"), Units.parseAmountToRaw("1", 6))
        assertEquals(BigInteger("123456"), Units.parseAmountToRaw("0.123456", 6))
        assertEquals(BigInteger.ZERO, Units.parseAmountToRaw("0", 18))
        assertEquals(BigInteger("500000000000000000"), Units.parseAmountToRaw(".5", 18))
        assertEquals(BigInteger("2000000000000000000"), Units.parseAmountToRaw("2.", 18))
    }

    /** No Double round-trip: 0.1 + 0.2 style drift must be impossible. */
    @Test
    fun `keeps full 18 decimal precision`() {
        val raw = Units.parseAmountToRaw("0.000000000000000001", 18)
        assertEquals(BigInteger.ONE, raw)
        assertEquals("0.000000000000000001", Units.formatExact(raw, 18))
    }

    @Test
    fun `rejects more precision than the token supports`() {
        assertThrows(AmountFormatException::class.java) {
            Units.parseAmountToRaw("1.1234567", 6)
        }
    }

    @Test
    fun `rejects malformed and negative input`() {
        assertThrows(AmountFormatException::class.java) { Units.parseAmountToRaw("", 18) }
        assertThrows(AmountFormatException::class.java) { Units.parseAmountToRaw("-1", 18) }
        assertThrows(AmountFormatException::class.java) { Units.parseAmountToRaw("1,5", 18) }
        assertThrows(AmountFormatException::class.java) { Units.parseAmountToRaw("abc", 18) }
        assertThrows(AmountFormatException::class.java) { Units.parseAmountToRaw("1e18", 18) }
    }

    @Test
    fun `formats without scientific notation`() {
        assertEquals("1000", Units.formatExact(BigInteger("1000000000000000000000"), 18))
        assertEquals("0", Units.formatExact(BigInteger.ZERO, 18))
        assertEquals("0.125", Units.formatGwei(BigInteger.valueOf(125_000_000L)))
    }

    @Test
    fun `display formatting rounds only for labels`() {
        assertEquals("1.234568", Units.formatDisplay(BigInteger("1234567890000000000"), 18, 6))
        // Exact formatting must never round.
        assertEquals("1.23456789", Units.formatExact(BigInteger("1234567890000000000"), 18))
    }

    @Test
    fun `round trips through parse and format`() {
        listOf("1", "0.1", "123.456789", "0.000000000000000001").forEach { text ->
            val raw = Units.parseAmountToRaw(text, 18)
            assertEquals(text, Units.formatExact(raw, 18))
        }
    }
}
