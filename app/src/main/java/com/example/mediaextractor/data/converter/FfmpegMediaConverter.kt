package com.example.mediaextractor.data.converter

import android.content.Context
import android.graphics.Movie
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.example.mediaextractor.domain.converter.ConversionProgress
import com.example.mediaextractor.domain.converter.ConversionRequest
import com.example.mediaextractor.domain.converter.ConversionResult
import com.example.mediaextractor.domain.converter.ExtractFramesRequest
import com.example.mediaextractor.domain.converter.FfmpegCommandBuilder
import com.example.mediaextractor.domain.converter.GifToMp4Request
import com.example.mediaextractor.domain.converter.MediaConverter
import com.example.mediaextractor.domain.converter.VideoToGifRequest
import com.example.mediaextractor.util.DiagnosticLogger
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

class FfmpegMediaConverter(
    private val context: Context,
) : MediaConverter {
    private val currentProcess = AtomicReference<Process?>(null)

    override suspend fun convert(
        request: ConversionRequest,
        onProgress: (ConversionProgress) -> Unit,
    ): Result<ConversionResult> = withContext(Dispatchers.IO) {
        val taskDirectory = File(context.cacheDir, "conversions/${UUID.randomUUID()}")
        DiagnosticLogger.info(
            category = "CONVERSION",
            event = "conversion_started",
            details = mapOf(
                "type" to request::class.java.simpleName,
                "inputUri" to request.inputUri,
                "inputMime" to context.contentResolver.getType(request.inputUri),
                "parameters" to request.diagnosticParameters(),
            ),
        )
        try {
            validate(request)
            ensureCacheSpace()
            if (!taskDirectory.mkdirs()) throw ConversionException("无法创建转换缓存目录。")
            onProgress(ConversionProgress(1, "正在准备本地文件"))
            val input = copyInputToCache(request.inputUri, taskDirectory)
            val durationMs = durationMs(input, request)
            DiagnosticLogger.info(
                category = "CONVERSION",
                event = "input_prepared",
                details = mapOf(
                    "name" to input.name,
                    "bytes" to input.length(),
                    "durationMs" to durationMs,
                ),
            )

            val executable = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
            if (!executable.isFile || executable.length() == 0L) {
                DiagnosticLogger.warning(
                    category = "CONVERSION",
                    event = "native_components_missing",
                    details = mapOf(
                        "executableExists" to executable.isFile,
                        "executableBytes" to executable.length(),
                    ),
                )
                throw ConversionException("FFmpeg 初始化失败，缺少 arm64 本地组件。")
            }
            DiagnosticLogger.info(
                category = "CONVERSION",
                event = "ffmpeg_initialized",
                details = mapOf(
                    "distribution" to "io.github.rbaucells:ffmpeg-android:1.22",
                    "executableBytes" to executable.length(),
                    "nativeLibraryDir" to context.applicationInfo.nativeLibraryDir,
                ),
            )

            val result = when (request) {
                is VideoToGifRequest -> {
                    val output = File(taskDirectory, "output.gif")
                    runFfmpegWithFallback(
                        executable = executable,
                        primaryArguments = FfmpegCommandBuilder.videoToGif(
                            input.absolutePath,
                            output.absolutePath,
                            request.startSeconds,
                            request.endSeconds,
                            request.fps,
                            request.outputWidth,
                            request.loop,
                        ),
                        fallbackArguments = FfmpegCommandBuilder.videoToGifFallback(
                            input.absolutePath,
                            output.absolutePath,
                            request.startSeconds,
                            request.endSeconds,
                            request.fps,
                            request.outputWidth,
                            request.loop,
                        ),
                        output = output,
                        durationMs = ((request.endSeconds - request.startSeconds) * 1_000).toLong(),
                        onProgress = onProgress,
                    )
                    copyOutput(output, request.outputUri)
                    ConversionResult(request.outputUri)
                }
                is GifToMp4Request -> {
                    val output = File(taskDirectory, "output.mp4")
                    runFfmpegWithFallback(
                        executable = executable,
                        primaryArguments = FfmpegCommandBuilder.gifToMp4(input.absolutePath, output.absolutePath),
                        fallbackArguments = FfmpegCommandBuilder.gifToMp4Fallback(
                            input.absolutePath,
                            output.absolutePath,
                        ),
                        output = output,
                        durationMs = durationMs,
                        onProgress = onProgress,
                    )
                    copyOutput(output, request.outputUri)
                    ConversionResult(request.outputUri)
                }
                is ExtractFramesRequest -> {
                    val frames = File(taskDirectory, "frames")
                    if (!frames.mkdirs()) throw ConversionException("无法创建图片缓存目录。")
                    val pattern = File(frames, "frame_%05d.${request.format.extension}")
                    runFfmpeg(
                        executable,
                        FfmpegCommandBuilder.extractFrames(
                            input.absolutePath,
                            pattern.absolutePath,
                            request.intervalSeconds,
                        ),
                        durationMs = durationMs,
                        onProgress = onProgress,
                    )
                    val count = publishFrames(frames, request)
                    ConversionResult(request.outputTreeUri, count)
                }
            }
            onProgress(ConversionProgress(100, "转换完成"))
            DiagnosticLogger.info(
                category = "CONVERSION",
                event = "conversion_succeeded",
                details = mapOf(
                    "type" to request::class.java.simpleName,
                    "outputUri" to result.outputUri,
                    "generatedFiles" to result.generatedFileCount,
                ),
            )
            Result.success(result)
        } catch (canceled: CancellationException) {
            DiagnosticLogger.warning(
                category = "CONVERSION",
                event = "conversion_canceled",
                details = mapOf("type" to request::class.java.simpleName),
                failure = canceled,
            )
            throw canceled
        } catch (failure: Throwable) {
            Log.e(TAG, "FFmpeg conversion failed", failure)
            DiagnosticLogger.error(
                category = "CONVERSION",
                event = "conversion_failed",
                failure = failure,
                details = mapOf(
                    "type" to request::class.java.simpleName,
                    "friendlyMessage" to friendlyConversionError(failure),
                ),
            )
            Result.failure(
                ConversionException(
                    friendlyConversionError(failure),
                    failure,
                ),
            )
        } finally {
            currentProcess.getAndSet(null)?.destroy()
            taskDirectory.deleteRecursively()
        }
    }

    override fun cancel() {
        DiagnosticLogger.info("CONVERSION", "cancel_requested")
        currentProcess.getAndSet(null)?.destroy()
    }

    private suspend fun runFfmpegWithFallback(
        executable: File,
        primaryArguments: List<String>,
        fallbackArguments: List<String>,
        output: File,
        durationMs: Long?,
        onProgress: (ConversionProgress) -> Unit,
    ) {
        try {
            runFfmpeg(
                executable,
                primaryArguments,
                durationMs,
                onProgress,
            )
        } catch (firstFailure: Throwable) {
            coroutineContext.ensureActive()
            output.delete()
            Log.w(TAG, "Primary FFmpeg profile failed; retrying compatibility profile", firstFailure)
            DiagnosticLogger.warning(
                category = "CONVERSION_FFMPEG",
                event = "primary_profile_failed_trying_compatibility",
                failure = firstFailure,
                details = mapOf("primaryArguments" to primaryArguments.joinToString(" ")),
            )
            onProgress(ConversionProgress(3, "首选编码方式失败，正在尝试兼容模式"))
            runFfmpeg(
                executable,
                fallbackArguments,
                durationMs,
                onProgress,
            )
        }
    }

    private suspend fun runFfmpeg(
        executable: File,
        arguments: List<String>,
        durationMs: Long?,
        onProgress: (ConversionProgress) -> Unit,
    ) {
        val command = buildList {
            add(executable.absolutePath)
            addAll(listOf("-y", "-hide_banner", "-loglevel", "error", "-progress", "pipe:1", "-nostats"))
            addAll(arguments)
        }
        DiagnosticLogger.info(
            category = "CONVERSION_FFMPEG",
            event = "command_started",
            details = mapOf("arguments" to arguments.joinToString(" ")),
        )
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        builder.environment()["TMPDIR"] = context.cacheDir.absolutePath
        val process = builder.start()
        if (!currentProcess.compareAndSet(null, process)) {
            process.destroy()
            throw ConversionException("已有转换任务正在运行。")
        }
        val cancellationHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause is CancellationException) process.destroy()
        }
        val errors = ArrayDeque<String>()
        try {
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    coroutineContext.ensureActive()
                    val line = reader.readLine() ?: break
                    val timeUs = when {
                        line.startsWith("out_time_us=") -> line.substringAfter('=').toLongOrNull()
                        line.startsWith("out_time_ms=") -> line.substringAfter('=').toLongOrNull()
                        else -> null
                    }
                    if (timeUs != null && durationMs != null && durationMs > 0) {
                        val percent = ((timeUs / 1_000.0) / durationMs * 96.0)
                            .toInt().coerceIn(2, 97)
                        onProgress(ConversionProgress(percent, "正在本地转换"))
                    } else if (!line.startsWith("progress=") &&
                        !line.startsWith("frame=") &&
                        !line.startsWith("fps=") &&
                        !line.startsWith("bitrate=") &&
                        !line.startsWith("speed=") &&
                        !line.startsWith("total_size=") &&
                        !line.startsWith("dup_frames=") &&
                        !line.startsWith("drop_frames=")
                    ) {
                        if (errors.size >= MAX_FFMPEG_LOG_LINES) errors.removeFirst()
                        errors.addLast(line)
                    }
                }
            }
            val exitCode = process.waitFor()
            DiagnosticLogger.info(
                category = "CONVERSION_FFMPEG",
                event = "command_finished",
                details = mapOf(
                    "exitCode" to exitCode,
                    "output" to errors.joinToString(" || "),
                ),
            )
            if (exitCode != 0) {
                val detail = errors.lastOrNull()?.take(180)
                throw ConversionException(
                    detail?.let { "FFmpeg 执行失败：$it" } ?: "FFmpeg 执行失败（退出码 $exitCode）。",
                )
            }
        } finally {
            cancellationHandle.dispose()
            currentProcess.compareAndSet(process, null)
            process.destroy()
        }
    }

    private fun copyInputToCache(uri: Uri, taskDirectory: File): File {
        val name = queryDisplayName(uri)
        val mimeType = context.contentResolver.getType(uri)
        val nameExtension = name?.substringAfterLast('.', "")
            ?.lowercase()?.takeIf { it.length in 2..6 && it.all(Char::isLetterOrDigit) }
        val mimeExtension = mimeType?.let {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
        }
        val extension = nameExtension ?: mimeExtension
        val output = File(taskDirectory, "input${extension?.let { ".$it" }.orEmpty()}")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ConversionException("无法读取所选文件。")
        input.use { source -> output.outputStream().use(source::copyTo) }
        if (output.length() == 0L) throw ConversionException("所选文件为空或无法读取。")
        DiagnosticLogger.info(
            category = "CONVERSION",
            event = "input_copied_to_cache",
            details = mapOf(
                "displayName" to name,
                "mime" to mimeType,
                "chosenExtension" to extension,
                "bytes" to output.length(),
            ),
        )
        return output
    }

    private fun copyOutput(file: File, outputUri: Uri) {
        if (!file.isFile || file.length() == 0L) throw ConversionException("FFmpeg 没有生成有效输出文件。")
        val output = context.contentResolver.openOutputStream(outputUri, "w")
            ?: throw ConversionException("输出路径不可写。")
        output.use { target -> file.inputStream().use { it.copyTo(target) } }
        DiagnosticLogger.info(
            category = "CONVERSION",
            event = "output_published",
            details = mapOf("bytes" to file.length(), "outputUri" to outputUri),
        )
    }

    private fun publishFrames(directory: File, request: ExtractFramesRequest): Int {
        val tree = DocumentFile.fromTreeUri(context, request.outputTreeUri)
            ?: throw ConversionException("无法访问所选输出目录。")
        if (!tree.canWrite()) throw ConversionException("所选输出目录不可写。")
        val files = directory.listFiles()?.filter(File::isFile)?.sortedBy(File::getName).orEmpty()
        if (files.isEmpty()) throw ConversionException("没有提取到图片，请检查时间间隔和视频内容。")
        files.forEach { source ->
            val target = tree.createFile(request.format.mimeType, source.name)
                ?: throw ConversionException("无法在输出目录创建 ${source.name}。")
            val output = context.contentResolver.openOutputStream(target.uri, "w")
                ?: throw ConversionException("输出路径不可写。")
            output.use { stream -> source.inputStream().use { it.copyTo(stream) } }
        }
        return files.size
    }

    private fun durationMs(input: File, request: ConversionRequest): Long? {
        if (request is VideoToGifRequest) {
            return ((request.endSeconds - request.startSeconds) * 1_000).toLong()
        }
        if (request is GifToMp4Request) {
            return runCatching {
                Movie.decodeFile(input.absolutePath)?.duration()?.toLong()?.takeIf { it > 0 }
            }.getOrNull()
        }
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(input.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }

    private fun validate(request: ConversionRequest) {
        when (request) {
            is VideoToGifRequest -> {
                if (request.startSeconds < 0 || request.endSeconds <= request.startSeconds) {
                    throw ConversionException("结束时间必须大于开始时间。")
                }
                if (request.fps !in setOf(5, 10, 12, 15, 20)) {
                    throw ConversionException("请选择有效帧率。")
                }
            }
            is ExtractFramesRequest -> if (request.intervalSeconds <= 0) {
                throw ConversionException("图片提取间隔必须大于 0 秒。")
            }
            is GifToMp4Request -> Unit
        }
    }

    private fun ensureCacheSpace() {
        val available = StatFs(context.cacheDir.absolutePath).availableBytes
        DiagnosticLogger.info(
            category = "STORAGE",
            event = "conversion_cache_space_checked",
            details = mapOf("availableBytes" to available, "requiredBytes" to MIN_CACHE_BYTES),
        )
        if (available < MIN_CACHE_BYTES) {
            throw ConversionException("手机剩余空间不足，至少需要 100 MB 可用空间。")
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    private fun friendlyConversionError(failure: Throwable): String {
        val detail = failure.message.orEmpty()
        val lowered = detail.lowercase()
        return when {
            "invalid data" in lowered || "could not find codec" in lowered ->
                "所选文件已损坏，或其编码格式不受当前 FFmpeg 支持。"
            "unknown encoder" in lowered ->
                "当前 FFmpeg 缺少所需编码器，兼容模式也未能完成转换。"
            "no space" in lowered || "enospc" in lowered ->
                "手机剩余空间不足，无法完成转换。"
            "permission denied" in lowered || "read-only" in lowered ->
                "无法读写所选文件或输出位置，请重新选择保存位置。"
            detail.isNotBlank() -> detail.take(220)
            else -> "FFmpeg 执行失败。"
        }
    }

    private fun ConversionRequest.diagnosticParameters(): String = when (this) {
        is VideoToGifRequest ->
            "start=$startSeconds,end=$endSeconds,fps=$fps,width=$outputWidth,loop=$loop"
        is GifToMp4Request -> "gif-to-mp4"
        is ExtractFramesRequest -> "interval=$intervalSeconds,format=${format.name}"
    }

    private class ConversionException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private companion object {
        const val TAG = "FfmpegMediaConverter"
        const val MIN_CACHE_BYTES = 100L * 1024L * 1024L
        const val MAX_FFMPEG_LOG_LINES = 40
    }
}
