package com.framepick.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.framepick.app.FramePickApplication
import com.framepick.app.util.DiagnosticLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val diagnosticText: String = "",
    val diagnosticsLoading: Boolean = false,
    val diagnosticsError: String? = null,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val diagnostics = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = diagnostics.asStateFlow()

    fun refreshDiagnostics() {
        diagnostics.value = diagnostics.value.copy(loading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(DiagnosticLogger::buildReport)
                .onSuccess { diagnostics.value = SettingsUiState(diagnosticText = it) }
                .onFailure {
                    diagnostics.value = SettingsUiState(error = it.message ?: "无法读取诊断日志。")
                }
        }
    }

    fun clearDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(DiagnosticLogger::clear)
                .onSuccess { diagnostics.value = SettingsUiState(diagnosticText = DiagnosticLogger.buildReport()) }
                .onFailure {
                    diagnostics.value = diagnostics.value.copy(
                        error = it.message ?: "无法清空诊断日志。",
                    )
                }
        }
    }

    suspend fun createDiagnosticReport(): File = withContext(Dispatchers.IO) {
        DiagnosticLogger.createShareReport()
    }
}
