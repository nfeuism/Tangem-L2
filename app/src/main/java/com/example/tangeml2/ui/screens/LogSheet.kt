package com.example.tangeml2.ui.screens

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.tangeml2.domain.RecoveryActions
import com.example.tangeml2.domain.RecoveryUiState
import com.example.tangeml2.ui.theme.MonoStyle
import com.example.tangeml2.ui.theme.appColors

/** Diagnostics log viewer with export/clear. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogSheet(state: RecoveryUiState, actions: RecoveryActions) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = actions::closeLog,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "诊断日志",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "包含地址、交易数据与错误堆栈（不含私钥或卡片密码）。仅发给信任的人。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 420.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.appColors.codeSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            ) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(
                        text = state.logText.ifBlank { "（暂无日志）" },
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = actions::refreshLog,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = MaterialTheme.shapes.small,
                ) { Text("刷新") }
                OutlinedButton(
                    onClick = actions::clearLog,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = MaterialTheme.shapes.small,
                ) { Text("清空") }
                OutlinedButton(
                    onClick = {
                        // Writing the log touches the disk, so never do it on the main thread.
                        scope.launch {
                            val file = actions.exportLog() ?: return@launch
                            withContext(Dispatchers.Main) {
                                runCatching {
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, "Tangem L2 Recovery 日志")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "导出日志"))
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1.2f).height(44.dp),
                    shape = MaterialTheme.shapes.small,
                ) { Text("导出") }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = actions::closeLog, modifier = Modifier.fillMaxWidth()) {
                Text("关闭")
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}
