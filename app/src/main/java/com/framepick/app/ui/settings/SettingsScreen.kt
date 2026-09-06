@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.framepick.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Transform
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.framepick.app.BuildConfig
import com.framepick.app.R
import com.framepick.app.ui.components.AppPageHeader
import com.framepick.app.ui.components.DisclaimerDialog
import com.framepick.app.ui.components.HeaderBrandMark
import com.framepick.app.ui.components.SectionHeader
import com.framepick.app.ui.components.WarmSurfaceCard
import com.framepick.app.ui.converter.ConverterScreen
import com.framepick.app.ui.converter.ConverterViewModel
import com.framepick.app.util.FileIntentUtils
import kotlinx.coroutines.launch

private const val FEEDBACK_EMAIL = "xnzyw6@gmail.com"

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    converterViewModel: ConverterViewModel,
    modifier: Modifier = Modifier,
) {
    var showConverter by rememberSaveable { mutableStateOf(false) }

    if (showConverter) {
        BackHandler { showConverter = false }
        Column(modifier = modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { showConverter = false }) {
                    Icon(
                        Icons.Outlined.ChevronLeft,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(stringResource(R.string.settings_title))
                }
            }
            ConverterScreen(converterViewModel, Modifier.fillMaxSize())
        }
        return
    }

    SettingsContent(
        viewModel = viewModel,
        modifier = modifier,
        onOpenConverter = { showConverter = true },
    )
}

@Composable
private fun SettingsContent(
    viewModel: SettingsViewModel,
    modifier: Modifier,
    onOpenConverter: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showDisclaimer by rememberSaveable { mutableStateOf(false) }

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
                title = stringResource(R.string.settings_title),
                subtitle = stringResource(R.string.settings_subtitle),
                brandMark = HeaderBrandMark.MAPLE,
            )
            WarmSurfaceCard {
                SectionHeader(title = stringResource(R.string.settings_tools_title))
                SettingsEntryRow(
                    icon = Icons.Outlined.Transform,
                    title = stringResource(R.string.settings_converter_entry),
                    summary = stringResource(R.string.settings_converter_summary),
                    onClick = onOpenConverter,
                )
            }
            WarmSurfaceCard {
                SectionHeader(title = stringResource(R.string.settings_compliance_title))
                SettingsEntryRow(
                    icon = Icons.Outlined.Description,
                    title = stringResource(R.string.settings_disclaimer_entry),
                    summary = stringResource(R.string.rights_notice),
                    onClick = { showDisclaimer = true },
                )
            }
            WarmSurfaceCard {
                SectionHeader(title = stringResource(R.string.settings_diagnostics_title))
                SettingsEntryRow(
                    icon = Icons.Outlined.BugReport,
                    title = stringResource(R.string.settings_diagnostics_entry),
                    summary = stringResource(R.string.settings_diagnostics_summary),
                    onClick = {
                        showDiagnostics = true
                        viewModel.refreshDiagnostics()
                    },
                )
            }
            WarmSurfaceCard {
                SectionHeader(title = stringResource(R.string.settings_about_title))
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.brand_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboard.setText(AnnotatedString(FEEDBACK_EMAIL))
                            Toast.makeText(context, "已复制反馈邮箱", Toast.LENGTH_SHORT).show()
                        }
                        .padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Email,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("软件反馈", style = MaterialTheme.typography.titleSmall)
                        Text(
                            FEEDBACK_EMAIL,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "复制邮箱",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "解析、下载与格式转换全部在本机完成，不上传任何链接或文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDisclaimer) {
        DisclaimerDialog(onAccept = {}, onDismiss = { showDisclaimer = false })
    }
    if (showDiagnostics) {
        DiagnosticsDialog(
            state = state,
            onDismiss = { showDiagnostics = false },
            onCopy = {
                clipboard.setText(AnnotatedString(state.diagnosticText))
                Toast.makeText(context, "诊断报告已复制", Toast.LENGTH_SHORT).show()
            },
            onShare = {
                scope.launch {
                    runCatching { viewModel.createDiagnosticReport() }
                        .onSuccess { FileIntentUtils.shareDiagnosticReport(context, it) }
                        .onFailure {
                            Toast.makeText(context, "无法生成诊断报告", Toast.LENGTH_SHORT).show()
                        }
                }
            },
            onRefresh = viewModel::refreshDiagnostics,
            onClear = viewModel::clearDiagnostics,
        )
    }
}

@Composable
private fun SettingsEntryRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DiagnosticsDialog(
    state: SettingsUiState,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_diagnostics_entry)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "请先复现一次问题，再返回这里分享报告。报告会遮盖 Cookie、Token、授权头、URL 查询参数和文件选择器 URI。",
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        enabled = state.diagnosticText.isNotBlank(),
                        onClick = onCopy,
                    ) { Text("复制") }
                    TextButton(
                        enabled = !state.diagnosticsLoading,
                        onClick = onShare,
                    ) { Text("分享") }
                    TextButton(onClick = onRefresh) { Text("刷新") }
                    TextButton(onClick = onClear) { Text("清空日志") }
                }
                when {
                    state.diagnosticsLoading -> CircularProgressIndicator()
                    state.diagnosticsError != null -> Text(
                        state.diagnosticsError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> SelectionContainer {
                        Text(
                            text = state.diagnosticText.ifBlank { "暂无诊断记录。" },
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.disclaimer_close)) }
        },
    )
}
