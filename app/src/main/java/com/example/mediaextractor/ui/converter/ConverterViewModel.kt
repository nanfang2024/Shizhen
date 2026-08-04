package com.example.mediaextractor.ui.converter

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediaextractor.MediaExtractorApplication
import com.example.mediaextractor.data.repository.OperationStatus
import com.example.mediaextractor.data.repository.OperationType
import com.example.mediaextractor.domain.converter.ExtractFramesRequest
import com.example.mediaextractor.domain.converter.FrameFormat
import com.example.mediaextractor.domain.converter.GifToMp4Request
import com.example.mediaextractor.domain.converter.VideoToGifRequest
import com.example.mediaextractor.util.DiagnosticLogger
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ConversionStatus { IDLE, RUNNING, SUCCESS, FAILED, CANCELED }

enum class ConversionMode { VIDEO_TO_GIF, GIF_TO_MP4, EXTRACT_FRAMES }

data class ConverterUiState(
    val selectedFileUri: Uri? = null,
    val selectedFileName: String? = null,
    val selectedMimeType: String? = null,
    val selectedFileSize: Long? = null,
    val selectedWidth: Int? = null,
    val selectedHeight: Int? = null,
    val selectedDurationMs: Long? = null,
    val selectedMode: ConversionMode? = null,
    val startSeconds: String = "0",
    val endSeconds: String = "5",
    val fps: Int = 12,
    val outputWidth: Int? = 480,
    val loop: Boolean = true,
    val gifOutputName: String = "output.gif",
    val mp4OutputName: String = "output.mp4",
    val frameIntervalSeconds: String = "2",
    val frameFormat: FrameFormat = FrameFormat.JPG,
    val gifDestinationUri: Uri? = null,
    val mp4DestinationUri: Uri? = null,
    val framesDestinationUri: Uri? = null,
    val destinationLabel: String = "尚未选择保存位置",
    val status: ConversionStatus = ConversionStatus.IDLE,
    val progress: Int = 0,
    val statusMessage: String = "等待选择本地文件",
    val errorMessage: String? = null,
    val outputUri: String? = null,
    val outputFileSize: Long? = null,
    val generatedFileCount: Int = 0,
    val startedAtMillis: Long? = null,
)

class ConverterViewModel(application: Application) : AndroidViewModel(application) {
    private val app = getApplication<MediaExtractorApplication>()
    private val converter = app.mediaConverter
    private val historyRepository = app.historyRepository
    private val _uiState = MutableStateFlow(ConverterUiState())
    val uiState: StateFlow<ConverterUiState> = _uiState.asStateFlow()
    private var conversionJob: Job? = null
    private var activeHistoryId: Long = -1L

    fun onFileSelected(uri: Uri, name: String, mimeType: String?) {
        if (_uiState.value.status == ConversionStatus.RUNNING) return
        DiagnosticLogger.info(
            category = "CONVERTER_UI",
            event = "file_selected",
            details = mapOf("uri" to uri, "name" to name, "mime" to mimeType),
        )
        _uiState.update {
            it.copy(
                selectedFileUri = uri,
                selectedFileName = name,
                selectedMimeType = mimeType,
                selectedFileSize = null,
                selectedWidth = null,
                selectedHeight = null,
                selectedDurationMs = null,
                selectedMode = when {
                    looksLikeGif(name, mimeType) -> ConversionMode.GIF_TO_MP4
                    looksLikeVideo(name, mimeType) -> ConversionMode.VIDEO_TO_GIF
                    else -> null
                },
                gifDestinationUri = null,
                mp4DestinationUri = null,
                framesDestinationUri = null,
                destinationLabel = "尚未选择保存位置",
                status = ConversionStatus.IDLE,
                statusMessage = "已选择本地文件",
                errorMessage = null,
                outputUri = null,
                outputFileSize = null,
            )
        }
        loadSelectedFileMetadata(uri)
    }

    fun setStartSeconds(value: String) = update { copy(startSeconds = value.numericText()) }
    fun setEndSeconds(value: String) = update { copy(endSeconds = value.numericText()) }
    fun setFps(value: Int) = update { copy(fps = value) }
    fun setOutputWidth(value: Int?) = update { copy(outputWidth = value) }
    fun setLoop(value: Boolean) = update { copy(loop = value) }
    fun setGifOutputName(value: String) = update {
        copy(gifOutputName = value, gifDestinationUri = null, destinationLabel = "尚未选择保存位置")
    }
    fun setMp4OutputName(value: String) = update {
        copy(mp4OutputName = value, mp4DestinationUri = null, destinationLabel = "尚未选择保存位置")
    }
    fun setFrameInterval(value: String) = update { copy(frameIntervalSeconds = value.numericText()) }
    fun setFrameFormat(value: FrameFormat) = update { copy(frameFormat = value) }
    fun setMode(value: ConversionMode) = update {
        val supported = when (value) {
            ConversionMode.GIF_TO_MP4 -> looksLikeGif()
            ConversionMode.VIDEO_TO_GIF, ConversionMode.EXTRACT_FRAMES -> looksLikeVideo()
        }
        if (supported && selectedMode != value) {
            copy(
                selectedMode = value,
                gifDestinationUri = null,
                mp4DestinationUri = null,
                framesDestinationUri = null,
                destinationLabel = "尚未选择保存位置",
                errorMessage = null,
            )
        } else if (supported) {
            copy(errorMessage = null)
        } else {
            this
        }
    }

