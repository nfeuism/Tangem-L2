package com.example.tangeml2.data.token

import com.example.tangeml2.data.abi.Erc20
import com.example.tangeml2.data.rpc.EvmRpc
import com.example.tangeml2.domain.model.TokenInfo
import java.math.BigInteger

/**
 * ERC-20 metadata + balance lookups.
 *
 * `decimals()` failures are fatal on purpose: defaulting to 18 for a 6-decimal
 * token would inflate the amount by 10^12 and send the user's whole balance.
 */
class TokenService(private val rpc: EvmRpc) {

    /** Throws when the contract does not answer `decimals()`. */
    suspend fun decimalsOrThrow(rpcUrl: String, contract: String): Int {
        val raw = rpc.ethCall(rpcUrl, contract, Erc20.decimalsData())
        val value = Erc20.decodeUint(raw)
            ?: throw IllegalStateException("无法读取代币精度 decimals()，已中止以防金额算错。请先点「查询代币信息」确认。")
        if (value.signum() < 0 || value > BigInteger.valueOf(77L)) {
            throw IllegalStateException("代币精度返回值异常：$value")
        }
        return value.toInt()
    }

    suspend fun symbol(rpcUrl: String, contract: String): String {
        val raw = runCatching { rpc.ethCall(rpcUrl, contract, Erc20.symbolData()) }.getOrNull()
        return Erc20.decodeString(raw)?.takeIf { it.isNotBlank() } ?: "?"
    }

    suspend fun balanceOf(rpcUrl: String, contract: String, owner: String): BigInteger {
        val raw = rpc.ethCall(rpcUrl, contract, Erc20.balanceOfData(owner))
        return Erc20.decodeUint(raw) ?: BigInteger.ZERO
    }

    /** Loads symbol + decimals + balance in one go; throws if decimals is unavailable. */
    suspend fun load(rpcUrl: String, contract: String, owner: String): TokenInfo {
        val decimals = decimalsOrThrow(rpcUrl, contract)
        val symbol = symbol(rpcUrl, contract)
        val balance = balanceOf(rpcUrl, contract, owner)
        return TokenInfo(address = contract, symbol = symbol, decimals = decimals, balanceRaw = balance)
    }
}
