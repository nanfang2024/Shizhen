package com.framepick.app.ui.downloads

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.framepick.app.R
import com.framepick.app.data.download.DownloadTask
import com.framepick.app.ui.components.AppPageHeader
import com.framepick.app.ui.components.EmptyState
import com.framepick.app.ui.components.HeaderBrandMark
import com.framepick.app.ui.components.PrimaryActionButton
import com.framepick.app.ui.components.SecondaryActionButton
import com.framepick.app.ui.components.SectionHeader
import com.framepick.app.ui.components.WarmAccentBadge
import com.framepick.app.ui.components.WarmSurfaceCard
import com.framepick.app.ui.theme.extendedColors
import com.framepick.app.util.FileIntentUtils

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

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
            if (state.tasks.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Download,
                    title = stringResource(R.string.downloads_empty_title),
                    body = stringResource(R.string.downloads_empty_body),
                    showPawDecoration = true,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader(
                        title = stringResource(R.string.downloads_tasks_title),
                        supportingText = "任务由系统在后台执行，离开页面后仍会继续",
                    )
                    state.tasks.forEach { task ->
                        DownloadTaskCard(
                            task = task,
                            onCancel = viewModel::cancel,
                            onOpen = { uri -> FileIntentUtils.open(context, uri) },
                            onShare = { uri -> FileIntentUtils.share(context, uri) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    onCancel: (DownloadTask) -> Unit,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    WarmSurfaceCard {
        SectionHeader(
            title = task.displayName(),
            supportingText = "资源标识：${task.mediaItemId}",
            action = {
                WarmAccentBadge(
                    task.state.displayName(),
                    emphasis = task.state == WorkInfo.State.SUCCEEDED,
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
            WorkInfo.State.SUCCEEDED -> {
                Text(
                    "下载完成",
                    color = MaterialTheme.extendedColors.success,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    "保存位置：${task.outputUri}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                task.outputUri?.let { uri ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryActionButton(
                            "打开文件",
                            { onOpen(uri) },
                            Modifier.weight(1f),
                        )
                        SecondaryActionButton(
                            "分享",
                            { onShare(uri) },
                            Modifier.weight(1f),
                            icon = { Icon(Icons.Outlined.Share, contentDescription = null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                Text(
                    if (task.state == WorkInfo.State.CANCELLED) "下载已取消" else "下载失败",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge,
                )
                task.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    "详细原因可在「设置 → 诊断中心」中查看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun DownloadTask.displayName(): String = when (state) {
    WorkInfo.State.SUCCEEDED -> outputUri
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
        ?: "已完成的下载"
    else -> "媒体下载任务"
}

private fun WorkInfo.State.displayName(): String = when (this) {
    WorkInfo.State.ENQUEUED -> "等待中"
    WorkInfo.State.RUNNING -> "下载中"
    WorkInfo.State.SUCCEEDED -> "已完成"
    WorkInfo.State.FAILED -> "失败"
    WorkInfo.State.BLOCKED -> "等待条件"
    WorkInfo.State.CANCELLED -> "已取消"
}
