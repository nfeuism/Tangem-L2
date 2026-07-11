package com.example.tangeml2

import org.web3j.crypto.ECDSASignature
import org.web3j.crypto.Hash
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.Sign
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * 核心工具：Ethereum → Tangem NFC 签名 → 广播交易。
 *
 * 关键技术点：
 *   1. Tangem 返回的签名是 64 字节的 (r || s)，**不包含 recovery id**
 *   2. recovery id 需要通过 ecrecover（公钥恢复）反推
 *   3. EIP-155 v 值: v = recId + 35 + chainId * 2
 *   4. 必须 canonicalize (low-S)，否则有些 RPC 节点会拒收
 *   5. ❗大 chainId（如 Scroll 534352）的 v 值远超 1 字节，必须用 BigInteger 编码
 */
object TransactionHelper {

    // ── 1. 查询 Nonce ────────────────────────────────────────────
    fun ethGetTransactionCount(rpcUrl: String, address: String): BigInteger {
        val raw = rpcCall(rpcUrl, "eth_getTransactionCount", listOf(address, "latest"))
        return Numeric.toBigInt(raw)
    }

    // ── 2. 构建未签名交易（Legacy） ──────────────────────────────
    fun buildLegacyTransaction(
        network: L2Network,
        nonce: BigInteger,
        toAddress: String,
        amountWei: BigInteger,
        gasPrice: BigInteger,
        gasLimit: BigInteger,
    ): RawTransaction = RawTransaction.createEtherTransaction(
        nonce,
        gasPrice,
        gasLimit,
        toAddress,
        amountWei,
    )

    // ── 2b. 构建任意合约调用交易（to=合约, value=随调用发送的 ETH, data=calldata） ──
    /**
     * ❗web3j 4.9.8 签名: createTransaction(nonce, gasPrice, gasLimit, to, value, data)
     *    —— value 在 data 之前。顺序写反会把 calldata 当成 ETH 金额发出去、丢钱。
     * 覆盖 Eigenpie/Renzo 赎回、跨链桥、approve、claim 等一切合约方法调用。
     */
    fun buildContractCall(
        toContract: String,
        nonce: BigInteger,
        gasPrice: BigInteger,
        gasLimit: BigInteger,
        valueWei: BigInteger,
        data: String,
    ): RawTransaction = RawTransaction.createTransaction(
        nonce,
        gasPrice,
        gasLimit,
        toContract,
        valueWei,
        data,
    )

    /** 校验 calldata：0x 开头、纯十六进制、偶数长度、至少含 4 字节函数选择器 (0x + 8 hex)。 */
    fun isValidCalldata(data: String): Boolean =
        Regex("^0x[0-9a-fA-F]{8,}$").matches(data) && (data.length % 2 == 0)

    // ── 3. 编码交易 + 计算待签名哈希 ─────────────────────────────
    /**
     * 返回 (encodedUnsignedTx, hashToSign)。
     * EIP-155：encode(tx, chainId) 生成带 chainId 的 RLP 预映像，其 keccak256 即待签名哈希。
     */
    fun encodeAndHash(
        tx: RawTransaction,
        chainId: Long,
    ): Pair<ByteArray, ByteArray> {
        val encoded = TransactionEncoder.encode(tx, chainId)
        val hash = Hash.sha3(encoded)
        return Pair(encoded, hash)
    }

