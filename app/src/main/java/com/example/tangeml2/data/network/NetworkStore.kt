package com.example.tangeml2.data.network

import android.content.Context
import com.example.tangeml2.domain.model.Network
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for user-added networks and the last selected chain.
 *
 * The previous build kept custom networks in memory only, so a chain the user
 * added vanished on restart — the exact opposite of the app's purpose.
 */
class NetworkStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("tangem_l2_networks_v2", Context.MODE_PRIVATE)

    /** Built-in presets followed by the user's saved chains. */
    fun load(): List<Network> {
        val custom = readCustom()
        val presetChainIds = Network.PRESETS.map { it.chainId }.toSet()
        val filtered = custom.filterNot { it.chainId in presetChainIds }
        return Network.PRESETS + filtered
    }

    fun addCustom(network: Network) {
        val current = readCustom().filterNot { it.chainId == network.chainId || it.id == network.id }
        writeCustom(current + network.copy(isCustom = true))
    }

    fun removeCustom(networkId: String) {
        writeCustom(readCustom().filterNot { it.id == networkId })
    }

    /** Id of the chain to select on start-up. */
    fun selectedNetworkId(): String? = prefs.getString(KEY_SELECTED, null)

    fun setSelectedNetworkId(networkId: String) {
        prefs.edit().putString(KEY_SELECTED, networkId).apply()
    }

    private fun readCustom(): List<Network> {
        val raw = prefs.getString(KEY_CUSTOM, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val chainId = item.optLong("chainId", -1L)
                    val rpc = item.optString("rpcUrl")
                    if (chainId <= 0 || rpc.isBlank()) continue
                    add(
                        Network(
                            id = item.optString("id").ifBlank { "custom:$chainId" },
                            displayName = item.optString("displayName").ifBlank { "Chain $chainId" },
                            chainId = chainId,
                            rpcUrl = rpc,
                            explorerUrl = item.optString("explorerUrl").ifBlank { rpc },
                            nativeSymbol = item.optString("nativeSymbol").ifBlank { "ETH" },
                            nativeDecimals = item.optInt("nativeDecimals", 18).coerceIn(0, 36),
                            isCustom = true,
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            // Corrupt payload: drop it rather than crash on start-up.
            prefs.edit().remove(KEY_CUSTOM).apply()
            emptyList()
        }
    }

    private fun writeCustom(networks: List<Network>) {
        val array = JSONArray()
        networks.forEach { network ->
            array.put(
                JSONObject().apply {
                    put("id", network.id)
                    put("displayName", network.displayName)
                    put("chainId", network.chainId)
                    put("rpcUrl", network.rpcUrl)
                    put("explorerUrl", network.explorerUrl)
                    put("nativeSymbol", network.nativeSymbol)
                    put("nativeDecimals", network.nativeDecimals)
                },
            )
        }
        prefs.edit().putString(KEY_CUSTOM, array.toString()).apply()
    }

    private companion object {
        const val KEY_CUSTOM = "custom_networks"
        const val KEY_SELECTED = "selected_network"
    }
}
