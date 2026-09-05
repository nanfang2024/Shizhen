package com.framepick.app.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.framepick.app.FramePickApplication
import com.framepick.app.data.repository.HistoryRecord
import com.framepick.app.util.DiagnosticLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HistoryUiState(
    val records: List<HistoryRecord> = emptyList(),
    val diagnosticText: String = "",
    val diagnosticsLoading: Boolean = false,
    val diagnosticsError: String? = null,
)

private data class DiagnosticsState(
    val text: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = getApplication<FramePickApplication>().historyRepository
    private val diagnostics = MutableStateFlow(DiagnosticsState())

    val uiState: StateFlow<HistoryUiState> = combine(
        repository.observeAll(),
        diagnostics,
    ) { records, diagnosticState ->
        HistoryUiState(
            records = records,
            diagnosticText = diagnosticState.text,
            diagnosticsLoading = diagnosticState.loading,
            diagnosticsError = diagnosticState.error,
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HistoryUiState(),
        )

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun clearAll() {
        viewModelScope.launch { repository.clearAll() }
    }

    fun refreshDiagnostics() {
        diagnostics.value = diagnostics.value.copy(loading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(DiagnosticLogger::buildReport)
                .onSuccess { diagnostics.value = DiagnosticsState(text = it) }
                .onFailure {
                    diagnostics.value = DiagnosticsState(error = it.message ?: "无法读取诊断日志。")
                }
        }
    }

    fun clearDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(DiagnosticLogger::clear)
                .onSuccess { diagnostics.value = DiagnosticsState(text = DiagnosticLogger.buildReport()) }
                .onFailure {
                    diagnostics.value = DiagnosticsState(error = it.message ?: "无法清空诊断日志。")
                }
        }
    }

    suspend fun createDiagnosticReport(): File = withContext(Dispatchers.IO) {
        DiagnosticLogger.createShareReport()
    }
}