    // ── 4. 从 Tangem 签名 + 公钥恢复 recovery id + 组装签名 ─────
    /**
     * Tangem 返回 raw r||s (64 bytes) → 恢复 recId → 构造 Sign.SignatureData。
     *
     * @param rawSignature   Tangem 卡片返回的 64-byte 签名
     * @param txHash         传入 Tangem sign() 的哈希（keccak256 of EIP-155 preimage）
     * @param derivedPubKey  已推导并解压后的公钥 (65 bytes, 0x04 || x || y)
     * @param chainId        目标链的 chain ID
     */
    fun recoverAndBuildSignature(
        rawSignature: ByteArray,
        txHash: ByteArray,
        derivedPubKey: ByteArray,
        chainId: Long,
    ): Sign.SignatureData {
        require(rawSignature.size == 64) { "Tangem 签名长度必须为 64 字节，实为 ${rawSignature.size}" }
        require(derivedPubKey.size == 65) { "公钥必须为 65 字节 (0x04 || x || y)" }

        val r = BigInteger(1, rawSignature.copyOfRange(0, 32))
        val s = BigInteger(1, rawSignature.copyOfRange(32, 64))

        // EIP-2: 确保 s 在低半曲线范围 (canonical / low-S)
        val ecdsaSignature = ECDSASignature(r, s).toCanonicalised()

        // 用 ecrecover 反推 recovery id (0 或 1)
        val recId = findRecoveryId(ecdsaSignature, txHash, derivedPubKey)
            ?: error("无法恢复公钥 — 签名或哈希与预期地址不匹配，请检查 derivation path")

        // EIP-155: v = recId + 35 + chainId * 2
        // ❗必须用 BigInteger：Scroll chainId=534352 时 v≈1068739，远超 1 字节
        // ❗BigInteger.TWO 是 API 31 才有的字段，minSdk 24 会崩 NoSuchFieldError → 用 valueOf(2L)
        val v = BigInteger.valueOf(recId.toLong())
            .add(BigInteger.valueOf(CHAIN_ID_INC))
            .add(BigInteger.valueOf(chainId).multiply(BigInteger.valueOf(2L)))

        // r, s 必须补齐/截断为 32 字节（BigInteger.toByteArray 可能带前导 0 或不足 32 位）
        val rBytes = Numeric.toBytesPadded(ecdsaSignature.r, 32)
        val sBytes = Numeric.toBytesPadded(ecdsaSignature.s, 32)

        return Sign.SignatureData(v.toByteArray(), rBytes, sBytes)
    }

    /**
     * 用 ecrecover 尝试 recId 0..3，匹配给定公钥就返回该 recId，否则 null。
     * Sign.recoverFromSignature 直接返回恢复出的公钥（BigInteger 形式，64 字节 X||Y，无 0x04 前缀）。
     */
    private fun findRecoveryId(
        ecdsaSignature: ECDSASignature,
        messageHash: ByteArray,
        expectedPubKey: ByteArray, // 65 bytes: 0x04 || X || Y
    ): Int? {
        val expected = BigInteger(1, expectedPubKey.copyOfRange(1, 65))
        for (recId in 0..3) {
            val recovered: BigInteger? = try {
                Sign.recoverFromSignature(recId, ecdsaSignature, messageHash)
            } catch (_: Exception) {
                null
            }
            if (recovered != null && recovered == expected) {
                return recId
            }
        }
        return null
    }

    // ── 5. 拼接签名 → 已签名的编码交易 hex ───────────────────────
    /**
     * 将签名编码回交易 RLP，返回 hex 字符串，可直接用于 eth_sendRawTransaction。
     * v 已经带上 EIP-155 chainId 偏移，因此这里用 2 参数 encode（不再传 chainId）。
     */
    fun encodeSignedTransaction(
        tx: RawTransaction,
        signatureData: Sign.SignatureData,
    ): String {
        val encoded = TransactionEncoder.encode(tx, signatureData)
        return Numeric.toHexString(encoded)
    }

    // ── 6. 广播交易 ──────────────────────────────────────────────
    fun broadcast(rpcUrl: String, signedHex: String): String {
        return rpcCall(rpcUrl, "eth_sendRawTransaction", listOf(signedHex))
    }

    // ── 7. 查询 ETH 余额 ─────────────────────────────────────────
    fun ethGetBalance(rpcUrl: String, address: String): BigInteger {
        val raw = rpcCall(rpcUrl, "eth_getBalance", listOf(address, "latest"))
        return Numeric.toBigInt(raw)
    }

    // ── 8. 查询 Gas Price ────────────────────────────────────────
    /**
     * 查询链上建议 gasPrice。
     * ❗不要用 1 gwei 的高地板价：zkLink Nova 真实仅 ~0.125 gwei，强行抬到 1 gwei 会多付 8 倍 gas，
     *    在 gas 极少的钱包上直接造成 insufficient funds（真机已踩坑）。
     *    改为"网络实价 ×1.15 缓冲"，仅在 RPC 返回 0 时用极低兜底。
     */
    fun ethGasPrice(rpcUrl: String): BigInteger {
        val zeroFloor = BigInteger.valueOf(10_000_000L)   // 0.01 gwei，仅当网络返回 0
        val errFloor = BigInteger.valueOf(100_000_000L)   // 0.1 gwei，仅当查询异常
        return try {
            val raw = rpcCall(rpcUrl, "eth_gasPrice", emptyList())
            val net = Numeric.toBigInt(raw)
            val buffered = net.multiply(BigInteger.valueOf(115L)).divide(BigInteger.valueOf(100L))
            buffered.max(zeroFloor)
        } catch (_: Exception) {
            errFloor
        }
    }

