@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.mediaextractor.ui.converter

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GifBox
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.mediaextractor.R
import com.example.mediaextractor.domain.converter.FrameFormat
import com.example.mediaextractor.ui.components.AppPageHeader
import com.example.mediaextractor.ui.components.EmptyState
import com.example.mediaextractor.ui.components.ErrorState
import com.example.mediaextractor.ui.components.ExpandableTechnicalDetails
import com.example.mediaextractor.ui.components.HeaderBrandMark
import com.example.mediaextractor.ui.components.MapleLeafDecoration
import com.example.mediaextractor.ui.components.PrimaryActionButton
import com.example.mediaextractor.ui.components.PrivacyNoticeCard
import com.example.mediaextractor.ui.components.SecondaryActionButton
import com.example.mediaextractor.ui.components.SectionHeader
import com.example.mediaextractor.ui.components.SuccessState
import com.example.mediaextractor.ui.components.WarmAccentBadge
import com.example.mediaextractor.ui.components.WarmSurfaceCard
import com.example.mediaextractor.ui.theme.autumnColors
import com.example.mediaextractor.util.FileIntentUtils
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun ConverterScreen(
    viewModel: ConverterViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val chooseInput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.onFileSelected(
                uri = it,
                name = context.queryDisplayName(it) ?: "本地媒体文件",
                mimeType = context.contentResolver.getType(it),
            )
        }
    }
    val chooseGifDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/gif"),
    ) { uri -> uri?.let(viewModel::setGifDestination) }
    val chooseMp4Destination = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4"),
    ) { uri -> uri?.let(viewModel::setMp4Destination) }
    val chooseFramesDestination = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.setFramesDestination(it)
        }
    }
    val running = state.status == ConversionStatus.RUNNING

    val openDestinationPicker: () -> Unit = {
        when (state.selectedMode) {
            ConversionMode.VIDEO_TO_GIF -> viewModel.prepareGifOutput()?.let(chooseGifDestination::launch)
            ConversionMode.GIF_TO_MP4 -> viewModel.prepareMp4Output()?.let(chooseMp4Destination::launch)
            ConversionMode.EXTRACT_FRAMES -> if (viewModel.prepareFramesOutput()) {
                chooseFramesDestination.launch(null)
            }
            null -> Unit
        }
    }
    val startTask: () -> Unit = {
        when (state.selectedMode) {
            ConversionMode.VIDEO_TO_GIF -> viewModel.startVideoToGif()
            ConversionMode.GIF_TO_MP4 -> viewModel.startGifToMp4()
            ConversionMode.EXTRACT_FRAMES -> viewModel.startExtractFrames()
            null -> Unit
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
                title = stringResource(R.string.converter_title),
                subtitle = stringResource(R.string.converter_subtitle),
                brandMark = HeaderBrandMark.PAW,
            )
            PrivacyNoticeCard("仅处理无需解密、无 DRM 且你有权保存的本地文件。")

            if (state.selectedFileUri == null) {
                EmptyState(
                    icon = Icons.Outlined.FolderOpen,
                    title = stringResource(R.string.no_file_selected),
                    body = "选择视频或 GIF 后，只会显示当前文件可用的转换功能。",
                    showPawDecoration = true,
                    action = {
                        PrimaryActionButton(
                            text = stringResource(R.string.choose_media),
                            onClick = { chooseInput.launch(arrayOf("video/*", "image/gif")) },
                        )
                    },
                )
            } else {
                SelectedFileCard(
                    state = state,
                    enabled = !running,
                    onChange = { chooseInput.launch(arrayOf("video/*", "image/gif")) },
                )
                if (state.selectedMode == null) {
                    ErrorState(
                        title = "不支持的文件",
                        body = "请选择常见视频文件或 GIF 动图。",
                    )
                } else {
                    OperationSelector(state, viewModel, running)
                    when (state.selectedMode) {
                        ConversionMode.VIDEO_TO_GIF -> VideoToGifParameters(state, viewModel, running)
                        ConversionMode.GIF_TO_MP4 -> GifToMp4Parameters(state, viewModel, running)
                        ConversionMode.EXTRACT_FRAMES -> ExtractFramesParameters(state, viewModel, running)
                        null -> Unit
                    }
                    SaveLocationCard(
                        label = state.destinationLabel,
                        enabled = !running,
                        onChange = openDestinationPicker,
                    )
                    PrimaryActionButton(
                        text = if (state.selectedMode == ConversionMode.EXTRACT_FRAMES) "开始提取" else "开始转换",
                        onClick = startTask,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !running && state.hasDestinationForSelectedMode(),
                    )
                }
            }

            ConversionStatusContent(
                state = state,
                onCancel = viewModel::cancel,
                onOpen = { state.outputUri?.let { FileIntentUtils.open(context, it) } },
                onShare = { state.outputUri?.let { FileIntentUtils.share(context, it) } },
                onAgain = {
                    if (state.selectedFileUri != null && !running) startTask()
                },
            )
        }
    }
}

