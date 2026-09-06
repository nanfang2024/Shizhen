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
                // Only in-flight work is shown as a task; terminal outcomes
                // (succeeded/failed/cancelled) are already recorded in task
                // history, where they can be deleted or cleared.
                val active = tasks
                    .filter { !it.state.isFinished }
                    .groupBy(DownloadTask::mediaItemId)
                    .map { (_, entries) -> entries.first() }
                _uiState.update { it.copy(tasks = active) }
            }
        }
        viewModelScope.launch {
            historyRepository.observeAll().collect { records ->
                _uiState.update { it.copy(records = records) }
            }
        }
        // Terminal work records are invisible on this screen; drop them from
        // WorkManager's database so it does not grow unboundedly.
        downloadRepository.pruneFinished()
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