    /** 轻量 JSON-RPC 调用（不依赖完整 Web3j 实例），返回解析后的 JSON 响应对象（error 字段则抛异常）。 */
    private fun rpcRequest(url: String, method: String, params: List<Any>): org.json.JSONObject {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        conn.doOutput = true

        val payload = org.json.JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", org.json.JSONArray(params))
            put("id", 1)
        }.toString()

        conn.outputStream.use { it.write(payload.toByteArray()) }

        // ❗HTTP >= 400 时必须读 errorStream，否则 inputStream 抛异常会吞掉真正的 RPC 报错内容
        val code = conn.responseCode
        val stream = if (code >= 400) (conn.errorStream ?: conn.inputStream) else conn.inputStream
        val response = stream.bufferedReader().readText()
        conn.disconnect()

        if (code >= 400 && response.isBlank()) {
            throw RuntimeException("RPC HTTP $code (无响应体) @ $url")
        }

        val json = org.json.JSONObject(response)
        if (json.has("error")) throw RuntimeException("RPC error: ${json.getJSONObject("error")}")
        return json
    }

    /** 返回 result 字段字符串（用于有明确结果的调用，如广播返回 txid、余额等）。 */
    private fun rpcCall(url: String, method: String, params: List<Any>): String =
        rpcRequest(url, method, params).getString("result")

    /**
     * ❗关键：广播返回 txid ≠ 交易被链接受。用 eth_getTransactionByHash 核实节点是否真收到。
     * @return true = 节点已知该交易（进了 mempool 或已上块）；false = 链上查无（被丢弃/gas不足/签名问题）
     */
    fun isTransactionKnown(rpcUrl: String, txHash: String): Boolean {
        return try {
            !rpcRequest(rpcUrl, "eth_getTransactionByHash", listOf(txHash)).isNull("result")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * ❗真正的成功判定：查 receipt 的执行状态，而不是"交易在不在链上"。
     * reverted 的交易也在链上(有 receipt)，但 status=0x0 —— 之前只查 getTransactionByHash 会把
     * 失败交易误报成功。真机已踩坑(transfer amount exceeds balance，status=0x0)。
     * @return null=尚未上链/待确认；1=执行成功(0x1)；0=已上链但执行失败/revert(0x0)
     */
    fun getReceiptStatus(rpcUrl: String, txHash: String): Int? {
        return try {
            val json = rpcRequest(rpcUrl, "eth_getTransactionReceipt", listOf(txHash))
            if (json.isNull("result")) return null
            val receipt = json.getJSONObject("result")
            val status = receipt.optString("status", "")
            when {
                status.isEmpty() -> null
                Numeric.toBigInt(status).signum() == 0 -> 0
                else -> 1
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 校验收款地址是否为合法的 0x + 40 位十六进制 EVM 地址 */
    fun isValidAddress(address: String): Boolean =
        Regex("^0x[0-9a-fA-F]{40}$").matches(address)

    /** 从 RPC 拉取真实 chainId（添加自定义网络时校验，防止用户填错导致 EIP-155 签错链） */
    fun fetchChainId(rpcUrl: String): Long {
        val raw = rpcCall(rpcUrl, "eth_chainId", emptyList())
        return Numeric.toBigInt(raw).toLong()
    }

    // ══════════════════════════════════════════════════════════════════
    // ERC20 代币支持（Mode / zkLink / Scroll 上的非 ETH 代币）
    // ══════════════════════════════════════════════════════════════════

    // 4 字节函数选择器（keccak256(签名)[0..4]）
    private val SEL_TRANSFER = Hash.sha3("transfer(address,uint256)".toByteArray()).copyOfRange(0, 4)
    private val SEL_BALANCEOF = Hash.sha3("balanceOf(address)".toByteArray()).copyOfRange(0, 4)
    private val SEL_DECIMALS = Hash.sha3("decimals()".toByteArray()).copyOfRange(0, 4)
    private val SEL_SYMBOL = Hash.sha3("symbol()".toByteArray()).copyOfRange(0, 4)

    /** ABI 编码 transfer(address,uint256) 的 calldata = 选择器 + 左补32字节地址 + 左补32字节金额 */
    fun encodeErc20TransferData(toAddress: String, amountRaw: BigInteger): String {
        val toPadded = Numeric.toBytesPadded(Numeric.toBigInt(toAddress), 32)
        val amountPadded = Numeric.toBytesPadded(amountRaw, 32)
        return Numeric.toHexString(SEL_TRANSFER + toPadded + amountPadded)
    }

    /**
     * 构建 ERC20 转账交易：to = 代币合约地址，value = 0，转账语义都在 data 里。
     * gasLimit 必须由 estimateGas 得出，绝不能用 21000（那会 out-of-gas 白扣 gas）。
     */
    fun buildErc20Transfer(
        contractAddress: String,
        nonce: BigInteger,
        gasPrice: BigInteger,
        gasLimit: BigInteger,
        data: String,
    ): RawTransaction = RawTransaction.createTransaction(
        nonce,
        gasPrice,
        gasLimit,
        contractAddress,
        BigInteger.ZERO,
        data,
    )

    /** 通用 eth_call（只读合约调用） */
    private fun ethCall(rpcUrl: String, to: String, data: String): String {
        val callObj = org.json.JSONObject().apply {
            put("to", to)
            put("data", data)
        }
        return rpcCall(rpcUrl, "eth_call", listOf(callObj, "latest"))
    }

    /** 查询代币精度 decimals()（USDC/USDT 是 6，不是 18）。展示用，失败兜底 18。 */
    fun getTokenDecimals(rpcUrl: String, contractAddress: String): Int {
        val raw = ethCall(rpcUrl, contractAddress, Numeric.toHexString(SEL_DECIMALS))
        if (raw.isBlank() || raw == "0x") return 18 // 展示兜底；发送路径请用 getTokenDecimalsOrThrow
        return Numeric.toBigInt(raw).toInt()
    }

    /**
     * 发送路径专用：拿不到 decimals 必须抛异常中止，绝不能兜底 18。
     * ❗真金钱安全：若 6 位精度代币的 decimals() 瞬时返回空而兜底成 18，
     * 会把「1 USDC」放大成 10^12 USDC 发出去 → 灾难性多发。
     */
    fun getTokenDecimalsOrThrow(rpcUrl: String, contractAddress: String): Int {
        val raw = ethCall(rpcUrl, contractAddress, Numeric.toHexString(SEL_DECIMALS))
        if (raw.isBlank() || raw == "0x") {
            throw RuntimeException("无法读取代币精度 decimals()，已中止发送以防金额算错。请先点「查询代币信息」确认。")
        }
        return Numeric.toBigInt(raw).toInt()
    }

    /** 查询代币余额 balanceOf(owner)（返回原始最小单位值） */
    fun getTokenBalance(rpcUrl: String, contractAddress: String, owner: String): BigInteger {
        val ownerPadded = Numeric.toBytesPadded(Numeric.toBigInt(owner), 32)
        val data = Numeric.toHexString(SEL_BALANCEOF + ownerPadded)
        val raw = ethCall(rpcUrl, contractAddress, data)
        if (raw.isBlank() || raw == "0x") return BigInteger.ZERO
        return Numeric.toBigInt(raw)
    }

    /** 查询代币符号 symbol()（动态 string，手工解析 ABI：offset + length + utf8 bytes） */
    fun getTokenSymbol(rpcUrl: String, contractAddress: String): String {
        return try {
            val raw = ethCall(rpcUrl, contractAddress, Numeric.toHexString(SEL_SYMBOL))
            val bytes = Numeric.hexStringToByteArray(raw)
            if (bytes.size < 64) return "?"
            // [0..32]=offset(通常0x20)，[32..64]=length，其后为 utf8 字节
            val len = BigInteger(1, bytes.copyOfRange(32, 64)).toInt()
            if (len <= 0 || 64 + len > bytes.size) return "?"
            String(bytes.copyOfRange(64, 64 + len), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            "?"
        }
    }

    /**
     * eth_estimateGas。L2 上偶尔会失败或返回过低，加 1.3x buffer + 兜底 100000。
     */
    fun estimateGas(
        rpcUrl: String,
        from: String,
        to: String,
        data: String?,
        value: BigInteger,
    ): BigInteger {
        val fallback = BigInteger.valueOf(100_000L)
        return try {
            val callObj = org.json.JSONObject().apply {
                put("from", from)
                put("to", to)
                put("value", "0x" + value.toString(16))
                if (data != null) put("data", data)
            }
            val raw = rpcCall(rpcUrl, "eth_estimateGas", listOf(callObj, "latest"))
            val est = Numeric.toBigInt(raw)
            // +30% buffer，并不低于兜底值
            est.multiply(BigInteger.valueOf(13L)).divide(BigInteger.valueOf(10L)).max(BigInteger.valueOf(21_000L))
        } catch (_: Exception) {
            fallback
        }
    }

    private const val CHAIN_ID_INC = 35L
}
