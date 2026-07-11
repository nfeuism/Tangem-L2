package com.example.tangeml2

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.tangem.TangemSdk
import com.tangem.common.CompletionResult
import com.tangem.common.card.Card
import com.tangem.common.card.CardWallet
import com.tangem.common.card.EllipticCurve
import com.tangem.common.core.Config
import com.tangem.common.extensions.toDecompressedPublicKey
import com.tangem.common.extensions.toHexString
import com.tangem.crypto.hdWallet.DerivationPath
import com.tangem.operations.attestation.AttestationTask
import com.tangem.sdk.extensions.init
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.coroutines.resumeWithException

/**
 * Tangem L2 Recovery — 单 Activity 全流程。
 * 支持：动态网络列表 + 自定义网络、二维码/测试地址快填、原生 ETH 与 ERC20 代币转账、NFC 硬件签名。
 */
class MainActivity : ComponentActivity() {

    private lateinit var tangemSdk: TangemSdk

    // 卡片状态
    private var card: Card? = null
    private var masterWallet: CardWallet? = null
    private var derivedPublicKey: ByteArray? = null
    private var derivedAddress: String? = null

    // 网络（可变列表 = 预置 + 用户自定义）
    private val networks = L2Network.PRESETS.toMutableList()
    private lateinit var networkAdapter: ArrayAdapter<String>
    private var selectedNetwork: L2Network = L2Network.PRESETS.first()

    // 代币状态（切换网络时清空，避免跨链 decimals 串用）
    private var tokenDecimals: Int? = null
    private var tokenSymbol: String? = null

    // UI
    private lateinit var spinnerNetwork: Spinner
    private lateinit var btnAddNetwork: Button
    private lateinit var btnScanCard: Button
    private lateinit var tvAddress: TextView
    private lateinit var tvCardInfo: TextView
    private lateinit var tvBalance: TextView
    private lateinit var rgAssetType: RadioGroup
    private lateinit var rbNative: RadioButton
    private lateinit var rbToken: RadioButton
    private lateinit var rbContract: RadioButton
    private lateinit var layoutToken: LinearLayout
    private lateinit var layoutContract: LinearLayout
    private lateinit var etTokenContract: EditText
    private lateinit var etCalldata: EditText
    private lateinit var btnLoadToken: Button
    private lateinit var tvTokenInfo: TextView
    private lateinit var etToAddress: EditText
    private lateinit var btnScanQr: Button
    private lateinit var btnTestAddress: Button
    private lateinit var etAmount: EditText
    private lateinit var btnMax: Button
    private lateinit var btnSend: Button
    private lateinit var tvStatus: TextView
    private lateinit var btnExportLog: Button
    private lateinit var btnViewLog: Button
    private lateinit var btnClearLog: Button

