package com.framepick.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

object FileIntentUtils {
    fun open(context: Context, uriValue: String) {
        val uri = uriValue.toUri()
        val type = context.contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(context, intent, "没有找到可以打开该文件的应用。")
    }

    fun share(context: Context, uriValue: String) {
        val uri = uriValue.toUri()
        val type = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val intent = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(
            context,
            Intent.createChooser(intent, "分享媒体文件"),
            "没有找到可以分享该文件的应用。",
        )
    }

    fun shareDiagnosticReport(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "拾帧诊断报告")
            clipData = ClipData.newRawUri("diagnostic_report", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launch(
            context,
            Intent.createChooser(intent, "分享诊断报告"),
            "没有找到可以分享诊断报告的应用。",
        )
    }

    private fun launch(context: Context, intent: Intent, errorMessage: String) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, "文件已不存在或应用无权读取。", Toast.LENGTH_SHORT).show()
        }
    }
}
