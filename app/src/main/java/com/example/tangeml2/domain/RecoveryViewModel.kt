package com.example.tangeml2.domain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tangeml2.core.log.AppLog
import com.example.tangeml2.core.util.AmountFormatException
import com.example.tangeml2.core.util.Units
import com.example.tangeml2.core.util.Validators
import com.example.tangeml2.data.abi.CalldataDecoder
import com.example.tangeml2.data.abi.Erc20
import com.example.tangeml2.data.card.CardResult
import com.example.tangeml2.data.card.TangemCardGateway
import com.example.tangeml2.data.network.NetworkStore
import com.example.tangeml2.data.qr.QrPayloadParser
import com.example.tangeml2.data.rpc.EvmRpc
import com.example.tangeml2.data.token.TokenService
import com.example.tangeml2.data.tx.TxCodec
import com.example.tangeml2.domain.model.AssetType
import com.example.tangeml2.domain.model.CardSnapshot
import com.example.tangeml2.domain.model.EXPECTED_ADDRESS
import com.example.tangeml2.domain.model.Network
import com.example.tangeml2.domain.model.QrContractCall
import com.example.tangeml2.domain.model.QrPayload
import com.example.tangeml2.domain.model.StatusMessage
import com.example.tangeml2.domain.model.TokenInfo
import com.example.tangeml2.domain.model.TxPhase
import com.example.tangeml2.domain.model.TxQuote
import com.example.tangeml2.domain.model.UnsignedTx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.web3j.utils.Numeric
import java.io.File
import java.math.BigInteger

/**
 * Single source of truth for the recovery screen.
 *
 * All money-moving logic lives here: the Compose layer only renders
 * [RecoveryUiState] and emits [RecoveryActions].
 */
