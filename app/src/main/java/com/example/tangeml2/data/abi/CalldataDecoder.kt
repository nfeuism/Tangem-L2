package com.example.tangeml2.data.abi

import com.example.tangeml2.core.util.Validators
import org.web3j.utils.Numeric
import java.math.BigInteger

/** One decoded argument of a contract call. */
data class DecodedParam(
    val name: String,
    val type: String,
    val value: String,
)

/** Best-effort human reading of calldata, used by the QR review and confirm sheets. */
data class DecodedCall(
    val selector: String,
    /** e.g. `transfer(address,uint256)`; null when the selector is unknown. */
    val signature: String?,
    /** e.g. `transfer`. */
    val name: String?,
    val params: List<DecodedParam> = emptyList(),
    /** Short human sentence, e.g. `向 0x1234…abcd 转账 1.5 TOKEN`. */
    val summary: String? = null,
) {
    val isKnown: Boolean get() = signature != null
}

/**
 * Decodes the 4-byte selector plus statically-sized arguments for a curated set of
 * well-known methods (ERC-20, ERC-721, staking/bridge/claim entry points).
 *
 * Unknown selectors are reported as such — the UI must warn that the user is
 * blind-signing rather than pretend the call was understood.
 */
object CalldataDecoder {

    private val REGISTRY: Map<String, String> by lazy {
        val signatures = listOf(
            // ERC-20
            "transfer(address,uint256)",
            "transferFrom(address,address,uint256)",
            "approve(address,uint256)",
            "balanceOf(address)",
            "allowance(address,address)",
            "decimals()",
            "symbol()",
            "name()",
            "totalSupply()",
            // ERC-721 / 1155
            "setApprovalForAll(address,bool)",
            "safeTransferFrom(address,address,uint256)",
            "safeTransferFrom(address,address,uint256,bytes)",
            "mint(address,uint256)",
            "burn(uint256)",
            // DeFi / staking / bridging shapes seen on L2s
            "withdraw(uint256)",
            "withdraw()",
            "deposit()",
            "deposit(uint256)",
            "stake(uint256)",
            "unstake(uint256)",
            "claim()",
            "claim(address,uint256)",
            "redeem(uint256)",
            "redeem(address,uint256)",
            "harvest()",
            "bridge(address,uint256)",
            "depositETH(address,uint256)",
            "claimRewards(address[])",
            "execute(bytes)",
        )
        signatures.associateBy { selectorOf(it) }
    }

    private fun selectorOf(signature: String): String =
        Numeric.toHexStringNoPrefix(
            org.web3j.crypto.Hash.sha3(signature.toByteArray(Charsets.US_ASCII)).copyOfRange(0, 4),
        )

    /** Selector -> canonical signature registry backing [decode]. */
    fun knownSelectors(): Map<String, String> = REGISTRY

    fun decode(calldata: String): DecodedCall {
        val normalized = Validators.normalizeCalldata(calldata)
        if (!normalized.startsWith("0x") && !normalized.startsWith("0X")) {
            return DecodedCall("", null, null)
        }
        val body = normalized.substring(2)
        if (body.length < 8 || body.length % 2 != 0 || !body.all { it.isHex() }) {
            return DecodedCall(if (body.length >= 8) "0x" + body.take(8).lowercase() else "", null, null)
        }
        val selector = "0x" + body.take(8).lowercase()
        val signature = REGISTRY[selector.removePrefix("0x")]
            ?: REGISTRY.entries.firstOrNull { it.key == selector }?.value

        if (signature == null) {
            return DecodedCall(selector, null, null)
        }

        val types = parseTypes(signature)
        val argsHex = body.substring(8)
        val params = mutableListOf<DecodedParam>()
        val names = paramNames(signature, types)
        var cursor = 0
        var dynamicFrom = -1

        for ((index, type) in types.withIndex()) {
            val word = argsHex.substring(cursor.coerceAtMost(argsHex.length), (cursor + 64).coerceAtMost(argsHex.length))
            if (word.length < 64) {
                dynamicFrom = index
                break
            }
            when {
                type == "address" -> params += DecodedParam(
                    names[index], type, "0x" + word.takeLast(40),
                )
                type.startsWith("uint") || type.startsWith("int") -> {
                    val value = try {
                        BigInteger(word, 16)
                    } catch (_: Exception) {
                        BigInteger.ZERO
                    }
                    params += DecodedParam(names[index], type, value.toString())
                }
                type == "bool" -> params += DecodedParam(
                    names[index], type, if (word.takeLast(1) == "0") "false" else "true",
                )
                type == "bytes32" -> params += DecodedParam(names[index], type, "0x" + word)
                else -> {
                    // Dynamic type (bytes / string / array): stop structured decoding.
                    params += DecodedParam(names[index], type, "0x" + word)
                    dynamicFrom = index
                }
            }
            if (dynamicFrom >= 0) break
            cursor += 64
        }

        return DecodedCall(
            selector = selector,
            signature = signature,
            name = signature.substringBefore("("),
            params = params,
            summary = summarize(signature, params, dynamicFrom),
        )
    }

