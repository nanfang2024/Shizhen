package com.framepick.app.ui.downloads

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.framepick.app.FramePickApplication
import com.framepick.app.data.download.DownloadTask
import com.framepick.app.data.repository.HistoryRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadsUiState(
    val tasks: List<DownloadTask> = emptyList(),
    val records: List<HistoryRecord> = emptyList(),
)

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {
    private val downloadRepository = getApplication<FramePickApplication>().downloadRepository
    private val historyRepository = getApplication<FramePickApplication>().historyRepository
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            downloadRepository.observeDownloads().collect { tasks ->
                val byItem = tasks.groupBy(DownloadTask::mediaItemId).mapValues { (_, entries) ->
                    entries.firstOrNull { !it.state.isFinished } ?: entries.last()
                }
                // Active tasks first (stable order), finished ones keep working set on top.
                val active = byItem.values.filter { !it.state.isFinished }
                val finished = byItem.values.filter { it.state.isFinished }
                _uiState.update { it.copy(tasks = active + finished) }
            }
        }
        viewModelScope.launch {
            historyRepository.observeAll().collect { records ->
                _uiState.update { it.copy(records = records) }
            }
        }
    }

    fun cancel(task: DownloadTask) {
        if (task.state != WorkInfo.State.RUNNING &&
            task.state != WorkInfo.State.ENQUEUED &&
            task.state != WorkInfo.State.BLOCKED
        ) return
        downloadRepository.cancel(task.workId)
    }

    fun delete(id: Long) {
        viewModelScope.launch { historyRepository.delete(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { historyRepository.clearAll() }
    }
}
