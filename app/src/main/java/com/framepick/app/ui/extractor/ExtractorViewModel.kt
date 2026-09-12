package com.framepick.app.ui.extractor

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.framepick.app.FramePickApplication
import com.framepick.app.data.download.DownloadTask
import com.framepick.app.data.repository.OperationStatus
import com.framepick.app.data.repository.OperationType
import com.framepick.app.domain.model.MediaItem
import com.framepick.app.domain.model.ParsedMedia
import com.framepick.app.domain.parser.ParserMessages
import com.framepick.app.util.DiagnosticLogger
import com.framepick.app.util.PlatformRecognizer
import com.framepick.app.util.UrlExtractor
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExtractorUiState(
    val inputText: String = "",
    val recognizedUrl: String? = null,
    val platform: String? = null,
    val domain: String? = null,
    val status: String = "等待输入",
    val errorMessage: String? = null,
    val isParsing: Boolean = false,
    val parsedMedia: ParsedMedia? = null,
    val downloads: Map<String, DownloadTask> = emptyMap(),
)

class ExtractorViewModel(application: Application) : AndroidViewModel(application) {
    private val parserRegistry = getApplication<FramePickApplication>().parserRegistry
    private val downloadRepository = getApplication<FramePickApplication>().downloadRepository
    private val historyRepository = getApplication<FramePickApplication>().historyRepository
    private val _uiState = MutableStateFlow(ExtractorUiState())
    val uiState: StateFlow<ExtractorUiState> = _uiState.asStateFlow()
    private var parseJob: Job? = null

    init {
        viewModelScope.launch {
            downloadRepository.observeDownloads().collect { tasks ->
                val byItem = tasks.groupBy(DownloadTask::mediaItemId).mapValues { (_, entries) ->
                    entries.firstOrNull { !it.state.isFinished } ?: entries.last()
                }
                _uiState.update { it.copy(downloads = byItem) }
            }
        }
    }

    fun onInputChanged(value: String) {
        updateFromText(value)
    }

    fun acceptSharedText(value: String) {
        updateFromText(value)
    }

    fun parseLink() {
        val current = _uiState.value
        if (current.isParsing) return
        DiagnosticLogger.info(
            category = "EXTRACTOR_UI",
            event = "parse_requested",
            details = mapOf("inputLength" to current.inputText.length),
        )
        if (current.inputText.isBlank()) {
            DiagnosticLogger.warning("EXTRACTOR_UI", "empty_input")
            _uiState.update {
                it.copy(status = "输入为空", errorMessage = "请先粘贴或输入分享文字。")
            }
            return
        }
        val url = UrlExtractor.extractFirst(current.inputText)
        if (url == null) {
            DiagnosticLogger.warning("EXTRACTOR_UI", "no_valid_url_in_text")
            _uiState.update {
                it.copy(
                    recognizedUrl = null,
                    platform = null,
                    domain = null,
                    status = "未找到链接",
                    errorMessage = "没有在分享文字中找到有效链接。",
                    parsedMedia = null,
                )
            }
            return
        }

        val platform = PlatformRecognizer.recognize(url)
        DiagnosticLogger.info(
            category = "EXTRACTOR_UI",
            event = "url_recognized",
            details = mapOf("url" to url, "platform" to platform?.displayName),
        )
        _uiState.update {
            it.copy(
                recognizedUrl = url,
                platform = platform?.displayName,
                domain = platform?.domain,
                status = "正在解析公开媒体…",
                errorMessage = null,
                isParsing = true,
                parsedMedia = null,
            )
        }
        parseJob?.cancel()
        parseJob = viewModelScope.launch {
            val historyId = runCatching {
                historyRepository.create(
                    OperationType.PARSE,
                    url,
                    OperationStatus.RUNNING,
                )
            }.getOrDefault(-1L)
            parserRegistry.parse(url)
                .onSuccess { parsed ->
                    if (historyId > 0) runCatching {
                        historyRepository.updateResult(
                            historyId,
                            OperationStatus.SUCCESS,
                            outputUri = parsed.sourceUrl,
                        )
                    }
                    _uiState.update {
                        it.copy(
                            status = "解析成功，共找到 ${parsed.items.size} 个资源",
                            isParsing = false,
                            parsedMedia = parsed,
                            platform = parsed.platform,
                            errorMessage = null,
                        )
                    }
                }
                .onFailure { failure ->
                    Log.e(TAG, "Media parsing failed for $url", failure)
                    DiagnosticLogger.error(
                        category = "EXTRACTOR_UI",
                        event = "parse_result_failed",
                        failure = failure,
                        details = mapOf("url" to url),
                    )
                    if (historyId > 0) runCatching {
                        historyRepository.updateResult(
                            historyId,
                            OperationStatus.FAILED,
                            errorReason = failure.message,
                        )
                    }
                    _uiState.update {
                        it.copy(
                            status = "解析失败",
                            isParsing = false,
                            parsedMedia = null,
                            errorMessage = failure.message?.takeIf(String::isNotBlank)
                                ?: ParserMessages.NO_MEDIA,
                        )
                    }
                }
        }
    }

    fun download(item: MediaItem, index: Int = 0, total: Int = 1) {
        val media = _uiState.value.parsedMedia ?: return
        DiagnosticLogger.info(
            category = "EXTRACTOR_UI",
            event = "download_clicked",
            details = mapOf(
                "itemId" to item.id,
                "type" to item.type,
                "quality" to item.qualityLabel,
                "format" to item.format,
            ),
        )
        viewModelScope.launch {
            runCatching {
                val nameTag = if (total > 1) String.format(Locale.US, "%02d", index + 1) else null
                downloadRepository.enqueue(item, media.title, nameTag)
            }
                .onFailure { failure ->
                    Log.e(TAG, "Unable to enqueue download", failure)
                    _uiState.update {
                        it.copy(errorMessage = "无法创建下载任务，请稍后重试。")
                    }
                }
        }
    }

    fun clear() {
        parseJob?.cancel()
        _uiState.value = ExtractorUiState(downloads = _uiState.value.downloads)
    }

    private fun updateFromText(value: String) {
        parseJob?.cancel()
        val url = UrlExtractor.extractFirst(value)
        val platform = url?.let(PlatformRecognizer::recognize)
        _uiState.value = ExtractorUiState(
            inputText = value,
            recognizedUrl = url,
            platform = platform?.displayName,
            domain = platform?.domain,
            status = when {
                value.isBlank() -> "等待输入"
                url == null -> "未找到有效链接"
                else -> "已识别链接，等待用户确认解析"
            },
            errorMessage = if (value.isNotBlank() && url == null) {
                "没有在分享文字中找到有效链接。"
            } else {
                null
            },
            downloads = _uiState.value.downloads,
        )
    }

    private companion object {
        const val TAG = "ExtractorViewModel"
    }
}
