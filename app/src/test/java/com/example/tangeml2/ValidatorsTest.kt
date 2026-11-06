package com.example.tangeml2

import com.example.tangeml2.core.util.Validators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatorsTest {

    /** Official EIP-55 test vectors. */
    @Test
    fun `produces EIP-55 checksums`() {
        val vectors = listOf(
            "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed",
            "0xfB6916095ca1df60bB79Ce92cE3Ea74c37c5d359",
            "0xdbF03B407c01E7cD3CBea99509d93f8DDDC8C6FB",
            "0xD1220A0cf47c7B9Be7A2E6BA89F429762e7b9aDb",
        )
        vectors.forEach { expected ->
            assertEquals(expected, Validators.toChecksumAddress(expected.lowercase()))
        }
    }

    @Test
    fun `detects checksum validity`() {
        assertTrue(Validators.hasValidChecksum("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"))
        assertFalse(Validators.hasValidChecksum("0x5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"))
        assertFalse(Validators.hasValidChecksum("0x5AAEB6053F3E94C9B9A09F33669435E7EF1BEAED"))
    }

    @Test
    fun `validates addresses`() {
        assertTrue(Validators.isValidAddress("0x0000000000000000000000000000000000000000"))
        assertFalse(Validators.isValidAddress("0x000"))
        assertFalse(Validators.isValidAddress("0000000000000000000000000000000000000000"))
        assertFalse(Validators.isValidAddress("0xZZ00000000000000000000000000000000000000"))
    }

    @Test
    fun `validates calldata`() {
        assertTrue(Validators.isValidCalldata("0xa9059cbb"))
        assertTrue(
            Validators.isValidCalldata(
                "0xa9059cbb0000000000000000000000003535353535353535353535353535353535353535" +
                    "00000000000000000000000000000000000000000000000000000000000f4240",
            ),
        )
        // Shorter than a 4-byte selector.
        assertFalse(Validators.isValidCalldata("0xa9059c"))
        // Odd length.
        assertFalse(Validators.isValidCalldata("0xa9059cb"))
        // No 0x prefix.
        assertFalse(Validators.isValidCalldata("a9059cbb"))
    }

    @Test
    fun `normalises pasted calldata`() {
        assertEquals("0xa9059cbb", Validators.normalizeCalldata(" 0xa9059cbb\n"))
        assertEquals("0xa9059cbb", Validators.normalizeCalldata("0xa9059\ncbb"))
    }

    @Test
    fun `shortens addresses for display`() {
        val address = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
        assertEquals("0x5aAe…eAed", Validators.shortAddress(address))
        assertEquals("0x5aAe", Validators.shortAddress("0x5aAe"))
    }
}
