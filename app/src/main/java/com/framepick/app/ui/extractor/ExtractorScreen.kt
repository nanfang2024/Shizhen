@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.framepick.app.ui.extractor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import coil.compose.AsyncImage
import com.framepick.app.R
import com.framepick.app.data.download.DownloadTask
import com.framepick.app.domain.model.DownloadStrategy
import com.framepick.app.domain.model.MediaItem
import com.framepick.app.domain.model.MediaType
import com.framepick.app.domain.model.ParsedMedia
import com.framepick.app.domain.model.SourceWatermark
import com.framepick.app.ui.components.AppPageHeader
import com.framepick.app.ui.components.EmptyState
import com.framepick.app.ui.components.ErrorState
import com.framepick.app.ui.components.ExpandableTechnicalDetails
import com.framepick.app.ui.components.HeaderBrandMark
import com.framepick.app.ui.components.MapleLeafDecoration
import com.framepick.app.ui.components.PawPrintDecoration
import com.framepick.app.ui.components.PrimaryActionButton
import com.framepick.app.ui.components.PrivacyNoticeCard
import com.framepick.app.ui.components.SecondaryActionButton
import com.framepick.app.ui.components.SectionHeader
import com.framepick.app.ui.components.SuccessState
import com.framepick.app.ui.components.WarmAccentBadge
import com.framepick.app.ui.components.WarmSurfaceCard
import com.framepick.app.ui.theme.extendedColors
import com.framepick.app.util.DiagnosticLogger
import java.util.Locale

@Composable
fun ExtractorScreen(
    viewModel: ExtractorViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingDownloads by remember { mutableStateOf(emptyList<MediaItem>()) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Notifications are optional; denying them must not block the actual WorkManager task.
        pendingDownloads.forEach(viewModel::download)
        pendingDownloads = emptyList()
    }
    val requestDownloads: (List<MediaItem>) -> Unit = { items ->
        val downloadable = items.filter(MediaItem::isDownloadAllowed)
        if (downloadable.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                pendingDownloads = downloadable
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                downloadable.forEach(viewModel::download)
            }
        }
    }

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
                title = stringResource(R.string.extractor_title),
                subtitle = stringResource(R.string.extractor_subtitle),
                brandMark = HeaderBrandMark.MAPLE,
            )
            PrivacyNoticeCard(text = stringResource(R.string.rights_notice))
            LinkInputCard(
                state = state,
                onInputChanged = viewModel::onInputChanged,
                onParse = viewModel::parseLink,
                onClear = viewModel::clear,
            )
            state.recognizedUrl?.let {
                RecognizedLinkCard(state)
            }
            ParseStatusContent(state)
            val parsedMedia = state.parsedMedia
            if (parsedMedia != null) {
                ParsedMediaContent(
                    media = parsedMedia,
                    downloads = state.downloads,
                    onDownload = { requestDownloads(listOf(it)) },
                    onDownloadAll = requestDownloads,
                )
            } else if (!state.isParsing && state.errorMessage == null) {
                EmptyState(
                    icon = Icons.Outlined.LinkOff,
                    title = stringResource(R.string.results_empty_title),
                    body = stringResource(R.string.results_empty_body),
                    showPawDecoration = true,
                )
            }
        }
    }
}