@Composable
private fun SelectedFileCard(
    state: ConverterUiState,
    enabled: Boolean,
    onChange: () -> Unit,
) {
    WarmSurfaceCard {
        SectionHeader(
            title = "已选择文件",
            action = { SecondaryActionButton("更换文件", onChange, enabled = enabled) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LocalMediaThumbnail(
                uri = state.selectedFileUri,
                isGif = state.isGifFile(),
                modifier = Modifier.size(96.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    state.selectedFileName ?: "本地媒体文件",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        if (state.isGifFile()) "GIF 动图" else "视频",
                        state.selectedFileSize.formatFileSize(),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val dimensions = if (state.selectedWidth != null && state.selectedHeight != null) {
                    "${state.selectedWidth}×${state.selectedHeight}"
                } else null
                Text(
                    listOfNotNull(dimensions, state.selectedDurationMs.formatDuration()).joinToString(" · ")
                        .ifBlank { "正在读取媒体信息…" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LocalMediaThumbnail(uri: Uri?, isGif: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (uri == null) return
    if (isGif) {
        AsyncImage(
            model = uri,
            contentDescription = "所选 GIF 缩略图",
            modifier = modifier.clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Crop,
        )
        return
    }
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    retriever.release()
                }
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "所选视频缩略图",
            modifier = modifier.clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier.clip(RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Movie,
                contentDescription = "视频文件",
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun OperationSelector(
    state: ConverterUiState,
    viewModel: ConverterViewModel,
    running: Boolean,
) {
    WarmSurfaceCard {
        SectionHeader("选择转换功能")
        if (state.isGifFile()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = true,
                    onClick = { },
                    enabled = !running,
                    label = { Text("GIF 转 MP4") },
                    leadingIcon = { Icon(Icons.Outlined.Movie, contentDescription = null, Modifier.size(18.dp)) },
                )
            }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.selectedMode == ConversionMode.VIDEO_TO_GIF,
                    onClick = { viewModel.setMode(ConversionMode.VIDEO_TO_GIF) },
                    enabled = !running,
                    label = { Text("视频转 GIF") },
                    leadingIcon = { Icon(Icons.Outlined.GifBox, contentDescription = null, Modifier.size(18.dp)) },
                )
                FilterChip(
                    selected = state.selectedMode == ConversionMode.EXTRACT_FRAMES,
                    onClick = { viewModel.setMode(ConversionMode.EXTRACT_FRAMES) },
                    enabled = !running,
                    label = { Text("视频提取图片") },
                    leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null, Modifier.size(18.dp)) },
                )
            }
        }
    }
}

@Composable
private fun VideoToGifParameters(
    state: ConverterUiState,
    viewModel: ConverterViewModel,
    running: Boolean,
) {
    WarmSurfaceCard {
        SectionHeader("GIF 参数", supportingText = "默认 12 FPS、宽度 480，适合多数分享场景")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumericField("开始（秒）", state.startSeconds, viewModel::setStartSeconds, Modifier.weight(1f), running)
            NumericField("结束（秒）", state.endSeconds, viewModel::setEndSeconds, Modifier.weight(1f), running)
        }
        Text("帧率", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(5, 10, 12, 15, 20).forEach { fps ->
                FilterChip(
                    selected = state.fps == fps,
                    onClick = { viewModel.setFps(fps) },
                    label = { Text("$fps FPS") },
                    enabled = !running,
                )
            }
        }
        Text("输出宽度", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(null to "原尺寸", 320 to "320", 480 to "480", 720 to "720").forEach { (width, label) ->
                FilterChip(
                    selected = state.outputWidth == width,
                    onClick = { viewModel.setOutputWidth(width) },
                    label = { Text(label) },
                    enabled = !running,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("循环播放", style = MaterialTheme.typography.bodyMedium)
                Text("关闭时 GIF 只播放一次", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = state.loop, onCheckedChange = viewModel::setLoop, enabled = !running)
        }
        OutlinedTextField(
            value = state.gifOutputName,
            onValueChange = viewModel::setGifOutputName,
            label = { Text("输出文件名") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !running,
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        Text(
            "输出大小取决于片段时长、帧率和宽度；参数越高，文件通常越大。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GifToMp4Parameters(
    state: ConverterUiState,
    viewModel: ConverterViewModel,
    running: Boolean,
) {
    WarmSurfaceCard {
        SectionHeader("MP4 参数")
        Text(
            "转换为兼容大多数播放器和社交平台的 MP4 视频，并尽可能保持原动画节奏。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.mp4OutputName,
            onValueChange = viewModel::setMp4OutputName,
            label = { Text("输出文件名") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !running,
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
        )
        ExpandableTechnicalDetails("高级设置") {
            Text("编码：H.264", style = MaterialTheme.typography.bodySmall)
            Text("像素格式：yuv420p", style = MaterialTheme.typography.bodySmall)
            Text("播放节奏：读取 GIF 时间戳并保持原始帧率", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ExtractFramesParameters(
    state: ConverterUiState,
    viewModel: ConverterViewModel,
    running: Boolean,
) {
    val interval = state.frameIntervalSeconds.toDoubleOrNull()
    val expected = if (interval != null && interval > 0 && state.selectedDurationMs != null) {
        kotlin.math.ceil(state.selectedDurationMs / 1000.0 / interval).toInt().coerceAtLeast(1)
    } else null
    WarmSurfaceCard {
        SectionHeader("图片提取参数", supportingText = expected?.let { "预计提取约 $it 张" })
        NumericField(
            label = "每隔多少秒提取一张",
            value = state.frameIntervalSeconds,
            onValueChange = viewModel::setFrameInterval,
            modifier = Modifier.fillMaxWidth(),
            running = running,
        )
        Text("图片格式", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrameFormat.entries.forEach { format ->
                FilterChip(
                    selected = state.frameFormat == format,
                    onClick = { viewModel.setFrameFormat(format) },
                    label = { Text(format.name) },
                    enabled = !running,
                )
            }
        }
        Text(
            "文件名规则：frame_00001.${state.frameFormat.extension}、frame_00002.${state.frameFormat.extension}……",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SaveLocationCard(
    label: String,
    enabled: Boolean,
    onChange: () -> Unit,
) {
    WarmSurfaceCard {
        SectionHeader("保存位置")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "系统文件选择器会记住你上次使用的目录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SecondaryActionButton("更改位置", onChange, enabled = enabled)
        }
    }
}

@Composable
private fun NumericField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    running: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, maxLines = 1) },
        modifier = modifier,
        enabled = !running,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
    )
}

@Composable
private fun ConversionStatusContent(
    state: ConverterUiState,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onAgain: () -> Unit,
) {
    when (state.status) {
        ConversionStatus.IDLE -> state.errorMessage?.let { ErrorState("参数有误", it) }
        ConversionStatus.RUNNING -> {
            val timing = rememberTaskTiming(state.startedAtMillis, state.progress)
            WarmSurfaceCard {
                SectionHeader("任务进行中", supportingText = state.selectedMode.displayName())
                Text(state.statusMessage, color = MaterialTheme.colorScheme.primary)
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${state.progress.coerceIn(0, 100)}%", style = MaterialTheme.typography.labelMedium)
                    Text(timing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SecondaryActionButton("取消任务", onCancel, Modifier.fillMaxWidth())
            }
        }
        ConversionStatus.SUCCESS -> SuccessState(
            title = "转换完成",
            body = state.statusMessage,
        ) {
            ResultLine("输出文件", state.outputDisplayName())
            ResultLine("文件大小", state.outputFileSize.formatFileSize() ?: "未知")
            ResultLine("保存位置", state.destinationLabel)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryActionButton(
                    if (state.generatedFileCount > 1) "打开目录" else "打开文件",
                    onOpen,
                    Modifier.weight(1f),
                )
                if (state.generatedFileCount <= 1) {
                    SecondaryActionButton(
                        "分享",
                        onShare,
                        Modifier.weight(1f),
                        icon = { Icon(Icons.Outlined.Share, contentDescription = null, Modifier.size(18.dp)) },
                    )
                }
            }
            SecondaryActionButton("再次转换", onAgain, Modifier.fillMaxWidth())
        }
        ConversionStatus.FAILED -> ErrorState(
            title = "转换失败",
            body = buildString {
                append(state.errorMessage ?: "FFmpeg 执行失败。")
                append("\n详细原因可在“历史记录 → 诊断中心”中查看。")
            },
        )
        ConversionStatus.CANCELED -> WarmSurfaceCard {
            SectionHeader("任务已取消")
            Text("没有生成新的输出文件，你可以调整参数后重新开始。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResultLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.widthIn(min = 72.dp), style = MaterialTheme.typography.labelMedium)
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun rememberTaskTiming(startedAtMillis: Long?, progress: Int): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMillis) {
        while (startedAtMillis != null) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsedSeconds = startedAtMillis?.let { ((now - it) / 1000).coerceAtLeast(0) } ?: 0
    val remaining = if (progress in 1..99) elapsedSeconds * (100 - progress) / progress else null
    return "已用 ${elapsedSeconds.formatClock()}" + (remaining?.let { " · 预计剩余 ${it.formatClock()}" } ?: "")
}

private fun ConverterUiState.hasDestinationForSelectedMode(): Boolean = when (selectedMode) {
    ConversionMode.VIDEO_TO_GIF -> gifDestinationUri != null
    ConversionMode.GIF_TO_MP4 -> mp4DestinationUri != null
    ConversionMode.EXTRACT_FRAMES -> framesDestinationUri != null
    null -> false
}

private fun ConverterUiState.isGifFile(): Boolean =
    selectedMimeType == "image/gif" || selectedFileName.orEmpty().endsWith(".gif", true)

private fun ConverterUiState.outputDisplayName(): String = when (selectedMode) {
    ConversionMode.VIDEO_TO_GIF -> gifOutputName
    ConversionMode.GIF_TO_MP4 -> mp4OutputName
    ConversionMode.EXTRACT_FRAMES -> "${generatedFileCount} 张图片"
    null -> "输出文件"
}

private fun ConversionMode?.displayName(): String = when (this) {
    ConversionMode.VIDEO_TO_GIF -> "视频转 GIF"
    ConversionMode.GIF_TO_MP4 -> "GIF 转 MP4"
    ConversionMode.EXTRACT_FRAMES -> "视频提取图片"
    null -> "本地媒体任务"
}

private fun Long?.formatFileSize(): String? {
    if (this == null || this <= 0) return null
    val mb = this / (1024.0 * 1024.0)
    return if (mb >= 1) String.format(Locale.CHINA, "%.1f MB", mb) else "${this / 1024} KB"
}

private fun Long?.formatDuration(): String? {
    if (this == null || this <= 0) return null
    val totalSeconds = this / 1000
    return "%d:%02d".format(Locale.CHINA, totalSeconds / 60, totalSeconds % 60)
}

private fun Long.formatClock(): String = if (this >= 60) {
    "%d分%02d秒".format(Locale.CHINA, this / 60, this % 60)
} else {
    "${this}秒"
}

private fun android.content.Context.queryDisplayName(uri: Uri): String? = runCatching {
    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()
