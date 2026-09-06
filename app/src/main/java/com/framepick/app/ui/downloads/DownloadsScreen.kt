@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.framepick.app.ui.downloads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.framepick.app.R
import com.framepick.app.data.download.DownloadTask
import com.framepick.app.data.repository.HistoryRecord
import com.framepick.app.data.repository.OperationStatus
import com.framepick.app.data.repository.OperationType
import com.framepick.app.ui.components.AppPageHeader
import com.framepick.app.ui.components.EmptyState
import com.framepick.app.ui.components.HeaderBrandMark
import com.framepick.app.ui.components.SecondaryActionButton
import com.framepick.app.ui.components.SectionHeader
import com.framepick.app.ui.components.WarmAccentBadge
import com.framepick.app.ui.components.WarmSurfaceCard
import com.framepick.app.ui.theme.extendedColors
import com.framepick.app.util.FileIntentUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selected by remember { mutableStateOf<HistoryRecord?>(null) }
    var pendingDelete by remember { mutableStateOf<HistoryRecord?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AppPageHeader(
                title = stringResource(R.string.downloads_title),
                subtitle = stringResource(R.string.downloads_subtitle),
                brandMark = HeaderBrandMark.PAW,
            )
            if (state.tasks.isEmpty() && state.records.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Download,
                    title = stringResource(R.string.downloads_empty_title),
                    body = stringResource(R.string.downloads_empty_body),
                    showPawDecoration = true,
                )
            }
            if (state.tasks.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader(
                        title = stringResource(R.string.downloads_tasks_title),
                        supportingText = "任务由系统在后台执行，离开页面后仍会继续；完成或取消后自动归入下方任务历史",
                    )
                    state.tasks.forEach { task ->
                        DownloadTaskCard(
                            task = task,
                            onCancel = viewModel::cancel,
                        )
                    }
                }
            }
            if (state.records.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader(
                        title = stringResource(R.string.downloads_history_title),
                        supportingText = stringResource(R.string.history_subtitle),
                        action = {
                            SecondaryActionButton(
                                text = stringResource(R.string.clear_all),
                                onClick = { confirmClear = true },
                            )
                        },
                    )
                    state.records.forEach { record ->
                        HistoryCard(
                            record = record,
                            onDetails = { selected = record },
                            onDelete = { pendingDelete = record },
                            onOpen = record.outputUri
                                ?.takeIf { record.operationType != OperationType.PARSE }
                                ?.let { uri -> ({ FileIntentUtils.open(context, uri) }) },
                            onShare = record.outputUri
                                ?.takeIf { record.operationType != OperationType.PARSE }
                                ?.let { uri -> ({ FileIntentUtils.share(context, uri) }) },
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
                    viewModel.clearHistory()
                    confirmClear = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    onCancel: (DownloadTask) -> Unit,
) {
    WarmSurfaceCard {
        SectionHeader(
            title = "媒体下载任务",
            supportingText = "资源标识：${task.mediaItemId}",
            action = {
                WarmAccentBadge(
                    task.state.displayName(),
                    emphasis = false,
                )
            },
        )
        when (task.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("等待下载", style = MaterialTheme.typography.bodyMedium)
                SecondaryActionButton("取消下载", { onCancel(task) }, Modifier.fillMaxWidth())
            }
            WorkInfo.State.RUNNING -> {
                LinearProgressIndicator(
                    progress = { task.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("下载中 ${task.progress.coerceIn(0, 100)}%", color = MaterialTheme.colorScheme.primary)
                SecondaryActionButton("取消下载", { onCancel(task) }, Modifier.fillMaxWidth())
            }
            WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> Unit
        }
    }
}

private fun WorkInfo.State.displayName(): String = when (this) {
    WorkInfo.State.ENQUEUED -> "等待中"
    WorkInfo.State.RUNNING -> "下载中"
    WorkInfo.State.SUCCEEDED -> "已完成"
    WorkInfo.State.FAILED -> "失败"
    WorkInfo.State.BLOCKED -> "等待条件"
    WorkInfo.State.CANCELLED -> "已取消"
}

@Composable
private fun HistoryCard(
    record: HistoryRecord,
    onDetails: () -> Unit,
    onDelete: () -> Unit,
    onOpen: (() -> Unit)?,
    onShare: (() -> Unit)?,
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
            onShare?.let {
                IconButton(onClick = it) {
                    Icon(Icons.Outlined.Share, contentDescription = "分享输出文件")
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
        OperationStatus.SUCCESS -> Surface(
            color = MaterialTheme.extendedColors.successContainer,
            contentColor = MaterialTheme.extendedColors.onSuccessContainer,
            shape = RoundedCornerShape(50),
        ) {
            Text(
                status.displayName(),
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        OperationStatus.FAILED -> Surface(
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
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.disclaimer_close)) }
        },
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
