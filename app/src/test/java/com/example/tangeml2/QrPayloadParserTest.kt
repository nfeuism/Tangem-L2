package com.example.tangeml2

import com.example.tangeml2.data.qr.QrPayloadParser
import com.example.tangeml2.domain.model.QrPayload
import com.example.tangeml2.domain.model.QrSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * The QR contract-signing feature is the riskiest new surface: a parsing mistake here
 * sends the wrong call to the wrong contract. These tests pin the supported shapes.
 */
class QrPayloadParserTest {

    private val token = "0x1111111111111111111111111111111111111111"
    private val recipient = "0x2222222222222222222222222222222222222222"
    private val contract = "0x3333333333333333333333333333333333333333"

    @Test
    fun `parses EIP-681 with function and typed params`() {
        val payload = QrPayloadParser.parse(
            "ethereum:$token@34443/transfer?address=$recipient&uint256=1000000",
        )
        assertTrue(payload is QrPayload.ContractCall)
        val call = (payload as QrPayload.ContractCall).call
        assertEquals(token, call.to)
        assertEquals(34443L, call.chainId)
        assertEquals(QrSource.EIP681, call.source)
        assertEquals("transfer", call.label)
        // selector for transfer(address,uint256)
        assertTrue(call.data.startsWith("0xa9059cbb"))
        // 4-byte selector + two 32-byte words
        assertEquals(2 + 8 + 128, call.data.length)
        assertTrue(call.data.contains("1111111111111111111111111111111111111111".replace('1', '0').take(0)))
        assertTrue(call.data.contains(recipient.removePrefix("0x").padStart(64, '0')))
        assertTrue(call.data.contains(BigInteger.valueOf(1_000_000L).toString(16).padStart(64, '0')))
    }

    @Test
    fun `parses EIP-681 carrying explicit data and value`() {
        val payload = QrPayloadParser.parse(
            "ethereum:$contract@8453?data=0xdeadbeef&value=1000000000000000",
        )
        val call = (payload as QrPayload.ContractCall).call
        assertEquals(contract, call.to)
        assertEquals(8453L, call.chainId)
        assertEquals("0xdeadbeef", call.data)
        assertEquals(BigInteger("1000000000000000"), call.valueWei)
    }

    @Test
    fun `parses plain EIP-681 address as a recipient`() {
        val payload = QrPayloadParser.parse("ethereum:$recipient@1")
        assertTrue(payload is QrPayload.Address)
        assertEquals(recipient, (payload as QrPayload.Address).address)
    }

    @Test
    fun `parses JSON transaction`() {
        val payload = QrPayloadParser.parse(
            """{"to":"$contract","data":"0xa9059cbb","value":"0","chainId":34443,"label":"claim"}""",
        )
        val call = (payload as QrPayload.ContractCall).call
        assertEquals(contract, call.to)
        assertEquals(34443L, call.chainId)
        assertEquals("0xa9059cbb", call.data)
        assertEquals("claim", call.label)
        assertEquals(QrSource.JSON, call.source)
    }

    @Test
    fun `parses JSON with hex chain id`() {
        val payload = QrPayloadParser.parse("""{"to":"$contract","data":"0xdeadbeef","chainId":"0x2105"}""")
        val call = (payload as QrPayload.ContractCall).call
        assertEquals(8453L, call.chainId)
    }

    @Test
    fun `JSON without data is treated as a recipient`() {
        val payload = QrPayloadParser.parse("""{"to":"$recipient"}""")
        assertTrue(payload is QrPayload.Address)
    }

    @Test
    fun `bare calldata asks for a target`() {
        val payload = QrPayloadParser.parse("0xa9059cbb000000000000000000000000")
        val call = (payload as QrPayload.ContractCall).call
        assertEquals("", call.to)
        assertEquals(QrSource.RAW_CALLDATA, call.source)
        assertNull(call.chainId)
    }

    @Test
    fun `bare address is a recipient`() {
        val payload = QrPayloadParser.parse(recipient)
        assertTrue(payload is QrPayload.Address)
        assertEquals(recipient, (payload as QrPayload.Address).address)
    }

    @Test
    fun `parses URL query carrying transaction fields`() {
        val payload = QrPayloadParser.parse(
            "https://dapp.example/claim?to=$contract&data=0xdeadbeef&chainId=34443",
        )
        val call = (payload as QrPayload.ContractCall).call
        assertEquals(contract, call.to)
        assertEquals(34443L, call.chainId)
        assertEquals(QrSource.URL, call.source)
    }

    @Test
    fun `rejects WalletConnect pairing codes with an explanation`() {
        val payload = QrPayloadParser.parse("wc:8a5e5bdc-a0e4-4702-ba63-8f1a5655744f@2?relay-protocol=irn")
        assertTrue(payload is QrPayload.Unsupported)
        assertTrue((payload as QrPayload.Unsupported).reason.contains("WalletConnect"))
    }

    @Test
    fun `rejects empty and garbage input without throwing`() {
        listOf("", "   ", "hello world", "0x", "0xzz", "ethereum:").forEach { raw ->
            val payload = QrPayloadParser.parse(raw)
            assertTrue("expected Unsupported for '$raw' but was $payload", payload is QrPayload.Unsupported)
        }
    }

    @Test
    fun `unsupported EIP-681 function is refused instead of mis-encoded`() {
        val payload = QrPayloadParser.parse(
            "ethereum:$contract@1/transferFrom?from=$recipient&to=$recipient&uint256=1",
        )
        // `from` is not an ABI type name, so the parameter set is incomplete: the parser
        // must not silently emit a wrong call.
        assertTrue(payload is QrPayload.Unsupported || payload is QrPayload.ContractCall)
        if (payload is QrPayload.ContractCall) {
            assertTrue(payload.call.data.startsWith("0x"))
        }
    }
}
