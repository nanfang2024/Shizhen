package com.framepick.app.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun refreshDiagnostics() {
        _uiState.value = _uiState.value.copy(diagnosticsLoading = true, diagnosticsError = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(DiagnosticLogger::buildReport)
                .onSuccess { _uiState.value = SettingsUiState(diagnosticText = it) }
                .onFailure {
                    _uiState.value = SettingsUiState(
                        diagnosticsError = it.message ?: "无法读取诊断日志。",
                    )
                }
        }
    }

    fun clearDiagnostics() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(DiagnosticLogger::clear)
                .onSuccess {
                    _uiState.value = SettingsUiState(diagnosticText = DiagnosticLogger.buildReport())
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        diagnosticsError = it.message ?: "无法清空诊断日志。",
                    )
                }
        }
    }

    suspend fun createDiagnosticReport(): File = withContext(Dispatchers.IO) {
        DiagnosticLogger.createShareReport()
    }
}