@Composable
private fun LinkInputCard(
    state: ExtractorUiState,
    onInputChanged: (String) -> Unit,
    onParse: () -> Unit,
    onClear: () -> Unit,
) {
    WarmSurfaceCard {
        SectionHeader("分享链接", supportingText = "可直接粘贴包含链接的整段分享文字")
        OutlinedTextField(
            value = state.inputText,
            onValueChange = onInputChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.extractor_input_label)) },
            placeholder = { Text(stringResource(R.string.extractor_input_placeholder)) },
            minLines = 3,
            maxLines = 5,
            shape = RoundedCornerShape(14.dp),
            enabled = !state.isParsing,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PrimaryActionButton(
                text = if (state.isParsing) "正在解析" else stringResource(R.string.parse_link),
                onClick = onParse,
                modifier = Modifier.weight(1f),
                enabled = !state.isParsing && state.inputText.isNotBlank(),
                icon = if (state.isParsing) {
                    { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                } else null,
            )
            SecondaryActionButton(
                text = stringResource(R.string.clear),
                onClick = onClear,
                enabled = !state.isParsing && state.inputText.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun RecognizedLinkCard(state: ExtractorUiState) {
    WarmSurfaceCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    Icons.Outlined.Link,
                    contentDescription = null,
                    modifier = Modifier.padding(9.dp).size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("已识别链接", style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(state.platform, state.domain).distinct().joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = "识别成功",
                tint = MaterialTheme.extendedColors.success,
            )
        }
        Text(
            state.recognizedUrl.orEmpty().compactUrl(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExpandableTechnicalDetails(title = "完整链接") {
            Text(
                state.recognizedUrl.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ParseStatusContent(state: ExtractorUiState) {
    when {
        state.isParsing -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    PawPrintDecoration(
                        Modifier.align(Alignment.TopEnd).padding(12.dp).size(30.dp),
                        alpha = 0.07f,
                    )
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("正在解析公开媒体资源……", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "正在识别作品信息与可公开访问的资源",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                            )
                        }
                    }
                }
            }
        }
        state.errorMessage != null -> ErrorState(
            title = state.status,
            body = state.errorMessage,
        )
        state.parsedMedia != null -> SuccessState(
            title = "解析成功",
            body = state.parsedMedia.summaryText(),
        )
        state.inputText.isNotBlank() -> Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(state.status, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ParsedMediaContent(
    media: ParsedMedia,
    downloads: Map<String, DownloadTask>,
    onDownload: (MediaItem) -> Unit,
    onDownloadAll: (List<MediaItem>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        WorkOverviewCard(media)
        SectionHeader("可下载资源", supportingText = "同类资源已合并，请先选择版本再预览或下载")

        val videos = media.items.filter { it.type == MediaType.VIDEO }
        val images = media.items.filter { it.type == MediaType.IMAGE }
        val gifs = media.items.filter { it.type == MediaType.GIF }
        val audio = media.items.filter { it.type == MediaType.AUDIO }
        val covers = media.items.filter { it.type == MediaType.COVER }
        val unknown = media.items.filter { it.type == MediaType.UNKNOWN }

        if (videos.isNotEmpty()) {
            VariantResourceCard(
                title = "视频",
                items = videos,
                fallbackThumbnail = media.thumbnailUrl,
                downloads = downloads,
                onDownload = onDownload,
                previewButtonText = "预览视频",
            )
        }
        if (images.isNotEmpty()) {
            ImageGalleryCard(
                title = "图片",
                items = images,
                downloads = downloads,
                onDownload = onDownload,
                onDownloadAll = onDownloadAll,
            )
        }
        if (gifs.isNotEmpty()) {
            VariantResourceCard(
                title = "GIF 动图",
                items = gifs,
                fallbackThumbnail = media.thumbnailUrl,
                downloads = downloads,
                onDownload = onDownload,
                previewButtonText = "预览动图",
            )
        }
        if (audio.isNotEmpty()) {
            VariantResourceCard(
                title = "音频",
                items = audio,
                fallbackThumbnail = null,
                downloads = downloads,
                onDownload = onDownload,
                previewButtonText = "试听",
            )
        }
        if (covers.isNotEmpty()) {
            ImageGalleryCard(
                title = "封面",
                items = covers,
                downloads = downloads,
                onDownload = onDownload,
                onDownloadAll = onDownloadAll,
            )
        }
        unknown.forEach { item ->
            VariantResourceCard(
                title = "其他资源",
                items = listOf(item),
                fallbackThumbnail = media.thumbnailUrl,
                downloads = downloads,
                onDownload = onDownload,
                previewButtonText = "预览",
            )
        }
    }
}

@Composable
private fun WorkOverviewCard(media: ParsedMedia) {
    WarmSurfaceCard {
        SectionHeader("作品概览", supportingText = media.platform)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (media.thumbnailUrl != null) {
                AsyncImage(
                    model = media.thumbnailUrl,
                    contentDescription = "作品缩略图",
                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop,
                    onError = { result ->
                        DiagnosticLogger.warning(
                            "PREVIEW",
                            "work_thumbnail_failed",
                            details = mapOf("url" to media.thumbnailUrl),
                            failure = result.result.throwable,
                        )
                    },
                )
            } else {
                Surface(
                    modifier = Modifier.size(96.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(
                        Icons.Outlined.Movie,
                        contentDescription = null,
                        modifier = Modifier.padding(28.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    media.title ?: "未提供标题",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                media.author?.let {
                    Text(
                        "作者：$it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "${media.items.size} 个资源 · ${media.platform}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        ExpandableTechnicalDetails("作品详情") {
            Text("来源：${media.sourceUrl}", style = MaterialTheme.typography.bodySmall)
            Text("资源总数：${media.items.size}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun VariantResourceCard(
    title: String,
    items: List<MediaItem>,
    fallbackThumbnail: String?,
    downloads: Map<String, DownloadTask>,
    onDownload: (MediaItem) -> Unit,
    previewButtonText: String,
) {
    var selectedId by rememberSaveable(items.joinToString { it.id }) {
        mutableStateOf(items.firstOrNull { it.isRecommended }?.id ?: items.first().id)
    }
    var isPreviewing by rememberSaveable { mutableStateOf(false) }
    val selected = items.firstOrNull { it.id == selectedId } ?: items.first()
    val selectedTask = downloads[selected.id]

    WarmSurfaceCard {
        SectionHeader(
            title = title,
            supportingText = if (items.size > 1) "${items.size} 个可选版本" else selected.type.displayName(),
            action = {
                if (selected.isPreviewOnly) WarmAccentBadge("仅试听片段")
                else if (selected.isRecommended) WarmAccentBadge("推荐", emphasis = true)
            },
        )
        if (selected.type != MediaType.AUDIO) SourceWatermarkLabel(selected.sourceWatermark)
        items.forEach { item ->
            val selectedRow = item.id == selected.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .selectable(
                        selected = selectedRow,
                        onClick = {
                            selectedId = item.id
                            isPreviewing = false
                        },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RadioButton(
                    selected = selectedRow,
                    onClick = null,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        item.variantTitle(),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${item.format?.uppercase(Locale.ROOT) ?: "未知格式"} · ${item.fileSize.formatSize()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    item.isPreviewOnly -> WarmAccentBadge("仅试听")
                    item.isRecommended -> WarmAccentBadge("推荐", emphasis = true)
                }
            }
        }
        if (selected.isPreviewOnly) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    "（仅试听片段）这不是完整歌曲，只会保存平台公开提供的试听部分。",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        MediaPreview(
            item = selected,
            fallbackThumbnail = fallbackThumbnail,
            isPreviewing = isPreviewing,
            onTogglePreview = { isPreviewing = !isPreviewing },
            buttonText = previewButtonText,
        )
        PrimaryActionButton(
            text = selected.downloadLabel(selectedTask),
            onClick = { onDownload(selected) },
            modifier = Modifier.fillMaxWidth(),
            enabled = selected.isDownloadAllowed() &&
                selectedTask?.state !in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING),
            icon = { Icon(Icons.Outlined.Download, contentDescription = null, Modifier.size(18.dp)) },
        )
        if (selected.sourceWatermark == SourceWatermark.WATERMARKED) {
            Text(
                if (selected.allowWatermarkedDownload) {
                    "该版本含平台水印，下载后会原样保留。"
                } else {
                    "该版本被平台明确标记为带水印，本工具不提供下载。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        ExpandableTechnicalDetails {
            TechnicalItemDetails(selected)
        }
    }
}

@Composable
private fun ImageGalleryCard(
    title: String,
    items: List<MediaItem>,
    downloads: Map<String, DownloadTask>,
    onDownload: (MediaItem) -> Unit,
    onDownloadAll: (List<MediaItem>) -> Unit,
) {
    var selectedId by rememberSaveable(items.joinToString { it.id }) { mutableStateOf(items.first().id) }
    var expandedPreview by rememberSaveable { mutableStateOf(false) }
    val selected = items.firstOrNull { it.id == selectedId } ?: items.first()
    val selectedTask = downloads[selected.id]
    val downloadable = items.filter(MediaItem::isDownloadAllowed)

    WarmSurfaceCard {
        SectionHeader(title, supportingText = "${items.size} 张")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = MediaItem::id) { item ->
                val chosen = item.id == selected.id
                Card(
                    modifier = Modifier
                        .size(96.dp)
                        .clickable {
                            selectedId = item.id
                            expandedPreview = false
                        },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        if (chosen) 2.dp else 1.dp,
                        if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    elevation = CardDefaults.cardElevation(0.dp),
                ) {
                    AsyncImage(
                        model = item.previewUrl ?: item.mediaUrl,
                        contentDescription = "$title ${items.indexOf(item) + 1}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
        if (expandedPreview) {
            AsyncImage(
                model = selected.previewUrl ?: selected.mediaUrl,
                contentDescription = "所选${title}预览",
                modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Fit,
            )
        }
        Text(
            "当前：${items.indexOf(selected) + 1}/${items.size} · ${selected.resolutionText()} · ${selected.format?.uppercase(Locale.ROOT) ?: "未知格式"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SecondaryActionButton(
                text = if (expandedPreview) "收起图片" else "查看图片",
                onClick = { expandedPreview = !expandedPreview },
            )
            SecondaryActionButton(
                text = selected.downloadLabel(selectedTask),
                onClick = { onDownload(selected) },
                enabled = selected.isDownloadAllowed() &&
                    selectedTask?.state !in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING),
            )
        }
        if (items.size > 1) {
            PrimaryActionButton(
                text = "保存全部$title（${downloadable.size}）",
                onClick = { onDownloadAll(downloadable) },
                modifier = Modifier.fillMaxWidth(),
                enabled = downloadable.isNotEmpty(),
                icon = { Icon(Icons.Outlined.Download, contentDescription = null, Modifier.size(18.dp)) },
            )
        }
        ExpandableTechnicalDetails {
            TechnicalItemDetails(selected)
        }
    }
}

private fun MediaItem.isDownloadAllowed(): Boolean =
    sourceWatermark != SourceWatermark.WATERMARKED || allowWatermarkedDownload

@Composable
private fun SourceWatermarkLabel(status: SourceWatermark) {
    val (text, color) = when (status) {
        SourceWatermark.PUBLIC_ORIGINAL -> "来源：平台公开原始源" to MaterialTheme.extendedColors.success
        SourceWatermark.PUBLIC_CLEAN -> "来源：平台公开无水印播放源" to MaterialTheme.extendedColors.success
        SourceWatermark.WATERMARKED -> "来源：平台明确标记为带水印" to MaterialTheme.colorScheme.error
        SourceWatermark.UNKNOWN -> "来源：水印状态未验证" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, color = color, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun MediaPreview(
    item: MediaItem,
    fallbackThumbnail: String?,
    isPreviewing: Boolean,
    onTogglePreview: () -> Unit,
    buttonText: String,
) {
    val previewUrl = item.previewUrl
    when (item.type) {
        MediaType.IMAGE, MediaType.COVER -> Unit
        MediaType.GIF -> if (item.previewAsVideo) {
            StreamPreviewControl(
                previewUrl = previewUrl,
                fallbackThumbnail = fallbackThumbnail,
                isPreviewing = isPreviewing,
                buttonText = buttonText,
                onTogglePreview = onTogglePreview,
            )
            Text(
                "部分平台的 GIF 公开源实际为循环 MP4；下载后会在本机真实转换为 GIF。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (previewUrl != null) {
            if (isPreviewing) {
                AsyncImage(
                    model = previewUrl,
                    contentDescription = "GIF 资源预览",
                    modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Fit,
                )
            }
            SecondaryActionButton(
                if (isPreviewing) "停止预览" else buttonText,
                onTogglePreview,
                Modifier.fillMaxWidth(),
            )
        }
        MediaType.VIDEO, MediaType.AUDIO -> StreamPreviewControl(
            previewUrl = previewUrl,
            fallbackThumbnail = fallbackThumbnail.takeIf { item.type == MediaType.VIDEO },
            isPreviewing = isPreviewing,
            buttonText = buttonText,
            previewHeight = if (item.type == MediaType.AUDIO) 88.dp else 220.dp,
            onTogglePreview = onTogglePreview,
        )
        MediaType.UNKNOWN -> Text(
            "该类型暂不支持应用内预览。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StreamPreviewControl(
    previewUrl: String?,
    fallbackThumbnail: String?,
    isPreviewing: Boolean,
    buttonText: String,
    onTogglePreview: () -> Unit,
    previewHeight: Dp = 220.dp,
) {
    if (isPreviewing && previewUrl != null) {
        StreamingPreview(
            url = previewUrl,
            modifier = Modifier.fillMaxWidth().height(previewHeight).clip(RoundedCornerShape(14.dp)),
        )
    } else if (fallbackThumbnail != null) {
        AsyncImage(
            model = fallbackThumbnail,
            contentDescription = "媒体封面",
            modifier = Modifier.fillMaxWidth().height(168.dp).clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Crop,
        )
    }
    SecondaryActionButton(
        text = if (isPreviewing) "停止预览" else buttonText,
        onClick = onTogglePreview,
        modifier = Modifier.fillMaxWidth(),
        enabled = previewUrl != null,
        icon = {
            Icon(
                if (isPreviewing) Icons.Outlined.StopCircle else Icons.Outlined.PlayCircleOutline,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
    )
    Text(
        "预览使用平台临时媒体流，可能过期；下载仍按上方选择的版本执行。",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun StreamingPreview(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var errorMessage by remember(url) { mutableStateOf<String?>(null) }
    val videoView = remember(url) {
        VideoView(context).apply {
            val controller = MediaController(context)
            setMediaController(controller)
            controller.setAnchorView(this)
        }
    }
    DisposableEffect(videoView, url) {
        videoView.setOnPreparedListener { player ->
            DiagnosticLogger.info(
                category = "PREVIEW",
                event = "stream_preview_prepared",
                details = mapOf(
                    "url" to url,
                    "durationMs" to player.duration,
                    "videoSize" to "${player.videoWidth}x${player.videoHeight}",
                ),
            )
            player.isLooping = true
            errorMessage = null
            videoView.start()
        }
        videoView.setOnErrorListener { _, what, extra ->
            DiagnosticLogger.warning(
                category = "PREVIEW",
                event = "stream_preview_failed",
                details = mapOf("url" to url, "what" to what, "extra" to extra),
            )
            errorMessage = "这个临时媒体流无法在线播放，可尝试推荐版本或下载后打开。"
            true
        }
        videoView.setVideoURI(url.toUri(), mapOf("User-Agent" to STREAM_PREVIEW_USER_AGENT))
        onDispose {
            videoView.stopPlayback()
            videoView.setOnPreparedListener(null)
            videoView.setOnErrorListener(null)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AndroidView(factory = { videoView }, modifier = modifier)
        errorMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private const val STREAM_PREVIEW_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"

@Composable
private fun TechnicalItemDetails(item: MediaItem) {
    Text("媒体地址：${item.mediaUrl}", style = MaterialTheme.typography.bodySmall)
    Text("下载策略：${item.downloadStrategy.name}", style = MaterialTheme.typography.bodySmall)
    item.formatSelector?.let { Text("format id：$it", style = MaterialTheme.typography.bodySmall) }
    item.codecSummary?.let { Text("编码：$it", style = MaterialTheme.typography.bodySmall) }
    item.watermarkNote?.let { Text("来源说明：$it", style = MaterialTheme.typography.bodySmall) }
}

private fun ParsedMedia.summaryText(): String {
    val parts = buildList {
        val videoCount = items.count { it.type == MediaType.VIDEO }
        val imageCount = items.count { it.type == MediaType.IMAGE }
        val gifCount = items.count { it.type == MediaType.GIF }
        val audioCount = items.count { it.type == MediaType.AUDIO }
        val coverCount = items.count { it.type == MediaType.COVER }
        if (videoCount > 0) add("$videoCount 个视频版本")
        if (imageCount > 0) add("$imageCount 张图片")
        if (gifCount > 0) add("$gifCount 个 GIF")
        if (audioCount > 0) add("$audioCount 段音频")
        if (coverCount > 0) add("$coverCount 张封面")
    }
    return parts.joinToString(" · ").ifBlank { "已找到 ${items.size} 个媒体资源" }
}

private fun MediaItem.variantTitle(): String = when (type) {
    MediaType.AUDIO -> buildString {
        append(if (isPreviewOnly) "仅试听片段" else "公开音频")
        qualityLabel?.let { append(" · $it") }
    }
    else -> qualityLabel ?: resolutionText()
}

private fun MediaItem.downloadLabel(task: DownloadTask?): String = when {
    task?.state == WorkInfo.State.RUNNING -> "下载中 ${task.progress}%"
    task?.state == WorkInfo.State.ENQUEUED || task?.state == WorkInfo.State.BLOCKED -> "等待下载"
    task?.state == WorkInfo.State.SUCCEEDED -> "重新下载"
    task?.state == WorkInfo.State.FAILED || task?.state == WorkInfo.State.CANCELLED -> "重新下载"
    isPreviewOnly -> "下载试听片段"
    type == MediaType.AUDIO -> "下载完整音频"
    type == MediaType.VIDEO -> "下载视频"
    type == MediaType.IMAGE || type == MediaType.COVER -> "保存当前图片"
    type == MediaType.GIF -> "下载 GIF"
    else -> "下载资源"
}

private fun MediaType.displayName(): String = when (this) {
    MediaType.VIDEO -> "视频"
    MediaType.IMAGE -> "图片"
    MediaType.GIF -> "GIF"
    MediaType.COVER -> "封面"
    MediaType.AUDIO -> "音频"
    MediaType.UNKNOWN -> "未知资源"
}

private fun MediaItem.resolutionText(): String = when {
    width != null && height != null -> "${width}×${height}"
    height != null -> "${height}P"
    else -> "清晰度未知"
}

private fun Long?.formatSize(): String {
    if (this == null || this <= 0) return "未知"
    val mb = this / (1024.0 * 1024.0)
    return if (mb >= 1) String.format(Locale.CHINA, "%.1f MB", mb) else "${this / 1024} KB"
}

private fun String.compactUrl(): String = if (length <= 76) this else take(48) + "…" + takeLast(18)
