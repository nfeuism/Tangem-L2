package com.example.tangeml2.data.qr

import com.example.tangeml2.core.util.Validators
import com.example.tangeml2.domain.model.QrContractCall
import com.example.tangeml2.domain.model.QrPayload
import com.example.tangeml2.domain.model.QrSource
import org.json.JSONObject
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URLDecoder

/**
 * Turns raw QR text into an executable request.
 *
 * Accepted shapes:
 *  1. EIP-681 — `ethereum:0xContract@34443/transfer?address=0x..&uint256=1`
 *  2. EIP-681 without a function, carrying `data=` / `value=` query parameters
 *  3. JSON — `{"to":"0x..","data":"0x..","value":"0","chainId":34443}`
 *  4. Bare calldata — `0x` + at least 4 bytes (target address asked separately)
 *  5. Bare address — treated as a recipient
 *  6. Any URL whose query string carries `to` / `data` / `chainId` / `value`
 *
 * WalletConnect pairing URIs (`wc:…`) are recognised and reported as unsupported
 * rather than silently ignored.
 *
 * This parser never throws: unparseable input becomes [QrPayload.Unsupported] with a
 * human-readable reason so the UI can explain the problem.
 */
object QrPayloadParser {

    private val META_KEYS = setOf(
        "value", "gas", "gaslimit", "chainid", "data", "calldata", "label", "name", "description", "function",
    )
    private val STATIC_TYPE = Regex("^(address|bool|bytes32|u?int[0-9]{0,3})$")

    fun parse(raw: String): QrPayload {
        val text = raw.trim()
        if (text.isEmpty()) return QrPayload.Unsupported("二维码内容为空", raw)
        return try {
            parseInternal(text)
        } catch (e: Exception) {
            QrPayload.Unsupported("无法解析二维码内容：${e.message ?: "格式不支持"}", text)
        }
    }

    private fun parseInternal(text: String): QrPayload {
        val lower = text.lowercase()
        if (lower.startsWith("wc:") || lower.startsWith("wc@")) {
            return QrPayload.Unsupported(
                "这是 WalletConnect 配对码。请使用支持 WalletConnect 的钱包连接该 dApp。",
                text,
            )
        }
        if (lower.startsWith("ethereum:")) return parseEip681(text)
        if (text.startsWith("{")) return parseJson(text)
        if (Validators.isValidAddress(text)) {
            return QrPayload.Address(Validators.normalizeAddress(text))
        }
        if (isBareCalldata(text)) {
            return QrPayload.ContractCall(
                QrContractCall(
                    to = "",
                    chainId = null,
                    valueWei = null,
                    data = Validators.normalizeCalldata(text).lowercase(),
                    gasLimit = null,
                    label = null,
                    source = QrSource.RAW_CALLDATA,
                    raw = text,
                ),
            )
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) return parseUrl(text)
        return QrPayload.Unsupported("未识别的二维码格式（不是地址、calldata、EIP-681 或 JSON）", text)
    }

    // ── EIP-681 ──────────────────────────────────────────────────────────────

    private fun parseEip681(text: String): QrPayload {
        val rest = text.substring("ethereum:".length)
        val queryIndex = rest.indexOf('?')
        val queryRaw = if (queryIndex >= 0) rest.substring(queryIndex + 1) else ""
        var path = (if (queryIndex >= 0) rest.substring(0, queryIndex) else rest).substringBefore('#')

        var function = ""
        val slash = path.indexOf('/')
        if (slash >= 0) {
            function = path.substring(slash + 1).trim()
            path = path.substring(0, slash)
        }

        var chainId: Long? = null
        val at = path.indexOf('@')
        if (at >= 0) {
            chainId = path.substring(at + 1).trim().toLongOrNull()
            path = path.substring(0, at)
        }

        var target = path.trim()
        if (target.startsWith("pay-", ignoreCase = true)) target = target.substring(4).trim()

        val params = parseQuery(queryRaw)
        if (chainId == null) chainId = params["chainid"]?.let { parseChainId(it) }
        val valueWei = params["value"]?.let { parseQuantity(it) }
        val gasLimit = (params["gas"] ?: params["gaslimit"])?.let { parseQuantity(it) }

        val to = if (Validators.isValidAddress(target)) Validators.normalizeAddress(target) else ""

        val explicitData = params["data"] ?: params["calldata"]
        val data: String? = when {
            !explicitData.isNullOrBlank() -> normalizeData(explicitData)
            function.isNotBlank() -> encodeFunction(function, params)
            else -> null
        }

        if (data == null) {
            // No calldata: either a plain recipient or an unsupported function signature.
            return when {
                to.isNotEmpty() && function.isBlank() -> QrPayload.Address(to)
                to.isNotEmpty() -> QrPayload.Unsupported(
                    "EIP-681 的函数 `$function` 无法自动编码（含动态类型或未知参数）。请在 dApp 内获取 calldata 后使用「合约调用」手动填写。",
                    text,
                )
                else -> QrPayload.Unsupported("EIP-681 链接缺少有效的合约地址", text)
            }
        }

        if (to.isEmpty()) {
            return QrPayload.Unsupported("EIP-681 链接缺少合约地址，无法确定调用目标", text)
        }

        return QrPayload.ContractCall(
            QrContractCall(
                to = to,
                chainId = chainId,
                valueWei = valueWei,
                data = data,
                gasLimit = gasLimit,
                label = function.ifBlank { null },
                source = QrSource.EIP681,
                raw = text,
            ),
        )
    }

