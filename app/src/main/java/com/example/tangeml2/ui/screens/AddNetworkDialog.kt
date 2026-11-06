package com.example.tangeml2.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.tangeml2.domain.RecoveryActions
import com.example.tangeml2.domain.RecoveryUiState
import com.example.tangeml2.ui.components.LabelledField

/**
 * Adds an arbitrary EVM chain.
 *
 * The chain id is never typed by the user: it is read back from the RPC so an
 * EIP-155 signature can never be bound to the wrong chain.
 */
@Composable
fun AddNetworkDialog(state: RecoveryUiState, actions: RecoveryActions) {
    var name by remember { mutableStateOf("") }
    var rpcUrl by remember { mutableStateOf("") }
    var explorer by remember { mutableStateOf("") }

    val rpcValid = rpcUrl.isBlank() ||
        rpcUrl.trim().startsWith("http://") ||
        rpcUrl.trim().startsWith("https://")

    AlertDialog(
        onDismissRequest = actions::dismissAddNetwork,
        title = {
            Text("添加自定义网络", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "填写名称与 RPC，chainId 会从节点自动拉取并校验。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                LabelledField(
                    value = name,
                    onValueChange = { name = it },
                    label = "网络名称",
                    placeholder = "例如 Base",
                )
                Spacer(Modifier.height(10.dp))
                LabelledField(
                    value = rpcUrl,
                    onValueChange = { rpcUrl = it },
                    label = "RPC URL",
                    placeholder = "https://…",
                    mono = true,
                    keyboardType = KeyboardType.Uri,
                    error = if (!rpcValid) "必须以 http:// 或 https:// 开头" else null,
                )
                Spacer(Modifier.height(10.dp))
                LabelledField(
                    value = explorer,
                    onValueChange = { explorer = it },
                    label = "区块浏览器 URL（可选）",
                    placeholder = "https://…",
                    mono = true,
                    keyboardType = KeyboardType.Uri,
                )
                if (state.busy) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "正在从 RPC 拉取 chainId…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { actions.addCustomNetwork(name, rpcUrl, explorer) },
                enabled = !state.busy && name.isNotBlank() && rpcUrl.isNotBlank() && rpcValid,
            ) {
                Text("拉取 ChainID 并添加")
            }
        },
        dismissButton = {
            TextButton(onClick = actions::dismissAddNetwork) { Text("取消") }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    )
}