class RecoveryViewModel(
    application: Application,
    private val cardGateway: TangemCardGateway,
) : AndroidViewModel(application), RecoveryActions {

    private val networkStore = NetworkStore(application)
    private val rpc = EvmRpc()
    private val tokenService = TokenService(rpc)

    private val _state = MutableStateFlow(RecoveryUiState())
    val state: StateFlow<RecoveryUiState> = _state.asStateFlow()

    private var balanceJob: Job? = null
    private var tokenJob: Job? = null
    private var quoteJob: Job? = null
    private var sendJob: Job? = null

    private var pendingTx: UnsignedTx? = null
    private var pendingAssetLabel: String = ""

    init {
        cardGateway.initialize()
        val networks = networkStore.load()
        val restoredId = networkStore.selectedNetworkId()
        val selected = networks.firstOrNull { it.id == restoredId } ?: networks.first()
        _state.value = RecoveryUiState(
            networks = networks,
            selectedNetwork = selected,
            status = StatusMessage.info("请先扫描 Tangem 卡片"),
        )
        AppLog.app("vm", "已加载 ${networks.size} 个网络，当前 ${selected.displayName}")
    }

    // ── state plumbing ───────────────────────────────────────────────────────

    private fun set(transform: (RecoveryUiState) -> RecoveryUiState) {
        _state.update { current ->
            val next = transform(current)
            next.copy(canSign = next.hasCard && !next.busy && next.phase == TxPhase.IDLE)
        }
    }

    private fun status(message: StatusMessage) = set { it.copy(status = message) }

    private fun fail(text: String, error: Throwable? = null) {
        AppLog.err("vm", text, error)
        set { it.copy(status = StatusMessage.error(text), busy = false, phase = TxPhase.IDLE) }
    }

    /** Loopback / emulator-host RPCs are the only ones allowed over plaintext. */
    private fun isLoopbackRpc(url: String): Boolean {
        val host = runCatching {
            java.net.URI(url.trim()).host?.lowercase()
        }.getOrNull() ?: return false
        return host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" || host == "::1"
    }

    private fun friendly(error: Throwable): String {
        val raw = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
        return raw.take(300)
    }

    // ── network ──────────────────────────────────────────────────────────────

    override fun selectNetwork(network: Network) {
        if (_state.value.selectedNetwork.id == network.id) return
        networkStore.setSelectedNetworkId(network.id)
        tokenJob?.cancel()
        quoteJob?.cancel()
        set {
            it.copy(
                selectedNetwork = network,
                token = Async.Idle,
                tokenAddressInput = "",
                quote = null,
                nativeBalance = Async.Idle,
            )
        }
        AppLog.app("vm", "切换网络 -> ${network.displayName} (${network.chainId})")
        refreshBalance()
        // The gas price and chain differ per network, so the preview must be redone
        // even when the form itself did not change.
        scheduleQuote()
    }

    override fun openAddNetwork() = set { it.copy(addNetworkOpen = true) }

    override fun dismissAddNetwork() = set { it.copy(addNetworkOpen = false) }

    override fun addCustomNetwork(name: String, rpcUrl: String, explorerUrl: String) {
        val cleanName = name.trim()
        val cleanRpc = rpcUrl.trim()
        val cleanExplorer = explorerUrl.trim()
        if (cleanName.isEmpty() || cleanRpc.isEmpty()) {
            status(StatusMessage.error("网络名称和 RPC 地址不能为空"))
            return
        }
        if (!cleanRpc.startsWith("http://") && !cleanRpc.startsWith("https://")) {
            status(StatusMessage.error("RPC 地址必须以 http:// 或 https:// 开头"))
            return
        }
        // Android blocks cleartext traffic for remote hosts; fail early with an
        // explanation instead of surfacing an opaque socket error later.
        if (cleanRpc.startsWith("http://") && !isLoopbackRpc(cleanRpc)) {
            status(
                StatusMessage.error(
                    "出于安全考虑，远程 RPC 必须使用 https://（明文 HTTP 可能被中间人篡改余额与 chainId）。" +
                        "仅本机地址（localhost / 127.0.0.1 / 10.0.2.2）允许 http://。",
                ),
            )
            return
        }
        viewModelScope.launch {
            set { it.copy(busy = true, status = StatusMessage.info("正在从 RPC 拉取 chainId…")) }
            try {
                val chainId = withContext(Dispatchers.IO) { rpc.chainId(cleanRpc) }
                if (chainId <= 0) throw IllegalStateException("RPC 返回的 chainId 无效")
                val network = Network(
                    id = "custom:$chainId",
                    displayName = cleanName,
                    chainId = chainId,
                    rpcUrl = cleanRpc,
                    explorerUrl = cleanExplorer.ifBlank { cleanRpc },
                    isCustom = true,
                )
                networkStore.addCustom(network)
                val refreshed = networkStore.load()
                networkStore.setSelectedNetworkId(network.id)
                set {
                    it.copy(
                        networks = refreshed,
                        selectedNetwork = refreshed.firstOrNull { n -> n.id == network.id } ?: network,
                        addNetworkOpen = false,
                        busy = false,
                        token = Async.Idle,
                        tokenAddressInput = "",
                        status = StatusMessage.ok("已添加 $cleanName（chainId=$chainId）"),
                    )
                }
                AppLog.app("vm", "添加自定义网络 name=$cleanName chainId=$chainId rpc=$cleanRpc")
                refreshBalance()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                set {
                    it.copy(
                        busy = false,
                        status = StatusMessage.error("添加失败（RPC 不可达或不是 EVM 链）：${friendly(e)}"),
                    )
                }
                AppLog.err("vm", "添加自定义网络失败 rpc=$cleanRpc", e)
            }
        }
    }

    override fun removeCustomNetwork(network: Network) {
        if (!network.isCustom) {
            status(StatusMessage.warn("内置网络不可删除"))
            return
        }
        networkStore.removeCustom(network.id)
        val refreshed = networkStore.load()
        val next = refreshed.firstOrNull() ?: Network.PRESETS.first()
        networkStore.setSelectedNetworkId(next.id)
        set {
            it.copy(
                networks = refreshed,
                selectedNetwork = next,
                token = Async.Idle,
                tokenAddressInput = "",
                quote = null,
                status = StatusMessage.ok("已删除 ${network.displayName}"),
            )
        }
        refreshBalance()
    }

    // ── card ─────────────────────────────────────────────────────────────────

    override fun scanCard() {
        if (_state.value.card is CardState.Scanning) return
        set {
            it.copy(
                card = CardState.Scanning,
                status = StatusMessage.info("请将 Tangem 卡片贴到 NFC 感应区…"),
                busy = true,
            )
        }
        viewModelScope.launch {
            when (val result = cardGateway.scanAndDerive()) {
                is CardResult.Ok -> onCardReady(result.value)
                is CardResult.Err -> {
                    AppLog.err("vm", "扫卡失败: ${result.message}")
                    set {
                        it.copy(
                            card = CardState.Failed(result.message),
                            busy = false,
                            status = StatusMessage.error(result.message),
                        )
                    }
                }
            }
        }
    }

    private fun onCardReady(snapshot: CardSnapshot) {
        val mismatch = EXPECTED_ADDRESS.isNotBlank() &&
            !snapshot.address.equals(EXPECTED_ADDRESS, ignoreCase = true)
        set {
            it.copy(
                card = CardState.Ready(snapshot),
                busy = false,
                status = if (mismatch) {
                    StatusMessage.warn(
                        "地址与预设不一致：派生 ${Validators.shortAddress(snapshot.address)}，" +
                            "预期 ${Validators.shortAddress(EXPECTED_ADDRESS)}。请谨慎核对。",
                    )
                } else {
                    StatusMessage.ok("已读取地址 ${Validators.shortAddress(snapshot.address)}")
                },
            )
        }
        refreshBalance()
        scheduleQuote()
    }

    override fun refreshBalance() {
        val snapshot = (_state.value.card as? CardState.Ready)?.snapshot ?: return
        val network = _state.value.selectedNetwork
        balanceJob?.cancel()
        balanceJob = viewModelScope.launch {
            set { it.copy(nativeBalance = Async.Loading) }
            try {
                val wei = withContext(Dispatchers.IO) { rpc.balance(network.rpcUrl, snapshot.address) }
                // Guard against a stale response after a network switch.
                if (_state.value.selectedNetwork.id != network.id) return@launch
                set {
                    it.copy(
                        nativeBalance = Async.Data(
                            Units.formatDisplay(wei, network.nativeDecimals, maxFraction = 6),
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.err("vm", "余额查询失败 net=${network.displayName}", e)
                set { it.copy(nativeBalance = Async.Failed(friendly(e))) }
            }
        }
    }

    // ── asset form ───────────────────────────────────────────────────────────

    override fun setAssetType(type: AssetType) {
        set { it.copy(assetType = type, quote = null) }
        scheduleQuote()
    }

    override fun setTokenAddress(value: String) {
        set { it.copy(tokenAddressInput = value, token = Async.Idle, quote = null) }
    }

    override fun loadToken() {
        val snapshot = (_state.value.card as? CardState.Ready)?.snapshot
        if (snapshot == null) {
            status(StatusMessage.warn("请先扫描卡片"))
            return
        }
        val contract = Validators.normalizeAddress(_state.value.tokenAddressInput)
        if (!Validators.isValidAddress(contract)) {
            status(StatusMessage.error("代币合约地址格式不正确（应为 0x + 40 位十六进制）"))
            return
        }
        val network = _state.value.selectedNetwork
        tokenJob?.cancel()
        tokenJob = viewModelScope.launch {
            set { it.copy(token = Async.Loading) }
            try {
                val info = withContext(Dispatchers.IO) {
                    tokenService.load(network.rpcUrl, contract, snapshot.address)
                }
                set { it.copy(token = Async.Data(info), tokenAddressInput = contract) }
                scheduleQuote()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.err("vm", "代币查询失败 contract=$contract", e)
                set { it.copy(token = Async.Failed(friendly(e))) }
            }
        }
    }

    override fun setRecipient(value: String) {
        set { it.copy(recipientInput = value, quote = null) }
        scheduleQuote()
    }

    override fun setAmount(value: String) {
        set { it.copy(amountInput = value, quote = null) }
        scheduleQuote()
    }

    override fun setCallValue(value: String) {
        set { it.copy(callValueInput = value, quote = null) }
        scheduleQuote()
    }

    override fun setCalldata(value: String) {
        set { it.copy(calldataInput = value, quote = null) }
        scheduleQuote()
    }

    override fun fillOwnAddress() {
        val address = _state.value.address ?: run {
            status(StatusMessage.warn("请先扫描卡片"))
            return
        }
        set {
            it.copy(
                recipientInput = Validators.toChecksumAddress(address),
                status = StatusMessage.ok("已填入本卡地址"),
            )
        }
        scheduleQuote()
    }

    override fun fillMaxAmount() {
        val snapshot = (_state.value.card as? CardState.Ready)?.snapshot ?: run {
            status(StatusMessage.warn("请先扫描卡片"))
            return
        }
        val network = _state.value.selectedNetwork
        viewModelScope.launch {
            try {
                if (_state.value.assetType == AssetType.TOKEN) {
                    val contract = Validators.normalizeAddress(_state.value.tokenAddressInput)
                    if (!Validators.isValidAddress(contract)) {
                        status(StatusMessage.warn("请先填写代币合约地址"))
                        return@launch
                    }
                    val decimals = withContext(Dispatchers.IO) {
                        tokenService.decimalsOrThrow(network.rpcUrl, contract)
                    }
                    val balance = withContext(Dispatchers.IO) {
                        tokenService.balanceOf(network.rpcUrl, contract, snapshot.address)
                    }
                    val human = Units.formatExact(balance, decimals)
                    set {
                        it.copy(
                            amountInput = human,
                            status = StatusMessage.ok("已填入全部代币余额"),
                        )
                    }
                } else {
                    val balance = withContext(Dispatchers.IO) {
                        rpc.balance(network.rpcUrl, snapshot.address)
                    }
                    val gasPrice = withContext(Dispatchers.IO) { rpc.gasPrice(network.rpcUrl) }
                    // Estimate the real gas limit instead of assuming 21000: zkSync-stack
                    // chains charge far more for a plain transfer.
                    val gasLimit = runCatching {
                        withContext(Dispatchers.IO) {
                            rpc.estimateGas(
                                network.rpcUrl,
                                snapshot.address,
                                snapshot.address,
                                null,
                                BigInteger.ZERO,
                            )
                        }
                    }.getOrElse { BigInteger.valueOf(300_000L) }
                    val reserve = gasPrice.multiply(gasLimit)
                    val spendable = balance.subtract(reserve).max(BigInteger.ZERO)
                    val human = Units.formatExact(spendable, network.nativeDecimals)
                    if (_state.value.assetType == AssetType.CONTRACT) {
                        set { it.copy(callValueInput = human, status = StatusMessage.ok("已填入可用余额（预留 gas）")) }
                    } else {
                        set { it.copy(amountInput = human, status = StatusMessage.ok("已填入余额 - gas 预留")) }
                    }
                }
                scheduleQuote()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.err("vm", "填充全部余额失败", e)
                status(StatusMessage.error("读取余额失败：${friendly(e)}"))
            }
        }
    }

    // ── QR ───────────────────────────────────────────────────────────────────

    override fun onQrScanned(raw: String) {
        AppLog.app("qr", "扫描内容 ${raw.take(160)}")
        when (val payload = QrPayloadParser.parse(raw)) {
            is QrPayload.Address -> {
                set {
                    it.copy(
                        recipientInput = Validators.toChecksumAddress(payload.address),
                        status = StatusMessage.ok("已填入收款地址 ${Validators.shortAddress(payload.address)}"),
                    )
                }
                scheduleQuote()
            }

            is QrPayload.ContractCall -> {
                val call = payload.call
                val current = _state.value.selectedNetwork
                val mismatch = call.chainId?.takeIf { it != current.chainId }?.let { chainId ->
                    val known = _state.value.networks.firstOrNull { it.chainId == chainId }
                    if (known != null) {
                        "二维码指定 chainId=$chainId（${known.displayName}），当前网络是 ${current.displayName}（${current.chainId}）。确认后将自动切换到 ${known.displayName}。"
                    } else {
                        "二维码指定 chainId=$chainId，但应用里还没有这条链。请先添加对应 RPC 网络，否则签名会发往错误的链。"
                    }
                }
                set { it.copy(qrReview = call, qrChainMismatch = mismatch) }
            }

            is QrPayload.Unsupported -> status(StatusMessage.error(payload.reason))
        }
    }

    override fun dismissQrReview() = set { it.copy(qrReview = null, qrChainMismatch = null) }

    override fun prefillFromQrReview() {
        val call = _state.value.qrReview ?: return
        if (!applyQrCall(call)) return
        set {
            it.copy(
                qrReview = null,
                qrChainMismatch = null,
                status = StatusMessage.ok("已填入合约调用，请核对后签署"),
            )
        }
        scheduleQuote()
    }

    override fun acceptQrReview() {
        val call = _state.value.qrReview ?: return
        val targetChainId = call.chainId
        if (targetChainId != null) {
            val known = _state.value.networks.firstOrNull { it.chainId == targetChainId }
            if (known == null) {
                status(
                    StatusMessage.error(
                        "缺少 chainId=$targetChainId 的网络配置，无法安全签署。请先在「添加自定义网络」中添加对应 RPC。",
                    ),
                )
                return
            }
        }
        if (!applyQrCall(call)) return
        set { it.copy(qrReview = null, qrChainMismatch = null) }
        requestSend()
    }

    /** Applies a scanned call to the form; returns false when the target is unknown. */
    private fun applyQrCall(call: QrContractCall): Boolean {
        val networks = _state.value.networks
        val target = call.chainId?.let { chainId -> networks.firstOrNull { it.chainId == chainId } }
        if (call.chainId != null && target == null) return false

        if (target != null && target.id != _state.value.selectedNetwork.id) {
            networkStore.setSelectedNetworkId(target.id)
            set { it.copy(selectedNetwork = target, token = Async.Idle, tokenAddressInput = "", quote = null) }
            refreshBalance()
        }

        val network = target ?: _state.value.selectedNetwork
        val callValue = call.valueWei
            ?.let { Units.formatExact(it, network.nativeDecimals) }
            .orEmpty()

        set {
            it.copy(
                assetType = AssetType.CONTRACT,
                recipientInput = if (call.to.isNotBlank()) Validators.toChecksumAddress(call.to) else it.recipientInput,
                calldataInput = call.data,
                callValueInput = callValue,
                quote = null,
            )
        }
        AppLog.app(
            "qr",
            "应用扫码合约调用 to=${call.to} chainId=${call.chainId} dataLen=${call.data.length}",
        )
        return true
    }

    // ── quote ────────────────────────────────────────────────────────────────

    /** Debounced gas preview; silently hides itself when the form is not yet valid. */
    private fun scheduleQuote() {
        quoteJob?.cancel()
        val snapshot = (_state.value.card as? CardState.Ready)?.snapshot
        if (snapshot == null) {
            set { it.copy(quote = null, quoteLoading = false) }
            return
        }
        quoteJob = viewModelScope.launch {
            delay(500)
            set { it.copy(quoteLoading = true) }
            try {
                val draft = buildDraft(snapshot, validate = false)
                if (draft == null) {
                    set { it.copy(quote = null, quoteLoading = false) }
                    return@launch
                }
                val network = _state.value.selectedNetwork
                val gasPrice = withContext(Dispatchers.IO) { rpc.gasPrice(network.rpcUrl) }
                val gasLimit = withContext(Dispatchers.IO) {
                    rpc.estimateGas(
                        network.rpcUrl,
                        snapshot.address,
                        draft.to,
                        draft.data,
                        draft.valueWei,
                    )
                }
                val nonce = withContext(Dispatchers.IO) { rpc.nonce(network.rpcUrl, snapshot.address) }
                set {
                    it.copy(
                        quote = TxQuote(
                            gasPriceWei = gasPrice,
                            gasLimit = gasLimit,
                            feeWei = gasPrice.multiply(gasLimit),
                            nonce = nonce,
                        ),
                        quoteLoading = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed estimate is expected while the form is still incomplete.
                set { it.copy(quote = null, quoteLoading = false) }
            }
        }
    }

    // ── send ─────────────────────────────────────────────────────────────────

    private data class Draft(
        val to: String,
        val data: String?,
        val valueWei: BigInteger,
        val assetLabel: String,
        val amountLabel: String,
    )

    /**
     * Builds the call shape from the form.
     *
     * With [validate] = false it tolerates partial input (used for the live gas
     * preview); with true it throws [AmountFormatException] / [IllegalStateException]
     * with user-facing Chinese messages.
     */
    private fun buildDraft(snapshot: CardSnapshot, validate: Boolean): Draft? {
        val state = _state.value
        val network = state.selectedNetwork
        val recipient = Validators.normalizeAddress(state.recipientInput)
        val contract = Validators.normalizeAddress(state.tokenAddressInput)

        return when (state.assetType) {
            AssetType.NATIVE -> {
                if (!Validators.isValidAddress(recipient)) {
                    if (validate) throw IllegalStateException("收款地址格式不正确（应为 0x + 40 位十六进制）")
                    return null
                }
                val amount = try {
                    Units.parseAmountToRaw(state.amountInput, network.nativeDecimals)
                } catch (e: AmountFormatException) {
                    if (validate) throw e
                    return null
                }
                if (amount.signum() <= 0 && validate) throw IllegalStateException("金额必须大于 0")
                Draft(
                    to = recipient,
                    data = null,
                    valueWei = amount,
                    assetLabel = network.nativeSymbol,
                    amountLabel = "${Units.formatExact(amount, network.nativeDecimals)} ${network.nativeSymbol}",
                )
            }

            AssetType.TOKEN -> {
                if (!Validators.isValidAddress(recipient)) {
                    if (validate) throw IllegalStateException("收款地址格式不正确（应为 0x + 40 位十六进制）")
                    return null
                }
                if (!Validators.isValidAddress(contract)) {
                    if (validate) throw IllegalStateException("代币合约地址格式不正确")
                    return null
                }
                val token = (state.token as? Async.Data)?.value
                if (token == null) {
                    if (validate) throw IllegalStateException("请先点「查询代币」读取精度与余额")
                    return null
                }
                val amount = try {
                    Units.parseAmountToRaw(state.amountInput, token.decimals)
                } catch (e: AmountFormatException) {
                    if (validate) throw e
                    return null
                }
                if (amount.signum() <= 0 && validate) throw IllegalStateException("金额必须大于 0")
                Draft(
                    to = contract,
                    data = Erc20.transferData(recipient, amount),
                    valueWei = BigInteger.ZERO,
                    assetLabel = token.symbol,
                    amountLabel = "${Units.formatExact(amount, token.decimals)} ${token.symbol}",
                )
            }

            AssetType.CONTRACT -> {
                if (!Validators.isValidAddress(recipient)) {
                    if (validate) throw IllegalStateException("合约地址格式不正确（应为 0x + 40 位十六进制）")
                    return null
                }
                val calldata = Validators.normalizeCalldata(state.calldataInput)
                if (!Validators.isValidCalldata(calldata)) {
                    if (validate) {
                        throw IllegalStateException(
                            "Calldata 格式不正确（应为 0x 开头、偶数长度、至少 4 字节十六进制）",
                        )
                    }
                    return null
                }
                val value = if (state.callValueInput.isBlank()) {
                    BigInteger.ZERO
                } else {
                    try {
                        Units.parseAmountToRaw(state.callValueInput, network.nativeDecimals)
                    } catch (e: AmountFormatException) {
                        if (validate) throw e
                        return null
                    }
                }
                val decoded = runCatching { CalldataDecoder.decode(calldata) }.getOrNull()
                Draft(
                    to = recipient,
                    data = calldata,
                    valueWei = value,
                    assetLabel = decoded?.signature ?: "合约调用（盲签）",
                    amountLabel = "${Units.formatExact(value, network.nativeDecimals)} ${network.nativeSymbol}",
                )
            }
        }
    }

    override fun requestSend() {
        if (_state.value.busy) return
        val snapshot = (_state.value.card as? CardState.Ready)?.snapshot ?: run {
            status(StatusMessage.warn("请先扫描卡片"))
            return
        }
        viewModelScope.launch {
            set {
                it.copy(
                    busy = true,
                    phase = TxPhase.QUOTING,
                    status = StatusMessage.info("正在构建并估算交易…"),
                )
            }
            try {
                val network = _state.value.selectedNetwork
                val draft = buildDraft(snapshot, validate = true)
                    ?: throw IllegalStateException("表单尚未填写完整")

                val gasPrice = withContext(Dispatchers.IO) { rpc.gasPrice(network.rpcUrl) }
                val gasLimit = withContext(Dispatchers.IO) {
                    rpc.estimateGas(
                        network.rpcUrl,
                        snapshot.address,
                        draft.to,
                        draft.data,
                        draft.valueWei,
                    )
                }
                val nonce = withContext(Dispatchers.IO) { rpc.nonce(network.rpcUrl, snapshot.address) }
                val fee = gasPrice.multiply(gasLimit)

                // Balance safety checks: a revert still burns gas, so stop before signing.
                val nativeBalance = withContext(Dispatchers.IO) {
                    rpc.balance(network.rpcUrl, snapshot.address)
                }
                when (_state.value.assetType) {
                    AssetType.TOKEN -> {
                        val token = (_state.value.token as? Async.Data)?.value
                        if (token != null) {
                            val raw = Units.parseAmountToRaw(_state.value.amountInput, token.decimals)
                            if (raw > token.balanceRaw) {
                                throw IllegalStateException(
                                    "金额超过代币余额：拟发 ${Units.formatExact(raw, token.decimals)}，" +
                                        "余额 ${Units.formatExact(token.balanceRaw, token.decimals)}。" +
                                        "请点「全部余额」精确填入。",
                                )
                            }
                        }
                        if (fee > nativeBalance) {
                            throw IllegalStateException(
                                "原生币不足以支付 gas：需要 ${Units.formatExact(fee, network.nativeDecimals)} " +
                                    "${network.nativeSymbol}，余额 ${Units.formatExact(nativeBalance, network.nativeDecimals)}。",
                            )
                        }
                    }
                    else -> {
                        val total = draft.valueWei.add(fee)
                        if (total > nativeBalance) {
                            throw IllegalStateException(
                                "金额 + gas 超过余额：需要 ${Units.formatExact(total, network.nativeDecimals)} " +
                                    "${network.nativeSymbol}，余额 ${Units.formatExact(nativeBalance, network.nativeDecimals)}。" +
                                    "请减小金额或点「全部余额」。",
                            )
                        }
                    }
                }

                val tx = UnsignedTx(
                    chainId = network.chainId,
                    nonce = nonce,
                    gasPrice = gasPrice,
                    gasLimit = gasLimit,
                    to = draft.to,
                    valueWei = draft.valueWei,
                    data = draft.data ?: "0x",
                )
                pendingTx = tx
                pendingAssetLabel = draft.assetLabel

                val decoded = if (_state.value.assetType == AssetType.CONTRACT) {
                    runCatching { CalldataDecoder.decode(tx.data) }.getOrNull()
                } else {
                    null
                }
                val warnings = buildList {
                    if (_state.value.assetType == AssetType.CONTRACT) {
                        if (decoded == null || !decoded.isKnown) {
                            add("无法解析该 calldata：这是盲签，请确认来源可信。")
                        }
                    }
                    if (!Validators.hasValidChecksum(_state.value.recipientInput) &&
                        _state.value.recipientInput.isNotBlank()
                    ) {
                        add("收款地址未使用 EIP-55 校验和大小写，已按原样使用。")
                    }
                    if (network.isCustom) {
                        add("这是自定义网络（${network.displayName}），请自行确认 RPC 可信。")
                    }
                }

                val summary = ConfirmSummary(
                    networkLabel = network.displayName,
                    chainId = network.chainId,
                    from = snapshot.address,
                    to = tx.to,
                    assetLabel = draft.assetLabel,
                    amountLabel = draft.amountLabel,
                    feeLabel = "${Units.formatExact(fee, network.nativeDecimals)} ${network.nativeSymbol}",
                    calldataLabel = if (_state.value.assetType == AssetType.CONTRACT) {
                        decoded?.signature ?: "未知函数（盲签）"
                    } else {
                        null
                    },
                    calldataPreview = if (_state.value.assetType == AssetType.CONTRACT) {
                        tx.data
                    } else {
                        null
                    },
                    totalLabel = "${Units.formatExact(tx.valueWei.add(fee), network.nativeDecimals)} ${network.nativeSymbol}",
                    warnings = warnings,
                )

                set {
                    it.copy(
                        busy = false,
                        phase = TxPhase.IDLE,
                        confirmOpen = true,
                        confirmSummary = summary,
                        quote = TxQuote(gasPrice, gasLimit, fee, nonce),
                        status = StatusMessage.info("请核对交易详情后贴卡签名"),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pendingTx = null
                AppLog.err("vm", "构建交易失败", e)
                set {
                    it.copy(
                        busy = false,
                        phase = TxPhase.IDLE,
                        status = StatusMessage.error(friendly(e)),
                    )
                }
            }
        }
    }

    override fun dismissConfirm() {
        pendingTx = null
        set { it.copy(confirmOpen = false, confirmSummary = null) }
    }

    override fun confirmSend() {
        val tx = pendingTx ?: return
        val snapshot = (_state.value.card as? CardState.Ready)?.snapshot ?: return
        val network = _state.value.selectedNetwork
        set {
            it.copy(
                confirmOpen = false,
                confirmSummary = null,
                busy = true,
                phase = TxPhase.AWAITING_CARD,
                status = StatusMessage.info("请将卡片贴到 NFC 感应区以签名…"),
            )
        }

        sendJob = viewModelScope.launch {
            try {
                val txHash = TxCodec.encodeAndHash(tx).second
                AppLog.app(
                    "send",
                    "请求签名 net=${network.displayName} chainId=${network.chainId} to=${tx.to} " +
                        "value=${tx.valueWei} gas=${tx.gasLimit} dataLen=${tx.data.length}",
                )

                val signature = when (val signed = cardGateway.sign(txHash)) {
                    is CardResult.Ok -> signed.value
                    is CardResult.Err -> {
                        fail("签名失败：${signed.message}")
                        return@launch
                    }
                }

                val publicKey = Numeric.hexStringToByteArray(snapshot.publicKeyHex)
                val signedHex = withContext(Dispatchers.Default) {
                    TxCodec.assembleSigned(tx, signature, publicKey)
                }

                set {
                    it.copy(
                        phase = TxPhase.BROADCASTING,
                        status = StatusMessage.info("正在广播交易…"),
                    )
                }
                AppLog.app("send", "signedRawTx=$signedHex")

                val broadcastHash = withContext(Dispatchers.IO) {
                    rpc.broadcast(network.rpcUrl, signedHex)
                }
                AppLog.app("send", "广播返回 txid=$broadcastHash，开始回链核实")

                set {
                    it.copy(
                        phase = TxPhase.CONFIRMING,
                        status = StatusMessage.info("已广播，正在等待链上确认…"),
                    )
                }

                val receipt = withContext(Dispatchers.IO) {
                    rpc.awaitReceipt(network.rpcUrl, broadcastHash, timeoutMs = 45_000L) { elapsed ->
                        _state.update {
                            it.copy(
                                status = StatusMessage.info(
                                    "已广播，等待链上确认… ${elapsed / 1000}s",
                                ),
                            )
                        }
                    }
                }

                when (receipt) {
                    1 -> {
                        AppLog.app("send", "链上执行成功 txid=$broadcastHash")
                        set {
                            it.copy(
                                status = StatusMessage.ok(
                                    "交易成功并已确认\n${network.txUrl(broadcastHash)}",
                                ),
                                phase = TxPhase.IDLE,
                            )
                        }
                    }
                    0 -> {
                        AppLog.err(
                            "send",
                            "交易上链但执行失败(revert) txid=$broadcastHash —— 币未转移，gas 已消耗",
                        )
                        set {
                            it.copy(
                                status = StatusMessage.error(
                                    "交易被链上拒绝（revert）：币没有转出，但 gas 已消耗。\n" +
                                        "常见原因：金额超过余额 / gas 不足 / 合约条件不满足。\n" +
                                        network.txUrl(broadcastHash),
                                ),
                                phase = TxPhase.IDLE,
                            )
                        }
                    }
                    else -> {
                        AppLog.err("send", "45 秒内未查到回执 txid=$broadcastHash")
                        set {
                            it.copy(
                                status = StatusMessage.warn(
                                    "已广播但暂未确认，请稍后到浏览器核对：\n${network.txUrl(broadcastHash)}",
                                ),
                                phase = TxPhase.IDLE,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.err("vm", "发送流程失败 net=${network.displayName}", e)
                set {
                    it.copy(
                        status = StatusMessage.error("交易失败：${friendly(e)}"),
                        phase = TxPhase.IDLE,
                    )
                }
            } finally {
                pendingTx = null
                pendingAssetLabel = ""
                set { it.copy(busy = false, phase = TxPhase.IDLE) }
                refreshBalance()
            }
        }
    }

    // ── log ──────────────────────────────────────────────────────────────────

    override fun openLog() {
        set { it.copy(logOpen = true, logText = AppLog.tail(2000)) }
    }

    override fun closeLog() = set { it.copy(logOpen = false) }

    override fun refreshLog() = set { it.copy(logText = AppLog.tail(2000)) }

    override fun clearLog() {
        AppLog.clear()
        set { it.copy(logText = AppLog.tail(2000), status = StatusMessage.ok("日志已清空")) }
    }

    override suspend fun exportLog(): File? = withContext(Dispatchers.IO) {
        runCatching {
            AppLog.exportToFile(getApplication())
        }.onFailure {
            AppLog.err("vm", "导出日志失败", it)
        }.getOrNull()
    }

    override fun dismissStatus() {
        set { it.copy(status = StatusMessage.info("就绪")) }
    }

    override fun onCleared() {
        super.onCleared()
        balanceJob?.cancel()
        tokenJob?.cancel()
        quoteJob?.cancel()
        sendJob?.cancel()
    }
}