    private fun summarize(signature: String, params: List<DecodedParam>, dynamicFrom: Int): String? {
        val name = signature.substringBefore("(")
        fun p(i: Int): String = params.getOrNull(i)?.value ?: "?"
        fun addr(i: Int): String = Validators.shortAddress(p(i))
        return when (name) {
            "transfer" -> "向 ${addr(0)} 转账 ${p(1)}（最小单位）"
            "transferFrom" -> "从 ${addr(0)} 转给 ${addr(1)} 金额 ${p(2)}（最小单位）"
            "approve" -> "授权 ${addr(0)} 使用最多 ${p(1)}（最小单位）"
            "setApprovalForAll" -> "授权 ${addr(0)} 管理全部 NFT（${p(1)}）"
            "safeTransferFrom" -> "转移 NFT #${p(2)} 至 ${addr(1)}"
            "withdraw" -> "提取 ${p(0)}（最小单位）"
            "deposit" -> if (params.isEmpty()) "存入（无参数）" else "存入 ${p(0)}（最小单位）"
            "stake" -> "质押 ${p(0)}（最小单位）"
            "unstake" -> "解除质押 ${p(0)}（最小单位）"
            "redeem" -> "赎回 ${params.lastOrNull()?.value ?: "?"}（最小单位）"
            "claim" -> "领取奖励"
            "harvest" -> "收获奖励"
            "bridge" -> "跨链 ${p(1)}（最小单位）至 ${addr(0)}"
            "depositETH" -> "存入 ETH 至 ${addr(0)}，金额 ${p(1)}（最小单位）"
            "balanceOf", "allowance", "decimals", "symbol", "name", "totalSupply" -> "只读查询（不应作为交易发送）"
            else -> if (dynamicFrom >= 0) "参数含动态类型，仅显示前 $dynamicFrom 项" else null
        }
    }

    private fun parseTypes(signature: String): List<String> {
        val inner = signature.substringAfter("(", "").substringBeforeLast(")")
        if (inner.isBlank()) return emptyList()
        return inner.split(",").map { it.trim() }
    }

    private fun paramNames(signature: String, types: List<String>): List<String> {
        val name = signature.substringBefore("(")
        val canonical = when (name) {
            "transfer" -> listOf("to", "amount")
            "transferFrom" -> listOf("from", "to", "amount")
            "approve" -> listOf("spender", "amount")
            "allowance" -> listOf("owner", "spender")
            "balanceOf" -> listOf("owner")
            "mint" -> listOf("to", "amount")
            "burn" -> listOf("amount")
            "setApprovalForAll" -> listOf("operator", "approved")
            "safeTransferFrom" -> listOf("from", "to", "tokenId", "data")
            "withdraw" -> listOf("amount")
            "deposit" -> listOf("amount")
            "stake" -> listOf("amount")
            "unstake" -> listOf("amount")
            "redeem" -> listOf("owner", "amount")
            "claim" -> listOf("owner", "amount")
            "bridge" -> listOf("to", "amount")
            "depositETH" -> listOf("to", "amount")
            else -> emptyList()
        }
        return types.indices.map { i -> canonical.getOrNull(i) ?: "arg$i" }
    }

    private fun Char.isHex(): Boolean = this.isDigit() || lowercaseChar() in 'a'..'f'
}
