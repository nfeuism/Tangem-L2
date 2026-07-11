package com.example.tangeml2

import java.math.BigInteger

/**
 * 目标网络配置。data class（不再是 enum），以支持用户运行时添加自定义网络。
 * 内置三条 L2 的 chainId / RPC 已对照 chainid.network 官方注册表校验。
 * 所有 EVM 链都兼容标准 Legacy(type-0) 交易 + eth_sendRawTransaction。
 */
data class L2Network(
    val displayName: String,
    val chainId: Long,
    val rpcUrl: String,
    val explorer: String,
    val isCustom: Boolean = false,
) {
    val chainIdBI: BigInteger get() = BigInteger.valueOf(chainId)

    /** 下拉框显示文本 */
    fun label(): String = "$displayName (chainId=$chainId)"

    companion object {
        /** 内置预置网络 */
        val PRESETS: List<L2Network> = listOf(
            L2Network("Mode Network", 34_443L, "https://mainnet.mode.network", "https://explorer.mode.network"),
            L2Network("Scroll", 534_352L, "https://rpc.scroll.io", "https://scrollscan.com"),
            L2Network("zkLink Nova", 810_180L, "https://rpc.zklink.io", "https://explorer.zklink.io"),
            // 便于测试网演练（地址与主网一致，可先在这里跑通签名恢复再动真资产）
            L2Network("Sepolia Testnet", 11_155_111L, "https://ethereum-sepolia-rpc.publicnode.com", "https://sepolia.etherscan.io"),
        )
    }
}

/**
 * 以太坊标准 HD 路径。你的主网地址就是 secp256k1 主种子在此路径下的派生子公钥。
 * 因为 zkLink/Mode/Scroll 都是 EVM 链，同一路径派生出的地址与主网完全一致。
 */
const val ETH_DERIVATION_PATH = "m/44'/60'/0'/0/0"

/** 你的主网地址；扫码派生后自动比对。测试地址快填按钮也会用到它。 */
// 留空 = 通用工具，不绑定任何个人地址。如需把派生地址与你已知地址做校验，可自行填入你的地址。
const val EXPECTED_ADDRESS = ""
