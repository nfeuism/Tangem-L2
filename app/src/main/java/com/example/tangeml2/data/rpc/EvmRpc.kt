package com.example.tangeml2.data.rpc

import kotlinx.coroutines.delay
import org.json.JSONObject
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * Typed Ethereum JSON-RPC surface used by the app.
 *
 * Gas pricing intentionally uses the node's own `eth_gasPrice` with a small
 * buffer: forcing a 1 gwei floor overpaid by ~8x on zkLink Nova and could make
 * low-balance accounts fail with "insufficient funds".
 */
class EvmRpc(private val client: JsonRpcClient = JsonRpcClient()) {

    suspend fun chainId(rpcUrl: String): Long =
        Numeric.toBigInt(client.resultString(rpcUrl, "eth_chainId", emptyList())).toLong()

    suspend fun blockNumber(rpcUrl: String): BigInteger =
        Numeric.toBigInt(client.resultString(rpcUrl, "eth_blockNumber", emptyList()))

    suspend fun balance(rpcUrl: String, address: String): BigInteger {
        val raw = client.resultString(rpcUrl, "eth_getBalance", listOf(address, "latest"))
        return parseQuantity(raw)
    }

    /** Uses "pending" so two sends in a row do not reuse a nonce. */
    suspend fun nonce(rpcUrl: String, address: String): BigInteger {
        val raw = client.resultString(rpcUrl, "eth_getTransactionCount", listOf(address, "pending"))
        return parseQuantity(raw)
    }

    suspend fun gasPrice(rpcUrl: String): BigInteger {
        val zeroFloor = BigInteger.valueOf(10_000_000L)   // 0.01 gwei
        val errorFloor = BigInteger.valueOf(100_000_000L) // 0.1 gwei
        return try {
            val network = parseQuantity(client.resultString(rpcUrl, "eth_gasPrice", emptyList()))
            network.multiply(BigInteger.valueOf(115L))
                .divide(BigInteger.valueOf(100L))
                .max(zeroFloor)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            errorFloor
        }
    }

    /**
     * `eth_estimateGas` with a safety buffer.
     *
     * Unlike v1 this does not swallow failures: an under-gassed contract call burns
     * gas for nothing, so the caller gets the node's reason and can show it.
     */
    suspend fun estimateGas(
        rpcUrl: String,
        from: String,
        to: String,
        data: String?,
        valueWei: BigInteger,
    ): BigInteger {
        val call = JSONObject().apply {
            put("from", from)
            put("to", to)
            put("value", "0x" + valueWei.toString(16))
            if (!data.isNullOrBlank()) put("data", data)
        }
        val estimated = parseQuantity(client.resultString(rpcUrl, "eth_estimateGas", listOf(call, "latest")))
        return estimated.multiply(BigInteger.valueOf(13L))
            .divide(BigInteger.valueOf(10L))
            .max(BigInteger.valueOf(21_000L))
    }

    suspend fun ethCall(rpcUrl: String, to: String, data: String): String? {
        val call = JSONObject().apply {
            put("to", to)
            put("data", data)
        }
        return client.resultStringOrNull(rpcUrl, "eth_call", listOf(call, "latest"))
    }

    suspend fun broadcast(rpcUrl: String, signedTxHex: String): String =
        client.resultString(rpcUrl, "eth_sendRawTransaction", listOf(signedTxHex))

    /** `1` success, `0` reverted, `null` not mined yet. */
    suspend fun receiptStatus(rpcUrl: String, txHash: String): Int? {
        val envelope = client.raw(rpcUrl, "eth_getTransactionReceipt", listOf(txHash))
        if (envelope.isNull("result")) return null
        val receipt = envelope.optJSONObject("result") ?: return null
        val status = receipt.optString("status")
        if (status.isBlank()) return null
        return if (Numeric.toBigInt(status).signum() == 0) 0 else 1
    }

    /**
     * Polls for a receipt until [timeoutMs] elapses.
     * [onPoll] receives the elapsed milliseconds so the UI can show progress.
     */
    suspend fun awaitReceipt(
        rpcUrl: String,
        txHash: String,
        timeoutMs: Long = 40_000L,
        pollMs: Long = 2_500L,
        onPoll: (Long) -> Unit = {},
    ): Int? {
        val started = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - started
            onPoll(elapsed)
            val status = runCatching { receiptStatus(rpcUrl, txHash) }.getOrNull()
            if (status != null) return status
            if (elapsed >= timeoutMs) return null
            delay(pollMs)
        }
    }

    private fun parseQuantity(raw: String): BigInteger {
        val value = raw.trim()
        if (value.isEmpty() || value == "0x") return BigInteger.ZERO
        return Numeric.toBigInt(value)
    }
}