    fun setGifDestination(uri: Uri) = update {
        copy(gifDestinationUri = uri, destinationLabel = displayName(uri) ?: "已选择 GIF 保存位置")
    }

    fun setMp4Destination(uri: Uri) = update {
        copy(mp4DestinationUri = uri, destinationLabel = displayName(uri) ?: "已选择 MP4 保存位置")
    }

    fun setFramesDestination(uri: Uri) = update {
        copy(
            framesDestinationUri = uri,
            destinationLabel = DocumentFile.fromTreeUri(app, uri)?.name ?: "已选择图片输出目录",
        )
    }

    fun prepareGifOutput(): String? {
        val state = _uiState.value
        val start = state.startSeconds.toDoubleOrNull()
        val end = state.endSeconds.toDoubleOrNull()
        return when {
            state.selectedFileUri == null -> invalid("请先选择本地视频文件。")
            !state.looksLikeVideo() -> invalid("所选文件不是受支持的视频，请重新选择。")
            start == null || start < 0 -> invalid("开始时间格式不正确。")
            end == null || end <= start -> invalid("结束时间必须大于开始时间。")
            !validFileName(state.gifOutputName) -> invalid("输出文件名包含非法字符。")
            else -> state.gifOutputName.withExtension("gif")
        }
    }

    fun prepareMp4Output(): String? {
        val state = _uiState.value
        return when {
            state.selectedFileUri == null -> invalid("请先选择本地 GIF 文件。")
            !state.looksLikeGif() -> invalid("所选文件不是 GIF，请重新选择。")
            !validFileName(state.mp4OutputName) -> invalid("输出文件名包含非法字符。")
            else -> state.mp4OutputName.withExtension("mp4")
        }
    }

    fun prepareFramesOutput(): Boolean {
        val state = _uiState.value
        val interval = state.frameIntervalSeconds.toDoubleOrNull()
        return when {
            state.selectedFileUri == null -> invalidBoolean("请先选择本地视频文件。")
            !state.looksLikeVideo() -> invalidBoolean("所选文件不是受支持的视频，请重新选择。")
            interval == null || interval <= 0 -> invalidBoolean("提取间隔必须大于 0 秒。")
            else -> true
        }
    }

    fun startVideoToGif(outputUri: Uri) {
        val state = _uiState.value
        val input = state.selectedFileUri ?: return
        val start = state.startSeconds.toDoubleOrNull() ?: return
        val end = state.endSeconds.toDoubleOrNull() ?: return
        startConversion(
            request = VideoToGifRequest(
                inputUri = input,
                outputUri = outputUri,
                startSeconds = start,
                endSeconds = end,
                fps = state.fps,
                outputWidth = state.outputWidth,
                loop = state.loop,
            ),
            operationType = OperationType.VIDEO_TO_GIF,
        )
    }

    fun startVideoToGif() {
        val destination = _uiState.value.gifDestinationUri
            ?: return invalidUnit("请先选择 GIF 保存位置。")
        if (prepareGifOutput() != null) startVideoToGif(destination)
    }

    fun startGifToMp4(outputUri: Uri) {
        val input = _uiState.value.selectedFileUri ?: return
        startConversion(
            GifToMp4Request(input, outputUri),
            OperationType.GIF_TO_MP4,
        )
    }

    fun startGifToMp4() {
        val destination = _uiState.value.mp4DestinationUri
            ?: return invalidUnit("请先选择 MP4 保存位置。")
        if (prepareMp4Output() != null) startGifToMp4(destination)
    }

    fun startExtractFrames(outputTreeUri: Uri) {
        val state = _uiState.value
        val input = state.selectedFileUri ?: return
        val interval = state.frameIntervalSeconds.toDoubleOrNull() ?: return
        startConversion(
            ExtractFramesRequest(input, outputTreeUri, interval, state.frameFormat),
            OperationType.EXTRACT_FRAMES,
        )
    }

    fun startExtractFrames() {
        val destination = _uiState.value.framesDestinationUri
            ?: return invalidUnit("请先选择图片输出目录。")
        if (prepareFramesOutput()) startExtractFrames(destination)
    }

    fun cancel() {
        if (_uiState.value.status != ConversionStatus.RUNNING) return
        DiagnosticLogger.info("CONVERTER_UI", "cancel_clicked")
        converter.cancel()
        conversionJob?.cancel(CancellationException("用户取消转换"))
        val historyId = activeHistoryId
        _uiState.update {
            it.copy(
                status = ConversionStatus.CANCELED,
                statusMessage = "转换已取消",
                errorMessage = null,
            )
        }
        if (historyId > 0) viewModelScope.launch {
            historyRepository.updateResult(
                historyId,
                OperationStatus.CANCELED,
                errorReason = "用户取消转换。",
            )
        }
    }

