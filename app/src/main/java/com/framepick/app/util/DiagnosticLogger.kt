package com.framepick.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object DiagnosticLogger {
    private val lock = Any()
    private val sessionId = UUID.randomUUID().toString().take(8)
    @Volatile private var applicationContext: Context? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
        info(
            category = "APP",
            event = "session_started",
            details = mapOf(
                "session" to sessionId,
                "sdk" to Build.VERSION.SDK_INT,
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "abis" to Build.SUPPORTED_ABIS.joinToString(),
            ),
        )
    }

    fun info(category: String, event: String, details: Map<String, Any?> = emptyMap()) =
        write("INFO", category, event, details, null)

    fun warning(
        category: String,
        event: String,
        details: Map<String, Any?> = emptyMap(),
        failure: Throwable? = null,
    ) = write("WARN", category, event, details, failure)

    fun error(
        category: String,
        event: String,
        failure: Throwable,
        details: Map<String, Any?> = emptyMap(),
    ) = write("ERROR", category, event, details, failure)

    fun buildReport(): String = synchronized(lock) {
        val context = applicationContext ?: return@synchronized "诊断系统尚未初始化。"
        buildString {
            appendLine("拾帧诊断报告")
            appendLine("生成时间: ${timestamp()}")
            appendLine("隐私说明: Cookie、Token、授权头、URL 查询参数及 content URI 已自动遮盖。")
            appendLine()
            appendLine("[运行环境]")
            environmentLines(context).forEach(::appendLine)
            appendLine()
            appendLine("[事件日志：旧 -> 新]")
            val backup = backupFile(context)
            val current = logFile(context)
            if (!backup.isFile && !current.isFile) {
                appendLine("暂无诊断事件。")
            } else {
                if (backup.isFile) append(backup.readText(Charsets.UTF_8))
                if (current.isFile) append(current.readText(Charsets.UTF_8))
            }
        }
    }

    fun createShareReport(): File = synchronized(lock) {
        val context = requireNotNull(applicationContext) { "诊断系统尚未初始化。" }
        val directory = File(context.cacheDir, "diagnostics/reports").apply { mkdirs() }
        directory.listFiles()?.sortedByDescending(File::lastModified)?.drop(MAX_REPORT_FILES - 1)
            ?.forEach(File::delete)
        val file = File(directory, "media_extractor_diagnostics_${fileTimestamp()}.txt")
        file.writeText(buildReport(), Charsets.UTF_8)
        file
    }

    fun clear() = synchronized(lock) {
        val context = applicationContext ?: return@synchronized
        logFile(context).delete()
        backupFile(context).delete()
    }

    private fun write(
        level: String,
        category: String,
        event: String,
        details: Map<String, Any?>,
        failure: Throwable?,
    ) {
        val context = applicationContext ?: return
        val sanitizedDetails = details.entries.joinToString(" | ") { (key, value) ->
            "$key=${DiagnosticSanitizer.sanitize(value?.toString().orEmpty()).take(MAX_VALUE_LENGTH)}"
        }
        val exceptionText = failure?.let(::exceptionSummary)
        val line = buildString {
            append(timestamp())
            append(" | ").append(level)
            append(" | ").append(category.take(32))
            append(" | ").append(event.take(80))
            if (sanitizedDetails.isNotBlank()) append(" | ").append(sanitizedDetails)
            if (!exceptionText.isNullOrBlank()) append(" | error=").append(exceptionText)
            appendLine()
        }
        synchronized(lock) {
            runCatching {
                val target = logFile(context)
                target.parentFile?.mkdirs()
                if (target.length() + line.toByteArray(Charsets.UTF_8).size > MAX_LOG_BYTES) {
                    backupFile(context).delete()
                    target.renameTo(backupFile(context))
                }
                target.appendText(line, Charsets.UTF_8)
            }.onFailure { Log.e(TAG, "Unable to persist diagnostic log", it) }
        }
        when (level) {
            "ERROR" -> Log.e(TAG, "$category/$event: $sanitizedDetails", failure)
            "WARN" -> Log.w(TAG, "$category/$event: $sanitizedDetails", failure)
            else -> Log.i(TAG, "$category/$event: $sanitizedDetails")
        }
    }

    private fun exceptionSummary(failure: Throwable): String {
        val causes = generateSequence(failure) { it.cause }.take(3).joinToString(" <- ") {
            "${it::class.java.simpleName}: ${it.message.orEmpty()}"
        }
        val frames = failure.stackTrace
            .filter { it.className.startsWith("com.framepick.app") }
            .take(6)
            .joinToString(" <- ") { "${it.className.substringAfterLast('.')}:${it.lineNumber}" }
        return DiagnosticSanitizer.sanitize(
            if (frames.isBlank()) causes else "$causes @ $frames",
        ).take(MAX_EXCEPTION_LENGTH)
    }

    private fun environmentLines(context: Context): List<String> {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        val transports = buildList {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("蜂窝网络")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("以太网")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
        }.ifEmpty { listOf("无活动网络") }
        val internalFree = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrNull()
        val cacheFree = runCatching { StatFs(context.cacheDir.absolutePath).availableBytes }.getOrNull()
        return listOf(
            "session=$sessionId",
            "app=${packageInfo?.versionName ?: "unknown"} (${packageInfo.versionCodeCompat()})",
            "android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "device=${Build.MANUFACTURER} ${Build.MODEL}",
            "abis=${Build.SUPPORTED_ABIS.joinToString()}",
            "network=${transports.joinToString()}，validated=${capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true}",
            "internalFree=${internalFree?.formatBytes() ?: "unknown"}",
            "cacheFree=${cacheFree?.formatBytes() ?: "unknown"}",
        )
    }

    private fun Long.formatBytes(): String = "%.1f MiB".format(Locale.US, this / 1024.0 / 1024.0)
    @Suppress("DEPRECATION")
    private fun android.content.pm.PackageInfo?.versionCodeCompat(): Long = when {
        this == null -> 0L
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> longVersionCode
        else -> versionCode.toLong()
    }
    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    private fun fileTimestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private fun logFile(context: Context) = File(context.filesDir, "diagnostics/events.log")
    private fun backupFile(context: Context) = File(context.filesDir, "diagnostics/events.previous.log")

    private const val TAG = "MediaDiagnostics"
    private const val MAX_LOG_BYTES = 512L * 1024L
    private const val MAX_REPORT_FILES = 3
    private const val MAX_VALUE_LENGTH = 1_600
    private const val MAX_EXCEPTION_LENGTH = 4_000
}
