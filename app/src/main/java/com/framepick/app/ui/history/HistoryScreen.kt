@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.framepick.app.ui.history

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HistoryToggleOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.framepick.app.R
import com.framepick.app.data.repository.HistoryRecord
import com.framepick.app.data.repository.OperationStatus
import com.framepick.app.data.repository.OperationType
import com.framepick.app.ui.components.AppPageHeader
import com.framepick.app.ui.components.EmptyState
import com.framepick.app.ui.components.HeaderBrandMark
import com.framepick.app.ui.components.SecondaryActionButton
import com.framepick.app.ui.components.WarmAccentBadge
import com.framepick.app.ui.theme.autumnColors
import com.framepick.app.util.FileIntentUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<HistoryRecord?>(null) }
    var pendingDelete by remember { mutableStateOf<HistoryRecord?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppPageHeader(
                title = stringResource(R.string.history_title),
                subtitle = stringResource(R.string.history_subtitle),
                brandMark = HeaderBrandMark.MAPLE,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryActionButton(
                    text = "诊断中心",
                    onClick = {
                        showDiagnostics = true
                        viewModel.refreshDiagnostics()
                    },
                    icon = {
                        Icon(
                            Icons.Outlined.BugReport,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                SecondaryActionButton(
                    text = stringResource(R.string.clear_all),
                    onClick = { confirmClear = true },
                    enabled = state.records.isNotEmpty(),
                )
            }
            if (state.records.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.HistoryToggleOff,
                    title = stringResource(R.string.history_empty_title),
                    body = stringResource(R.string.history_empty_body),
                    showPawDecoration = true,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.records, key = HistoryRecord::id) { record ->
                        HistoryCard(
                            record = record,
                            onDetails = { selected = record },
                            onDelete = { pendingDelete = record },
                            onOpen = record.outputUri
                                ?.takeIf { record.operationType != OperationType.PARSE }
                                ?.let { uri -> ({ FileIntentUtils.open(context, uri) }) },
                        )
                    }
                }
            }
        }
    }

    selected?.let { record ->
        HistoryDetailsDialog(record = record, onDismiss = { selected = null })
    }
    pendingDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条记录？") },
            text = { Text("只删除历史记录，不会删除已保存的媒体文件。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(record.id)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部历史？") },
            text = { Text("只删除操作记录，不会删除已经保存的媒体文件。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAll()
                    confirmClear = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
    if (showDiagnostics) {
        AlertDialog(
            onDismissRequest = { showDiagnostics = false },
            title = { Text("诊断中心") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "请先复现一次问题，再返回这里分享报告。报告会遮盖 Cookie、Token、授权头、URL 查询参数和文件选择器 URI。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            enabled = state.diagnosticText.isNotBlank(),
                            onClick = {
                                clipboard.setText(AnnotatedString(state.diagnosticText))
                                Toast.makeText(context, "诊断报告已复制", Toast.LENGTH_SHORT).show()
                            },
                        ) { Text("复制") }
                        TextButton(
                            enabled = !state.diagnosticsLoading,
                            onClick = {
                                scope.launch {
                                    runCatching { viewModel.createDiagnosticReport() }
                                        .onSuccess { FileIntentUtils.shareDiagnosticReport(context, it) }
                                        .onFailure {
                                            Toast.makeText(context, "无法生成诊断报告", Toast.LENGTH_SHORT).show()
                                        }
                                }
                            },
                        ) { Text("分享") }
                        TextButton(onClick = viewModel::refreshDiagnostics) { Text("刷新") }
                        TextButton(onClick = viewModel::clearDiagnostics) { Text("清空日志") }
                    }
                    when {
                        state.diagnosticsLoading -> CircularProgressIndicator()
                        state.diagnosticsError != null -> Text(
                            state.diagnosticsError.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                        )
                        else -> SelectionContainer {
                            Text(
                                text = state.diagnosticText.ifBlank { "暂无诊断记录。" },
                                modifier = Modifier
                                    .heightIn(max = 420.dp)
                                    .verticalScroll(rememberScrollState()),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnostics = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun HistoryCard(
    record: HistoryRecord,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onDetails),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        record.operationType.displayName(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    HistoryStatusBadge(record.status)
                }
                Text(
                    record.source.lineSequence().firstOrNull().orEmpty(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    record.timestamp.formatTime(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            onOpen?.let {
                IconButton(onClick = it) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = "打开输出文件")
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除记录",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun HistoryStatusBadge(status: OperationStatus) {
    when (status) {
        OperationStatus.SUCCESS -> androidx.compose.material3.Surface(
            color = MaterialTheme.autumnColors.successContainer,
            contentColor = MaterialTheme.autumnColors.onSuccessContainer,
            shape = RoundedCornerShape(50),
        ) {
            Text(
                status.displayName(),
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        OperationStatus.FAILED -> androidx.compose.material3.Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(50),
        ) {
            Text(
                status.displayName(),
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        else -> WarmAccentBadge(status.displayName(), emphasis = status == OperationStatus.RUNNING)
    }
}

@Composable
private fun HistoryDetailsDialog(record: HistoryRecord, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(record.operationType.displayName()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("时间：${record.timestamp.formatTime()}")
                Text("状态：${record.status.displayName()}")
                Text("来源：${record.source}")
                record.outputUri?.let { Text("输出：$it") }
                record.errorReason?.let {
                    Text("原因：$it", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun OperationType.displayName(): String = when (this) {
    OperationType.PARSE -> "链接解析"
    OperationType.DOWNLOAD -> "媒体下载"
    OperationType.VIDEO_TO_GIF -> "视频转 GIF"
    OperationType.GIF_TO_MP4 -> "GIF 转 MP4"
    OperationType.EXTRACT_FRAMES -> "视频提取图片"
}

private fun OperationStatus.displayName(): String = when (this) {
    OperationStatus.WAITING -> "等待中"
    OperationStatus.RUNNING -> "进行中"
    OperationStatus.SUCCESS -> "成功"
    OperationStatus.FAILED -> "失败"
    OperationStatus.CANCELED -> "已取消"
}

private fun Long.formatTime(): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(this))