    /**
     * Builds calldata for `/<function>?<type>=<value>&…`.
     *
     * EIP-681 names parameters by ABI type, so the canonical signature can be
     * derived directly. Dynamic types are refused rather than mis-encoded.
     */
    private fun encodeFunction(function: String, params: Map<String, String>): String? {
        val name = function.substringBefore('(').trim()
        if (name.isEmpty()) return null

        val typed = params.entries
            .filter { it.key !in META_KEYS }
            .map { it.key.lowercase() to it.value }
            .filter { (key, _) -> STATIC_TYPE.matches(key) }

        if (typed.isEmpty()) return null

        val types = typed.map { it.first }
        val values = typed.map { it.second }
        val signature = "$name(${types.joinToString(",")})"
        val selector = Hash.sha3(signature.toByteArray(Charsets.US_ASCII)).copyOfRange(0, 4)

        val encoded = StringBuilder(Numeric.toHexStringNoPrefix(selector))
        for (index in types.indices) {
            val word = encodeStatic(types[index], values[index]) ?: return null
            encoded.append(word)
        }
        return "0x" + encoded.toString()
    }

    private fun encodeStatic(type: String, value: String): String? {
        val trimmed = value.trim()
        return try {
            when {
                type == "address" -> {
                    if (!Validators.isValidAddress(trimmed)) return null
                    Numeric.toHexStringNoPrefix(Numeric.toBytesPadded(Numeric.toBigInt(trimmed), 32))
                }
                type == "bool" -> {
                    val flag = trimmed.equals("true", true) || trimmed == "1"
                    Numeric.toHexStringNoPrefix(Numeric.toBytesPadded(if (flag) BigInteger.ONE else BigInteger.ZERO, 32))
                }
                type == "bytes32" -> {
                    val body = trimmed.removePrefix("0x").removePrefix("0X")
                    if (body.length > 64 || body.any { !it.isHexDigit() }) return null
                    body.padEnd(64, '0')
                }
                type.startsWith("uint") || type.startsWith("int") -> {
                    val number = parseQuantity(trimmed) ?: return null
                    Numeric.toHexStringNoPrefix(Numeric.toBytesPadded(number, 32))
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── JSON / URL ───────────────────────────────────────────────────────────

    private fun parseJson(text: String): QrPayload {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return QrPayload.Unsupported("JSON 格式错误：${e.message}", text)
        }

        val to = firstString(json, "to", "target", "contract", "address")?.let { candidate ->
            if (Validators.isValidAddress(candidate)) Validators.normalizeAddress(candidate) else null
        }
        val data = firstString(json, "data", "calldata", "input")?.let { normalizeData(it) }
        val chainId = when {
            json.has("chainId") -> parseChainId(json.get("chainId").toString())
            json.has("chainid") -> parseChainId(json.get("chainid").toString())
            else -> null
        }
        val valueWei = firstString(json, "value", "amount", "wei")?.let { parseQuantity(it) }
        val gasLimit = firstString(json, "gas", "gasLimit", "gas_limit")?.let { parseQuantity(it) }
        val label = firstString(json, "label", "name", "description", "function", "method")

        return buildPayload(to, chainId, valueWei, data, gasLimit, label, QrSource.JSON, text)
    }

    private fun parseUrl(text: String): QrPayload {
        val queryIndex = text.indexOf('?')
        if (queryIndex < 0) {
            return QrPayload.Unsupported("链接中没有交易参数（缺少 ?to= / ?data=）", text)
        }
        val params = parseQuery(text.substring(queryIndex + 1).substringBefore('#'))
        val to = (params["to"] ?: params["target"] ?: params["contract"])
            ?.takeIf { Validators.isValidAddress(it) }
            ?.let { Validators.normalizeAddress(it) }
        val data = (params["data"] ?: params["calldata"] ?: params["input"])?.let { normalizeData(it) }
        val chainId = params["chainid"]?.let { parseChainId(it) }
        val valueWei = params["value"]?.let { parseQuantity(it) }
        val gasLimit = (params["gas"] ?: params["gaslimit"])?.let { parseQuantity(it) }
        val label = params["label"] ?: params["name"]

        if (to == null && data == null) {
            return QrPayload.Unsupported("链接中没有可执行的交易参数（缺少 to / data）", text)
        }
        return buildPayload(to, chainId, valueWei, data, gasLimit, label, QrSource.URL, text)
    }

    private fun buildPayload(
        to: String?,
        chainId: Long?,
        valueWei: BigInteger?,
        data: String?,
        gasLimit: BigInteger?,
        label: String?,
        source: QrSource,
        raw: String,
    ): QrPayload {
        if (data == null) {
            return if (to != null) {
                QrPayload.Address(to)
            } else {
                QrPayload.Unsupported("二维码中没有 calldata，也没有有效地址", raw)
            }
        }
        return QrPayload.ContractCall(
            QrContractCall(
                to = to.orEmpty(),
                chainId = chainId,
                valueWei = valueWei,
                data = data,
                gasLimit = gasLimit,
                label = label,
                source = source,
                raw = raw,
            ),
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        query.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val separator = pair.indexOf('=')
            if (separator <= 0) return@forEach
            val key = decode(pair.substring(0, separator)).trim().lowercase()
            val value = decode(pair.substring(separator + 1)).trim()
            if (key.isNotEmpty()) result[key] = value
        }
        return result
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }

    private fun firstString(json: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (!json.has(key) || json.isNull(key)) continue
            val raw = json.opt(key) ?: continue
            if (raw === JSONObject.NULL) continue
            val text = raw.toString().trim()
            if (text.isNotEmpty()) return text
        }
        return null
    }

    /** Accepts decimal, `0x` hex, and EIP-681 scientific notation such as `1e18`. */
    private fun parseQuantity(raw: String): BigInteger? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        return try {
            when {
                value.startsWith("0x") || value.startsWith("0X") -> Numeric.toBigInt(value)
                value.contains('e', ignoreCase = true) ->
                    BigDecimal(value).toBigIntegerExact()
                value.contains('.') -> BigDecimal(value).toBigIntegerExact()
                else -> BigInteger(value)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseChainId(raw: String): Long? {
        val value = raw.trim().trim('"')
        if (value.isEmpty()) return null
        return try {
            when {
                value.startsWith("0x") || value.startsWith("0X") -> Numeric.toBigInt(value).toLong()
                else -> value.toLongOrNull() ?: BigDecimal(value).toLong()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeData(raw: String): String? {
        val cleaned = Validators.normalizeCalldata(raw)
        if (!cleaned.startsWith("0x") && !cleaned.startsWith("0X")) {
            val body = cleaned
            if (body.isEmpty() || body.any { !it.isHexDigit() }) return null
            return "0x" + body.lowercase()
        }
        val body = cleaned.substring(2)
        if (body.isEmpty() || body.any { !it.isHexDigit() }) return null
        return "0x" + body.lowercase()
    }

    private fun isBareCalldata(text: String): Boolean {
        val cleaned = Validators.normalizeCalldata(text)
        if (!cleaned.startsWith("0x") && !cleaned.startsWith("0X")) return false
        val body = cleaned.substring(2)
        return body.length >= 8 && body.length % 2 == 0 && body.all { it.isHexDigit() }
    }

    private fun Char.isHexDigit(): Boolean = isDigit() || lowercaseChar() in 'a'..'f'
}