    private fun startConversion(
        request: com.example.mediaextractor.domain.converter.ConversionRequest,
        operationType: OperationType,
    ) {
        if (_uiState.value.status == ConversionStatus.RUNNING) return
        DiagnosticLogger.info(
            category = "CONVERTER_UI",
            event = "conversion_requested",
            details = mapOf("type" to operationType.name, "input" to request.inputUri),
        )
        conversionJob = viewModelScope.launch {
            val source = _uiState.value.selectedFileName ?: request.inputUri.toString()
            activeHistoryId = runCatching {
                historyRepository.create(operationType, source, OperationStatus.RUNNING)
            }.getOrDefault(-1L)
            _uiState.update {
                it.copy(
                    status = ConversionStatus.RUNNING,
                    progress = 0,
                    statusMessage = "正在准备转换",
                    errorMessage = null,
                    outputUri = null,
                    outputFileSize = null,
                    generatedFileCount = 0,
                    startedAtMillis = System.currentTimeMillis(),
                )
            }
            converter.convert(request) { progress ->
                _uiState.update {
                    it.copy(progress = progress.percent, statusMessage = progress.message)
                }
            }.onSuccess { result ->
                if (activeHistoryId > 0) runCatching {
                    historyRepository.updateResult(
                        activeHistoryId,
                        OperationStatus.SUCCESS,
                        outputUri = result.outputUri.toString(),
                    )
                }
                _uiState.update {
                    it.copy(
                        status = ConversionStatus.SUCCESS,
                        progress = 100,
                        statusMessage = if (result.generatedFileCount > 1) {
                            "转换完成，共生成 ${result.generatedFileCount} 个文件"
                        } else {
                            "转换完成"
                        },
                        outputUri = result.outputUri.toString(),
                        outputFileSize = querySize(result.outputUri),
                        generatedFileCount = result.generatedFileCount,
                    )
                }
            }.onFailure { failure ->
                Log.e(TAG, "Conversion failed", failure)
                if (activeHistoryId > 0) runCatching {
                    historyRepository.updateResult(
                        activeHistoryId,
                        OperationStatus.FAILED,
                        errorReason = failure.message,
                    )
                }
                _uiState.update {
                    it.copy(
                        status = ConversionStatus.FAILED,
                        statusMessage = "转换失败",
                        errorMessage = failure.message ?: "FFmpeg 执行失败。",
                    )
                }
            }
        }
    }

    private fun update(transform: ConverterUiState.() -> ConverterUiState) {
        if (_uiState.value.status != ConversionStatus.RUNNING) _uiState.update(transform)
    }

    private fun invalid(message: String): String? {
        DiagnosticLogger.warning(
            category = "CONVERTER_UI",
            event = "validation_failed",
            details = mapOf("message" to message),
        )
        _uiState.update { it.copy(errorMessage = message, statusMessage = "参数有误") }
        return null
    }

    private fun invalidBoolean(message: String): Boolean {
        invalid(message)
        return false
    }

    private fun invalidUnit(message: String) {
        invalid(message)
    }

    private fun validFileName(value: String): Boolean = value.isNotBlank() &&
        !value.contains(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")) && value.trim('.', ' ').isNotBlank()

    private fun String.withExtension(extension: String): String =
        if (endsWith(".$extension", ignoreCase = true)) this else "$this.$extension"

    private fun String.numericText(): String = filter { it.isDigit() || it == '.' }.take(8)

    private fun ConverterUiState.looksLikeVideo(): Boolean {
        if (selectedMimeType?.startsWith("video/") == true) return true
        val extension = selectedFileName.orEmpty().substringAfterLast('.', "").lowercase()
        return extension in setOf("mp4", "mkv", "webm", "mov", "m4v", "avi", "3gp", "ts")
    }

    private fun ConverterUiState.looksLikeGif(): Boolean =
        selectedMimeType == "image/gif" || selectedFileName.orEmpty().endsWith(".gif", true)

    private fun looksLikeVideo(name: String, mimeType: String?): Boolean {
        if (mimeType?.startsWith("video/") == true) return true
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in setOf("mp4", "mkv", "webm", "mov", "m4v", "avi", "3gp", "ts")
    }

    private fun looksLikeGif(name: String, mimeType: String?): Boolean =
        mimeType == "image/gif" || name.endsWith(".gif", true)

    private fun loadSelectedFileMetadata(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val size = querySize(uri)
            val metadata = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(app, uri)
                    Triple(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                    )
                } finally {
                    retriever.release()
                }
            }.getOrNull()
            _uiState.update { current ->
                if (current.selectedFileUri != uri) current else current.copy(
                    selectedFileSize = size,
                    selectedWidth = metadata?.first,
                    selectedHeight = metadata?.second,
                    selectedDurationMs = metadata?.third,
                )
            }
        }
    }

    private fun querySize(uri: Uri): Long? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()

    private fun displayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private companion object {
        const val TAG = "ConverterViewModel"
    }
}