    // ❗二维码扫描：ActivityResult 注册必须在 STARTED 之前，做成类属性，绝不能放进点击回调
    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (!raw.isNullOrBlank()) {
            // 兼容 ethereum:0xABC...@chainId 这类 EIP-681 URI，提取 0x 地址
            val match = Regex("0x[0-9a-fA-F]{40}").find(raw)
            etToAddress.setText(match?.value ?: raw.trim())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AppLog.install(this)
        AppLog.app("onCreate", "App 启动")

        spinnerNetwork = findViewById(R.id.spinnerNetwork)
        btnAddNetwork = findViewById(R.id.btnAddNetwork)
        btnScanCard = findViewById(R.id.btnScanCard)
        tvAddress = findViewById(R.id.tvAddress)
        tvCardInfo = findViewById(R.id.tvCardInfo)
        tvBalance = findViewById(R.id.tvBalance)
        rgAssetType = findViewById(R.id.rgAssetType)
        rbNative = findViewById(R.id.rbNative)
        rbToken = findViewById(R.id.rbToken)
        rbContract = findViewById(R.id.rbContract)
        layoutToken = findViewById(R.id.layoutToken)
        layoutContract = findViewById(R.id.layoutContract)
        etTokenContract = findViewById(R.id.etTokenContract)
        etCalldata = findViewById(R.id.etCalldata)
        btnLoadToken = findViewById(R.id.btnLoadToken)
        tvTokenInfo = findViewById(R.id.tvTokenInfo)
        etToAddress = findViewById(R.id.etToAddress)
        btnScanQr = findViewById(R.id.btnScanQr)
        btnTestAddress = findViewById(R.id.btnTestAddress)
        etAmount = findViewById(R.id.etAmount)
        btnMax = findViewById(R.id.btnMax)
        btnSend = findViewById(R.id.btnSend)
        tvStatus = findViewById(R.id.tvStatus)
        btnExportLog = findViewById(R.id.btnExportLog)
        btnViewLog = findViewById(R.id.btnViewLog)
        btnClearLog = findViewById(R.id.btnClearLog)
        // 初始化 Tangem SDK：
        //  - attestationMode=Offline：只做【本地】密码学验卡，不联网。在线认证是"向 Tangem
        //    服务器验卡片真伪"的防伪检查，对签名/转账并非必需；且 SDK 默认 tangemApiBaseUrl=""
        //    (空串)，在线认证会因空 URL 失败——与你的网络是否可达无关。故保持 Offline，安全且不阻断。
        //  - defaultDerivationPaths：让扫卡在【同一个 NFC 会话内】就派生好 ETH 路径，
        //    避免扫卡后再发第二个会话触发 50003(Busy)。从"贴两次卡"变"贴一次卡"。
        tangemSdk = TangemSdk.init(
            this,
            Config(
                attestationMode = AttestationTask.Mode.Offline,
                defaultDerivationPaths = mutableMapOf(
                    EllipticCurve.Secp256k1 to listOf(DerivationPath(ETH_DERIVATION_PATH)),
                ),
            ),
        )

        // 网络下拉框（动态）
        networkAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            networks.map { it.label() }.toMutableList(),
        )
        spinnerNetwork.adapter = networkAdapter
        spinnerNetwork.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedNetwork = networks[pos]
                // 换链后清空代币信息
                tokenDecimals = null
                tokenSymbol = null
                tvTokenInfo.text = ""
                refreshBalance()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // 资产类型切换：原生 ETH / ERC20 代币 / 合约调用
        rgAssetType.setOnCheckedChangeListener { _, checkedId ->
            val token = checkedId == R.id.rbToken
            val contractCall = checkedId == R.id.rbContract
            layoutToken.visibility = if (token) View.VISIBLE else View.GONE
            layoutContract.visibility = if (contractCall) View.VISIBLE else View.GONE
            etAmount.hint = when {
                token -> "代币数量"
                contractCall -> "随调用发送的 ETH (通常 0)"
                else -> "金额 (ETH)"
            }
            etToAddress.hint = if (contractCall) "合约地址 0x... (调用目标)" else "接收地址 0x..."
        }

        btnAddNetwork.setOnClickListener { showAddNetworkDialog() }
        btnScanCard.setOnClickListener { scanCardAndDerive() }
        btnScanQr.setOnClickListener {
            qrLauncher.launch(
                ScanOptions()
                    .setOrientationLocked(false)
                    .setBeepEnabled(false)
                    .setPrompt("扫描收款地址二维码"),
            )
        }
        btnTestAddress.setOnClickListener {
            etToAddress.setText(derivedAddress ?: "")
            Toast.makeText(this, "已填入本卡地址（自转测试用）", Toast.LENGTH_SHORT).show()
        }
        btnLoadToken.setOnClickListener { loadTokenInfo() }
        btnMax.setOnClickListener { fillMaxAmount() }
        btnSend.setOnClickListener { sendTransaction() }

