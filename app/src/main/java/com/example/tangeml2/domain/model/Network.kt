package com.example.tangeml2.domain.model

import java.math.BigInteger

/**
 * A target EVM chain.
 *
 * Built-in presets cover the networks the official Tangem app cannot reach; users
 * can add any other EVM chain at runtime (name + RPC URL, chain id pulled from the
 * node itself so it can never be mistyped).
 */
data class Network(
    val id: String,
    val displayName: String,
    val chainId: Long,
    val rpcUrl: String,
    val explorerUrl: String,
    val nativeSymbol: String = "ETH",
    val nativeDecimals: Int = 18,
    val isCustom: Boolean = false,
) {
    val chainIdBigInteger: BigInteger get() = BigInteger.valueOf(chainId)

    /** Short label used in the network selector. */
    fun label(): String = displayName

    /** `https://explorer/tx/0x…` */
    fun txUrl(txHash: String): String = "${explorerUrl.trimEnd('/')}/tx/$txHash"

    /** `https://explorer/address/0x…` */
    fun addressUrl(address: String): String = "${explorerUrl.trimEnd('/')}/address/$address"

    companion object {
        const val ID_SEPARATOR = ":"

        val MODE = Network(
            id = "mode",
            displayName = "Mode",
            chainId = 34_443L,
            rpcUrl = "https://mainnet.mode.network",
            explorerUrl = "https://explorer.mode.network",
        )
        val SCROLL = Network(
            id = "scroll",
            displayName = "Scroll",
            chainId = 534_352L,
            rpcUrl = "https://rpc.scroll.io",
            explorerUrl = "https://scrollscan.com",
        )
        val ZKLINK_NOVA = Network(
            id = "zklink-nova",
            displayName = "zkLink Nova",
            chainId = 810_180L,
            rpcUrl = "https://rpc.zklink.io",
            explorerUrl = "https://explorer.zklink.io",
        )
        val BASE = Network(
            id = "base",
            displayName = "Base",
            chainId = 8_453L,
            rpcUrl = "https://mainnet.base.org",
            explorerUrl = "https://basescan.org",
        )
        val SEPOLIA = Network(
            id = "sepolia",
            displayName = "Sepolia 测试网",
            chainId = 11_155_111L,
            rpcUrl = "https://ethereum-sepolia-rpc.publicnode.com",
            explorerUrl = "https://sepolia.etherscan.io",
        )

        /** Networks shipped with the app, in display order. */
        val PRESETS: List<Network> = listOf(MODE, SCROLL, ZKLINK_NOVA, BASE, SEPOLIA)
    }
}

/** Ethereum BIP-44 path used for the Tangem secp256k1 seed wallet. */
const val ETH_DERIVATION_PATH = "m/44'/60'/0'/0/0"

/**
 * Optional guard: when non-blank, a scanned card must derive exactly this address.
 * Leave blank for a generic, address-agnostic tool.
 */
const val EXPECTED_ADDRESS = ""