        // 日志按钮
        btnExportLog.setOnClickListener {
            try {
                val file = AppLog.writeToFile(this)
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Tangem L2 Recovery 日志")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "导出日志"))
                AppLog.app("export", "日志已导出: ${file.length()} bytes")
            } catch (e: Exception) {
                AppLog.err("export", "导出失败", e)
                tvStatus.text = "导出失败: ${e.message}"
            }
        }
        btnViewLog.setOnClickListener {
            val log = AppLog.tail(200)
            AlertDialog.Builder(this)
                .setTitle("最近 200 行日志")
                .setMessage(log.ifEmpty { "(暂无日志)" })
                .setPositiveButton("关闭", null)
                .show()
        }
        btnClearLog.setOnClickListener {
            AppLog.clear()
            tvStatus.text = "✅ 日志已清空"
        }
        btnSend.isEnabled = false

        // ❗合约地址一改动就清空缓存的 decimals/symbol，防止用旧代币精度算错金额而丢币
        etTokenContract.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                tokenDecimals = null
                tokenSymbol = null
                tvTokenInfo.text = ""
            }
        })
    }

    // ═══════════════════════════════════════════════════════════════════
    // 添加自定义网络：只收 名称 + RPC，chainId 从 RPC 拉取（防止填错导致 EIP-155 签错链）
    // ═══════════════════════════════════════════════════════════════════
    private fun showAddNetworkDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val etName = EditText(this).apply {
            hint = "网络名称，如 Base"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val etRpc = EditText(this).apply {
            hint = "RPC URL，如 https://mainnet.base.org"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val etExplorer = EditText(this).apply {
            hint = "区块浏览器 URL（可选）"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        container.addView(etName)
        container.addView(etRpc)
        container.addView(etExplorer)

        AlertDialog.Builder(this)
            .setTitle("添加自定义网络")
            .setView(container)
            .setPositiveButton("拉取 ChainID 并添加") { _, _ ->
                val name = etName.text.toString().trim()
                val rpc = etRpc.text.toString().trim()
                val explorer = etExplorer.text.toString().trim()
                if (name.isEmpty() || rpc.isEmpty()) {
                    Toast.makeText(this, "名称和 RPC 不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                tvStatus.text = "正在从 RPC 拉取 ChainID…"
                lifecycleScope.launch {
                    try {
                        val chainId = withContext(Dispatchers.IO) { TransactionHelper.fetchChainId(rpc) }
                        val net = L2Network(name, chainId, rpc, explorer.ifEmpty { rpc }, isCustom = true)
                        networks.add(net)
                        networkAdapter.add(net.label())
                        networkAdapter.notifyDataSetChanged()
                        spinnerNetwork.setSelection(networks.size - 1)
                        tvStatus.text = "✅ 已添加 $name (chainId=$chainId)"
                    } catch (e: Exception) {
                        AppLog.err("addNetwork", "添加自定义网络失败 rpc=$rpc", e)
                        tvStatus.text = "❌ 添加失败（RPC 不可达或非 EVM 链）: ${e.message}"
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ═══════════════════════════════════════════════════════════════════
    // 阶段一：NFC 扫码 → 读取主种子钱包 → 派生 ETH 地址 → 验证
    // ═══════════════════════════════════════════════════════════════════
    private fun scanCardAndDerive() {
        tvStatus.text = "请将 Tangem 卡片贴到 NFC 感应区…"
        btnScanCard.isEnabled = false

        tangemSdk.scanCard { scanResult ->
            when (scanResult) {
                is CompletionResult.Success -> {
                    card = scanResult.data
                    masterWallet = card!!.wallets.firstOrNull { it.curve == EllipticCurve.Secp256k1 }
                    if (masterWallet == null) {
                        runOnUiThread {
                            tvStatus.text = "错误：卡片未找到 secp256k1 钱包"
                            btnScanCard.isEnabled = true
                        }
                        return@scanCard
                    }
                    // ❗不再发起第二个 NFC 会话（那正是 50003 Busy 的根因）。
                    // Config.defaultDerivationPaths 已让扫卡在同一会话内派生好，直接读 derivedKeys。
                    val path = DerivationPath(ETH_DERIVATION_PATH)
                    val childKey = masterWallet!!.derivedKeys[path]
                    runOnUiThread {
                        if (childKey == null) {
                            // 兜底：卡片可能固件过旧或未开启 HD。给出明确指引，不再自动发第二会话。
                            AppLog.app("derive", "derivedKeys 为空 path=$path keys=${masterWallet!!.derivedKeys.keys} " +
                                "hdAllowed=${card!!.settings.isHDWalletAllowed} fw=${card!!.firmwareVersion.stringValue}")
                            tvStatus.text =
                                "派生结果为空：卡片可能固件过旧或未启用 HD 钱包。\n" +
                                "请重试扫卡；若持续为空，说明此卡不支持 BIP32 派生。"
                            btnScanCard.isEnabled = true
                        } else {
                            derivedPublicKey = childKey.publicKey.toDecompressedPublicKey()
                            val pubNoPrefix = derivedPublicKey!!.copyOfRange(1, 65)
                            val addressBytes = Hash.sha3(pubNoPrefix).copyOfRange(12, 32)
                            derivedAddress = Numeric.toHexString(addressBytes)
                            tvAddress.text = "地址: $derivedAddress"
                            AppLog.app("derive", "派生成功 addr=$derivedAddress")

                            // 卡片诊断信息（含 access code 提示）
                            val cid = card!!.cardId
                            val pinNote = if (card!!.isAccessCodeSet) "已设密码(签名时SDK会弹窗)" else "无密码"
                            tvCardInfo.text = "卡片: ${cid.take(8)}… · 固件 ${card!!.firmwareVersion.stringValue} · $pinNote"

                            if (EXPECTED_ADDRESS.isNotBlank() && !derivedAddress.equals(EXPECTED_ADDRESS, ignoreCase = true)) {
                                tvStatus.text =
                                    "⚠️ 地址不匹配！\n派生: $derivedAddress\n预期: $EXPECTED_ADDRESS\n仍可继续，但请谨慎核对。"
                            } else {
                                tvStatus.text = "✅ 已读取地址: $derivedAddress"
                            }
                            btnSend.isEnabled = true
                            btnScanCard.isEnabled = true
                            refreshBalance()
                        }
                    }
                }
                is CompletionResult.Failure -> runOnUiThread {
                    AppLog.app("scan", "扫码失败: ${scanResult.error.customMessage} (code=${scanResult.error.code})")
                    tvStatus.text = "扫码失败: ${scanResult.error.customMessage}"
                    btnScanCard.isEnabled = true
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 查询原生币余额
    // ═══════════════════════════════════════════════════════════════════
    private fun refreshBalance() {
        val addr = derivedAddress ?: return
        lifecycleScope.launch {
            try {
                val wei = withContext(Dispatchers.IO) {
                    TransactionHelper.ethGetBalance(selectedNetwork.rpcUrl, addr)
                }
                val eth = BigDecimal(wei).divide(BigDecimal.TEN.pow(18), 6, RoundingMode.HALF_UP)
                tvBalance.text = "余额: $eth（原生币）@ ${selectedNetwork.displayName}"
            } catch (e: Exception) {
                AppLog.err("balance", "余额查询失败 net=${selectedNetwork.displayName}", e)
                tvBalance.text = "余额查询失败: ${e.message}"
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 查询 ERC20 代币信息（符号 / 精度 / 余额）
    // ═══════════════════════════════════════════════════════════════════
    private fun loadTokenInfo() {
        val contract = etTokenContract.text.toString().trim()
        val owner = derivedAddress
        if (!TransactionHelper.isValidAddress(contract)) {
            Toast.makeText(this, "代币合约地址非法", Toast.LENGTH_SHORT).show(); return
        }
        if (owner == null) {
            Toast.makeText(this, "请先扫描卡片", Toast.LENGTH_SHORT).show(); return
        }
        tvTokenInfo.text = "查询中…"
        lifecycleScope.launch {
            try {
                val rpc = selectedNetwork.rpcUrl
                // 用 OrThrow 版：读不到精度就报错，绝不把毒值 18 缓存进 tokenDecimals
                val dec = withContext(Dispatchers.IO) { TransactionHelper.getTokenDecimalsOrThrow(rpc, contract) }
                val sym = withContext(Dispatchers.IO) { TransactionHelper.getTokenSymbol(rpc, contract) }
                val bal = withContext(Dispatchers.IO) { TransactionHelper.getTokenBalance(rpc, contract, owner) }
                tokenDecimals = dec
                tokenSymbol = sym
                val human = BigDecimal(bal).divide(BigDecimal.TEN.pow(dec), 6, RoundingMode.HALF_UP)
                tvTokenInfo.text = "代币: $sym · 精度 $dec · 余额 $human"
            } catch (e: Exception) {
                AppLog.err("token", "代币信息查询失败 contract=$contract", e)
                tvTokenInfo.text = "查询失败: ${e.message}"
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 「全部余额」：用 BigDecimal 精确填入完整余额，杜绝浮点上浮导致超额 revert
    // ═══════════════════════════════════════════════════════════════════
    private fun fillMaxAmount() {
        val owner = derivedAddress
        if (owner == null) { Toast.makeText(this, "请先扫描卡片", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch {
            try {
                val net = selectedNetwork
                if (rbToken.isChecked) {
                    val contract = etTokenContract.text.toString().trim()
                    if (!TransactionHelper.isValidAddress(contract)) {
                        Toast.makeText(this@MainActivity, "请先填代币合约地址", Toast.LENGTH_SHORT).show(); return@launch
                    }
                    val dec = withContext(Dispatchers.IO) { TransactionHelper.getTokenDecimalsOrThrow(net.rpcUrl, contract) }
                    val bal = withContext(Dispatchers.IO) { TransactionHelper.getTokenBalance(net.rpcUrl, contract, owner) }
                    // 除以 10^dec 一定整除(除数是 10 的幂)，无精度损失
                    val human = BigDecimal(bal).divide(BigDecimal.TEN.pow(dec)).stripTrailingZeros().toPlainString()
                    etAmount.setText(human)
                    Toast.makeText(this@MainActivity, "已填入全部代币余额", Toast.LENGTH_SHORT).show()
                } else {
                    val bal = withContext(Dispatchers.IO) { TransactionHelper.ethGetBalance(net.rpcUrl, owner) }
                    val gasPrice = withContext(Dispatchers.IO) { TransactionHelper.ethGasPrice(net.rpcUrl) }
                    // 预留 gas: gasPrice × 300000(覆盖 zkLink Nova 等高 gasLimit 链普通转账 ~201527)
                    val reserve = gasPrice.multiply(BigInteger.valueOf(300_000L))
                    val spendable = bal.subtract(reserve).max(BigInteger.ZERO)
                    val human = BigDecimal(spendable).divide(BigDecimal.TEN.pow(18)).stripTrailingZeros().toPlainString()
                    etAmount.setText(human)
                    Toast.makeText(this@MainActivity, "已填入余额-gas预留", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AppLog.err("max", "填充全部余额失败", e)
                Toast.makeText(this@MainActivity, "读取余额失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 发送：ETH 与 ERC20 两条分支
    // ═══════════════════════════════════════════════════════════════════
    private fun sendTransaction() {
        val toAddress = etToAddress.text.toString().trim()
        val amountStr = etAmount.text.toString().trim()
        val isToken = rbToken.isChecked
        val isContract = rbContract.isChecked
        val contract = etTokenContract.text.toString().trim()
        val calldata = etCalldata.text.toString().trim().replace("\\s".toRegex(), "")

        if (!TransactionHelper.isValidAddress(toAddress)) {
            tvStatus.text = if (isContract) "❌ 合约地址非法（应为 0x + 40 位十六进制）"
            else "❌ 收款地址非法（应为 0x + 40 位十六进制）"
            return
        }
        // 合约调用：金额可空(默认0)，但 calldata 必填且合法；其它模式金额必填
        if (isContract) {
            if (!TransactionHelper.isValidCalldata(calldata)) {
                tvStatus.text = "❌ Calldata 非法（应为 0x + 至少 8 位十六进制，偶数长度）"; return
            }
        } else if (amountStr.isEmpty()) {
            tvStatus.text = "请填写金额"; return
        }
        if (isToken && !TransactionHelper.isValidAddress(contract)) {
            tvStatus.text = "❌ 代币合约地址非法"; return
        }
        if (derivedPublicKey == null || masterWallet == null || card == null) {
            tvStatus.text = "请先扫描卡片"; return
        }

        btnSend.isEnabled = false
        tvStatus.text = "正在构建交易…"

        lifecycleScope.launch {
            try {
                val net = selectedNetwork
                val from = derivedAddress!!
                val gasPrice = withContext(Dispatchers.IO) { TransactionHelper.ethGasPrice(net.rpcUrl) }
                val nonce = withContext(Dispatchers.IO) {
                    TransactionHelper.ethGetTransactionCount(net.rpcUrl, from)
                }

                // 构建交易 + 计算待签名哈希
                val (rawTx, txHash) = withContext(Dispatchers.IO) {
                    if (isContract) {
                        // 任意合约调用：to=合约, value=随调用发送的 ETH(可空→0), data=calldata
                        val valueWei = if (amountStr.isEmpty()) BigInteger.ZERO
                        else BigDecimal(amountStr).multiply(BigDecimal.TEN.pow(18)).toBigInteger()
                        val gasLimit = TransactionHelper.estimateGas(net.rpcUrl, from, toAddress, calldata, valueWei)
                        val tx = TransactionHelper.buildContractCall(
                            toAddress, nonce, gasPrice, gasLimit, valueWei, calldata,
                        )
                        val (_, h) = TransactionHelper.encodeAndHash(tx, net.chainId)
                        tx to h
                    } else if (isToken) {
                        val dec = tokenDecimals ?: TransactionHelper.getTokenDecimalsOrThrow(net.rpcUrl, contract)
                        val amountRaw = BigDecimal(amountStr).multiply(BigDecimal.TEN.pow(dec)).toBigInteger()
                        // ❗发送前校验余额，避免"金额超过余额"链上 revert、白烧 gas(真机已踩此坑)
                        val tokBal = TransactionHelper.getTokenBalance(net.rpcUrl, contract, from)
                        if (amountRaw > tokBal) error("金额超过代币余额\n拟发 $amountRaw\n余额 $tokBal\n相差 ${amountRaw - tokBal} wei —— 请点「全部余额」精确填入")
                        val data = TransactionHelper.encodeErc20TransferData(toAddress, amountRaw)
                        val gasLimit = TransactionHelper.estimateGas(net.rpcUrl, from, contract, data, BigInteger.ZERO)
                        val tx = TransactionHelper.buildErc20Transfer(contract, nonce, gasPrice, gasLimit, data)
                        val (_, h) = TransactionHelper.encodeAndHash(tx, net.chainId)
                        tx to h
                    } else {
                        val amountWei = BigDecimal(amountStr).multiply(BigDecimal.TEN.pow(18)).toBigInteger()
                        // ❗原生转账也走 estimateGas，以 21000 为下限。
                        // zkLink Nova 等 zkSync 栈链普通转账常 > 21000，硬编码会 out-of-gas 白烧 gas。
                        val gasLimit = TransactionHelper.estimateGas(net.rpcUrl, from, toAddress, null, amountWei)
                        // ❗校验 金额+gas ≤ 余额
                        val ethBal = TransactionHelper.ethGetBalance(net.rpcUrl, from)
                        val cost = amountWei.add(gasPrice.multiply(gasLimit))
                        if (cost > ethBal) error("金额+gas 超过余额\n需要 $cost wei\n余额 $ethBal wei —— 请减小金额或点「全部余额」")
                        val tx = TransactionHelper.buildLegacyTransaction(
                            net, nonce, toAddress, amountWei, gasPrice, gasLimit,
                        )
                        val (_, h) = TransactionHelper.encodeAndHash(tx, net.chainId)
                        tx to h
                    }
                }

                tvStatus.text = "请将卡片贴到 NFC 感应区以签名…"
                val signature = signWithCard(txHash)

                val sigData = withContext(Dispatchers.Default) {
                    TransactionHelper.recoverAndBuildSignature(signature, txHash, derivedPublicKey!!, net.chainId)
                }
                val signedHex = TransactionHelper.encodeSignedTransaction(rawTx, sigData)

                tvStatus.text = "正在广播交易…"
                AppLog.app("send", "广播交易 net=${net.displayName} isToken=$isToken to=$toAddress signedLen=${signedHex.length}")
                AppLog.app("send", "signedRawTx=$signedHex")  // 完整原始交易(不含私钥)，供链上解码核查
                val txid = withContext(Dispatchers.IO) { TransactionHelper.broadcast(net.rpcUrl, signedHex) }
                AppLog.app("send", "广播返回 txid=$txid，开始回链核实…")

                // ❗关键修复(Bug A)：查 receipt 的执行 status，不是"交易在不在链上"。
                //    reverted 交易也在链上，之前只查 getTransactionByHash 把失败误报成功(真机已踩坑)。
                //    Mode 出块 ~2s，最多等 ~25 秒。
                tvStatus.text = "已广播，正在回链核实执行结果…"
                val status = withContext(Dispatchers.IO) {
                    var st: Int? = null
                    for (i in 0 until 10) {
                        st = TransactionHelper.getReceiptStatus(net.rpcUrl, txid)
                        if (st != null) break
                        Thread.sleep(2500)
                    }
                    st
                }
                when (status) {
                    1 -> {
                        AppLog.app("send", "✅ 链上执行成功 txid=$txid")
                        tvStatus.text = "🎉 交易成功并被链确认！\nTX: $txid\n${net.explorer}/tx/$txid"
                    }
                    0 -> {
                        AppLog.err("send", "❌ 交易已上链但执行失败(revert) txid=$txid —— 币未转移, gas 已消耗")
                        tvStatus.text = "❌ 交易被链上拒绝(revert)！\n币没有转出, 但 gas 已消耗。\n常见原因: 金额超过余额 / gas 不足 / 合约条件不满足。\nTX: $txid\n${net.explorer}/tx/$txid\n请导出日志发我排查。"
                    }
                    else -> {
                        AppLog.err("send", "⚠️ 广播后 25 秒内查不到回执 txid=$txid")
                        tvStatus.text = "⚠️ 已广播但暂未上链确认。\nTX: $txid\n用浏览器核对: ${net.explorer}/tx/$txid\n若一直查不到请导出日志发我"
                    }
                }
                refreshBalance()
            } catch (e: Exception) {
                AppLog.err("send", "交易失败 net=${selectedNetwork.displayName} isToken=$isToken amount=$amountStr to=$toAddress", e)
                tvStatus.text = "交易失败: ${e.message}"
            } finally {
                btnSend.isEnabled = true
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // 核心：Tangem NFC 签名（walletPublicKey 传主种子公钥，卡内按路径派生子私钥签名）
    // ═══════════════════════════════════════════════════════════════════
    private suspend fun signWithCard(txHash: ByteArray): ByteArray =
        suspendCancellableCoroutine { cont ->
            tangemSdk.sign(
                hash = txHash,
                walletPublicKey = masterWallet!!.publicKey,
                cardId = card!!.cardId,
                derivationPath = DerivationPath(ETH_DERIVATION_PATH),
                initialMessage = null,
            ) { result ->
                when (result) {
                    is CompletionResult.Success -> {
                        val sig = result.data.signature
                        AppLog.app("sign", "NFC 签名返回 ${sig.size} bytes")
                        if (sig.size != 64) {
                            cont.resumeWithException(
                                RuntimeException("签名长度异常: ${sig.size} bytes（期望 64）。内容: ${sig.toHexString()}"),
                            )
                        } else {
                            cont.resumeWith(Result.success(sig))
                        }
                    }
                    is CompletionResult.Failure -> {
                        AppLog.app("sign", "NFC 签名失败: ${result.error.customMessage} (code=${result.error.code})")
                        cont.resumeWithException(RuntimeException("NFC 签名失败: ${result.error.customMessage}"))
                    }
                }
            }
        }
}
