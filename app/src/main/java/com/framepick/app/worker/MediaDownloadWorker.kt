package com.framepick.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.framepick.app.MainActivity
import com.framepick.app.FramePickApplication
import com.framepick.app.R
import com.framepick.app.data.repository.OperationStatus
import com.framepick.app.domain.model.DownloadStrategy
import com.framepick.app.domain.model.MediaType
import com.framepick.app.util.DiagnosticLogger
import com.framepick.app.util.InternationalPlatformFailurePolicy
import com.framepick.app.util.NeteaseAudioUnavailableException
import com.framepick.app.util.NeteasePublicAudioResolver
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import okhttp3.Request

class MediaDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val app = appContext as FramePickApplication
    private val historyRepository = app.historyRepository
    private var createdOutput: OutputDestination? = null

    override suspend fun doWork(): Result {
        val mediaUrl = inputData.getString(KEY_MEDIA_URL)
            ?: run {
                DiagnosticLogger.warning("DOWNLOAD", "missing_media_url")
                return failure("下载地址无效。")
            }
        val sourceUrl = inputData.getString(KEY_SOURCE_URL) ?: mediaUrl
        val backupUrl = inputData.getString(KEY_BACKUP_URL)
        val historyId = inputData.getLong(KEY_HISTORY_ID, -1L)
        val itemId = inputData.getString(KEY_ITEM_ID).orEmpty()
        val format = inputData.getString(KEY_FORMAT)
        val mediaType = inputData.getString(KEY_MEDIA_TYPE).orEmpty()
        val title = inputData.getString(KEY_TITLE)
        val strategy = runCatching {
            DownloadStrategy.valueOf(
                inputData.getString(KEY_DOWNLOAD_STRATEGY) ?: DownloadStrategy.DIRECT.name,
            )
        }.getOrDefault(DownloadStrategy.DIRECT)

        DiagnosticLogger.info(
            category = "DOWNLOAD",
            event = "worker_started",
            details = mapOf(
                "workId" to id,
                "attempt" to runAttemptCount,
                "strategy" to strategy.name,
                "mediaType" to mediaType,
                "format" to format,
                "selector" to inputData.getString(KEY_FORMAT_SELECTOR),
                "sourceUrl" to sourceUrl,
                "mediaUrl" to mediaUrl,
            ),
        )

        setForeground(createForegroundInfo(0, "等待下载"))
        historyRepository.updateResult(historyId, OperationStatus.RUNNING)

        return try {
            val destination = when (strategy) {
                DownloadStrategy.DIRECT -> downloadDirect(
                    url = resolveDirectDownloadUrl(mediaUrl, sourceUrl),
                    sourceUrl = sourceUrl,
                    format = format,
                    mediaType = mediaType,
                    title = title,
                    itemId = itemId,
                    backupUrl = backupUrl,
                )
                DownloadStrategy.DIRECT_AUDIO -> downloadDirectAudio(
                    url = mediaUrl,
                    sourceUrl = sourceUrl,
                    title = title,
                    itemId = itemId,
                )
                DownloadStrategy.YT_DLP -> downloadYtDlpItem(
                    mediaUrl = mediaUrl,
                    sourceUrl = sourceUrl,
                    format = format,
                    formatSelector = inputData.getString(KEY_FORMAT_SELECTOR),
                    mediaType = mediaType,
                    title = title,
                    itemId = itemId,
                )
                DownloadStrategy.YT_DLP_AUDIO -> downloadWithYtDlp(
                    sourceUrl = sourceUrl,
                    formatSelector = inputData.getString(KEY_FORMAT_SELECTOR),
                    mediaType = MediaType.AUDIO.name,
                    title = title,
                    itemId = itemId,
                    audioOnly = true,
                )
            }
            destination.publish()
            setProgress(workDataOf(KEY_PROGRESS to 100))
            setForeground(createForegroundInfo(100, "下载完成"))
            historyRepository.updateResult(
                historyId,
                OperationStatus.SUCCESS,
                outputUri = destination.uri.toString(),
            )
            DiagnosticLogger.info(
                category = "DOWNLOAD",
                event = "worker_succeeded",
                details = mapOf("workId" to id, "output" to destination.uri),
            )
            Result.success(
                workDataOf(
                    KEY_OUTPUT_URI to destination.uri.toString(),
                    KEY_PROGRESS to 100,
                ),
            )
        } catch (canceled: CancellationException) {
            createdOutput?.delete()
            DiagnosticLogger.warning(
                category = "DOWNLOAD",
                event = "worker_canceled",
                details = mapOf("workId" to id),
                failure = canceled,
            )
            historyRepository.updateResult(
                historyId,
                OperationStatus.CANCELED,
                errorReason = "下载已取消。",
            )
            throw canceled
        } catch (cause: Throwable) {
            createdOutput?.delete()
            val message = friendlyError(cause, sourceUrl)
            if (isRetryable(cause) && runAttemptCount < 2) {
                DiagnosticLogger.warning(
                    category = "DOWNLOAD",
                    event = "worker_retry_scheduled",
                    details = mapOf("workId" to id, "attempt" to runAttemptCount, "message" to message),
                    failure = cause,
                )
                historyRepository.updateResult(
                    historyId,
                    OperationStatus.WAITING,
                    errorReason = message,
                )
                Result.retry()
            } else {
                DiagnosticLogger.error(
                    category = "DOWNLOAD",
                    event = "worker_failed",
                    failure = cause,
                    details = mapOf("workId" to id, "attempt" to runAttemptCount, "message" to message),
                )
                historyRepository.updateResult(
                    historyId,
                    OperationStatus.FAILED,
                    errorReason = message,
                )
                failure(message)
            }
        }
    }

    private fun resolveDirectDownloadUrl(mediaUrl: String, sourceUrl: String): String {
        if (!NeteasePublicAudioResolver.isOuterUrl(sourceUrl)) return mediaUrl
        val resolved = try {
            NeteasePublicAudioResolver.resolveHttpsCdnUrl(app.httpClient, sourceUrl)
        } catch (failure: NeteaseAudioUnavailableException) {
            throw DownloadException("该资源需要登录或存在访问限制，拾帧不支持提取。")
        }
        DiagnosticLogger.info(
            category = "DOWNLOAD_DIRECT",
            event = "netease_audio_url_refreshed",
            details = mapOf("sourceUrl" to sourceUrl, "resolvedUrl" to resolved),
        )
        return resolved
    }

    private suspend fun downloadDirect(
        url: String,
        sourceUrl: String? = null,
        format: String?,
        mediaType: String,
        title: String?,
        itemId: String,
        backupUrl: String? = null,
    ): OutputDestination {
        return try {
            performDirectTransfer(url, sourceUrl, format, mediaType, title, itemId)
        } catch (failure: DirectForbiddenException) {
            if (backupUrl.isNullOrBlank() || backupUrl == url) throw accessRestricted()
            DiagnosticLogger.warning(
                category = "DOWNLOAD_DIRECT",
                event = "primary_forbidden_switching_backup",
                failure = failure,
                details = mapOf("backupUrl" to backupUrl),
            )
            performDirectTransfer(backupUrl, sourceUrl, format, mediaType, title, itemId)
        }
    }

    private suspend fun performDirectTransfer(
        url: String,
        sourceUrl: String?,
        format: String?,
        mediaType: String,
        title: String?,
        itemId: String,
    ): OutputDestination {
        val request = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_USER_AGENT)
                .apply {
                    val referer = sourceUrl?.takeIf { source ->
                        runCatching {
                            val uri = URI(source)
                            (uri.scheme == "https" || uri.scheme == "http") &&
                                !uri.host.isNullOrBlank()
                        }.getOrDefault(false)
                    }
                    referer?.let { header("Referer", it) }
                }
                .get()
                .build()
        val response = app.downloadHttpClient.newCall(request).execute()

        response.use {
            DiagnosticLogger.info(
                category = "DOWNLOAD_DIRECT",
                event = "http_response",
                details = mapOf(
                    "status" to it.code,
                    "contentType" to it.body?.contentType(),
                    "contentLength" to it.body?.contentLength(),
                    "finalUrl" to it.request.url,
                ),
            )
            if (it.code == 401 || it.code == 403) throw DirectForbiddenException()
            if (!it.isSuccessful) {
                throw DownloadException("下载失败，服务器返回状态 ${it.code}。")
            }
            val body = it.body ?: throw DownloadException("服务器没有返回文件内容。")
            validateResponseMime(mediaType, body.contentType()?.toString())
            val contentLength = body.contentLength().takeIf { size -> size > 0 }
                ?: inputData.getLong(KEY_EXPECTED_SIZE, -1L).takeIf { size -> size > 0 }
            ensureEnoughSpace(contentLength, outputStoragePath(), MIN_OUTPUT_FREE_BYTES)

            val responseType = body.contentType()?.toString()
            val extension = chooseExtension(format, responseType, url)
            val displayName = safeFileName(title, itemId, extension)
            val mimeType = DownloadMimePolicy.choose(mediaType, extension, responseType)
            val destination = createDestination(displayName, mimeType)
            createdOutput = destination
            DiagnosticLogger.info(
                category = "DOWNLOAD_DIRECT",
                event = "destination_created",
                details = mapOf(
                    "name" to displayName,
                    "mime" to mimeType,
                    "expectedBytes" to contentLength,
                ),
            )

            destination.outputStream.use { output ->
                body.byteStream().use { input ->
                    copyWithProgress(input, output, contentLength, 0, 99)
                }
            }
            return destination
        }
    }

    private suspend fun downloadDirectAudio(
        url: String,
        sourceUrl: String? = null,
        title: String?,
        itemId: String,
    ): OutputDestination {
        ensureEnoughSpace(null, applicationContext.cacheDir, MIN_CACHE_FREE_BYTES)
        val taskDirectory = File(applicationContext.cacheDir, "audio_downloads/$id")
        if (taskDirectory.exists()) taskDirectory.deleteRecursively()
        if (!taskDirectory.mkdirs()) throw DownloadException("无法创建音频提取缓存目录。")

        try {
            val response = app.downloadHttpClient.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .apply {
                        sourceUrl?.takeIf { source ->
                            runCatching {
                                val uri = URI(source)
                                (uri.scheme == "https" || uri.scheme == "http") &&
                                    !uri.host.isNullOrBlank()
                            }.getOrDefault(false)
                        }?.let { header("Referer", it) }
                    }
                    .get()
                    .build(),
            ).execute()
            val input = response.use {
                DiagnosticLogger.info(
                    category = "DOWNLOAD_AUDIO",
                    event = "source_http_response",
                    details = mapOf(
                        "status" to it.code,
                        "contentType" to it.body?.contentType(),
                        "contentLength" to it.body?.contentLength(),
                        "finalUrl" to it.request.url,
                    ),
                )
                if (it.code == 401 || it.code == 403) throw accessRestricted()
                if (!it.isSuccessful) {
                    throw DownloadException("音频来源下载失败，服务器返回状态 ${it.code}。")
                }
                val body = it.body ?: throw DownloadException("服务器没有返回可提取音频的媒体内容。")
                validateResponseMime(MediaType.VIDEO.name, body.contentType()?.toString())
                val contentLength = body.contentLength().takeIf { size -> size > 0 }
                ensureEnoughSpace(contentLength, applicationContext.cacheDir, MIN_CACHE_FREE_BYTES)
                val extension = chooseExtension(null, body.contentType()?.toString(), url)
                File(taskDirectory, "input.$extension").also { file ->
                    file.outputStream().use { output ->
                        body.byteStream().use { source ->
                            copyWithProgress(source, output, contentLength, 0, 75)
                        }
                    }
                }
            }

            setProgress(workDataOf(KEY_PROGRESS to 78))
            setForeground(createForegroundInfo(78, "正在本地提取音轨"))
            val output = File(taskDirectory, "audio.m4a")
            val copyArguments = listOf(
                "-i", input.absolutePath,
                "-map", "0:a:0",
                "-vn",
                "-c:a", "copy",
                "-movflags", "+faststart",
                output.absolutePath,
            )
            val encodeArguments = listOf(
                "-i", input.absolutePath,
                "-map", "0:a:0",
                "-vn",
                "-c:a", "aac",
                "-b:a", "192k",
                "-movflags", "+faststart",
                output.absolutePath,
            )
            val copyFailure = runCatching { runBundledFfmpeg(copyArguments) }.exceptionOrNull()
            if (copyFailure != null) {
                if (copyFailure is CancellationException) throw copyFailure
                DiagnosticLogger.warning(
                    category = "DOWNLOAD_AUDIO",
                    event = "stream_copy_failed_trying_aac",
                    failure = copyFailure,
                )
                output.delete()
                runCatching { runBundledFfmpeg(encodeArguments) }.getOrElse { encodeFailure ->
                    if (encodeFailure is CancellationException) throw encodeFailure
                    throw DownloadException(
                        "没有检测到可提取的音轨，或音频转换失败：" +
                            (encodeFailure.message ?: copyFailure.message.orEmpty()).take(160),
                    )
                }
            }
            if (!output.isFile || output.length() == 0L) {
                throw DownloadException("FFmpeg 没有生成有效的 M4A 音频文件。")
            }
            ensureEnoughSpace(output.length(), outputStoragePath(), MIN_OUTPUT_FREE_BYTES)
            setProgress(workDataOf(KEY_PROGRESS to 92))
            val displayName = safeFileName(
                title?.let { "${it}_音频" },
                itemId,
                "m4a",
            )
            val destination = createDestination(displayName, "audio/mp4")
            createdOutput = destination
            destination.outputStream.use { target ->
                output.inputStream().use { source ->
                    copyWithProgress(source, target, output.length(), 92, 99)
                }
            }
            DiagnosticLogger.info(
                category = "DOWNLOAD_AUDIO",
                event = "audio_extracted",
                details = mapOf("inputBytes" to input.length(), "outputBytes" to output.length()),
            )
            return destination
        } finally {
            taskDirectory.deleteRecursively()
        }
    }

    private suspend fun downloadYtDlpItem(
        mediaUrl: String,
        sourceUrl: String,
        format: String?,
        formatSelector: String?,
        mediaType: String,
        title: String?,
        itemId: String,
    ): OutputDestination {
        if (VerifiedQqAudioDirectPolicy.canUse(mediaUrl, sourceUrl, mediaType, format)) {
            DiagnosticLogger.info(
                category = "DOWNLOAD_QQ_AUDIO",
                event = "verified_direct_started",
                details = mapOf(
                    "mediaUrl" to mediaUrl,
                    "sourceUrl" to sourceUrl,
                    "format" to format,
                    "selector" to formatSelector,
                ),
            )
            try {
                return downloadDirect(
                    url = mediaUrl,
                    format = format,
                    mediaType = MediaType.AUDIO.name,
                    title = title,
                    itemId = itemId,
                ).also {
                    DiagnosticLogger.info(
                        category = "DOWNLOAD_QQ_AUDIO",
                        event = "verified_direct_succeeded",
                    )
                }
            } catch (failure: IOException) {
                createdOutput?.delete()
                createdOutput = null
                setProgress(workDataOf(KEY_PROGRESS to 0))
                setForeground(createForegroundInfo(0, "直链已过期，正在重新获取音频"))
                DiagnosticLogger.warning(
                    category = "DOWNLOAD_QQ_AUDIO",
                    event = "verified_direct_failed_falling_back_ytdlp",
                    failure = failure,
                    details = mapOf("selector" to formatSelector),
                )
            }
        }
        return downloadWithYtDlp(
            sourceUrl = sourceUrl,
            formatSelector = formatSelector,
            mediaType = mediaType,
            title = title,
            itemId = itemId,
            audioOnly = false,
        )
    }

    private suspend fun downloadWithYtDlp(
        sourceUrl: String,
        formatSelector: String?,
        mediaType: String,
        title: String?,
        itemId: String,
        audioOnly: Boolean,
    ): OutputDestination {
        ensureEnoughSpace(
            inputData.getLong(KEY_EXPECTED_SIZE, -1L).takeIf { it > 0 },
            applicationContext.cacheDir,
            MIN_CACHE_FREE_BYTES,
        )
        ensureEnoughSpace(
            inputData.getLong(KEY_EXPECTED_SIZE, -1L).takeIf { it > 0 },
            outputStoragePath(),
            MIN_OUTPUT_FREE_BYTES,
        )
        val taskDirectory = File(applicationContext.cacheDir, "downloads/$id")
        if (taskDirectory.exists()) taskDirectory.deleteRecursively()
        if (!taskDirectory.mkdirs()) throw DownloadException("无法创建下载缓存目录。")

        try {
            val ffmpegExecutable = bundledFfmpegExecutable()
            YoutubeDL.getInstance().init(applicationContext)
            val outputTemplate = File(taskDirectory, "media.%(ext)s").absolutePath
            val request = YoutubeDLRequest(sourceUrl)
                .addOption("--no-playlist")
                .addOption("--no-warnings")
                .addOption("--newline")
                .addOption("--format", formatSelector?.takeIf(String::isNotBlank) ?: BEST_SELECTOR)
                .addOption("--ffmpeg-location", ffmpegExecutable.absolutePath)
            if (audioOnly) {
                request
                    .addOption("--extract-audio")
                    .addOption("--audio-format", "m4a")
                    .addOption("--audio-quality", "0")
            } else if (mediaType != MediaType.AUDIO.name) {
                request.addOption("--merge-output-format", "mp4")
            }
            request.addOption("--output", outputTemplate)
            DiagnosticLogger.info(
                category = "DOWNLOAD_YT_DLP",
                event = "execute_started",
                details = mapOf(
                    "sourceUrl" to sourceUrl,
                    "selector" to (formatSelector?.takeIf(String::isNotBlank) ?: BEST_SELECTOR),
                    "audioOnly" to audioOnly,
                    "ffmpeg" to ffmpegExecutable.absolutePath,
                    "outputTemplate" to outputTemplate,
                ),
            )

            val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    YoutubeDL.getInstance().destroyProcessById(id.toString())
                }
            }
            val ytDlpOutput = ArrayDeque<String>()
            try {
                YoutubeDL.getInstance().execute(request, id.toString()) { progress, _, line ->
                    if (line.isNotBlank()) {
                        if (ytDlpOutput.size >= MAX_YT_DLP_LOG_LINES) ytDlpOutput.removeFirst()
                        ytDlpOutput.addLast(line)
                    }
                    val mapped = (progress * 0.9f).toInt().coerceIn(0, 90)
                    setProgressAsync(workDataOf(KEY_PROGRESS to mapped))
                    if (mapped == 0 || mapped % 5 == 0) {
                        setForegroundAsync(
                            createForegroundInfo(
                                mapped,
                                if (mediaType == MediaType.AUDIO.name) "正在下载音频" else "正在下载并准备音视频",
                            ),
                        )
                    }
                }
                DiagnosticLogger.info(
                    category = "DOWNLOAD_YT_DLP",
                    event = "execute_finished",
                    details = mapOf("output" to ytDlpOutput.joinToString(" || ")),
                )
            } catch (failure: Throwable) {
                DiagnosticLogger.error(
                    category = "DOWNLOAD_YT_DLP",
                    event = "execute_failed",
                    failure = failure,
                    details = mapOf("output" to ytDlpOutput.joinToString(" || ")),
                )
                throw failure
            } finally {
                cancellationHandle.dispose()
            }
            if (isStopped) throw CancellationException("下载已取消。")

            val downloadedFiles = taskDirectory.walkTopDown()
                .filter { file ->
                    file.isFile && file.length() > 0 &&
                        file.extension.lowercase(Locale.ROOT) in MEDIA_EXTENSIONS
                }
                .toList()
            val downloaded = if (audioOnly) {
                downloadedFiles.filter { it.extension.equals("m4a", ignoreCase = true) }
                    .maxByOrNull(File::length)
                    ?: downloadedFiles.filter { it.extension.lowercase(Locale.ROOT) in AUDIO_EXTENSIONS }
                        .maxByOrNull(File::length)
            } else {
                downloadedFiles.maxByOrNull(File::length)
            }
                ?: throw DownloadException("下载工具没有生成有效媒体文件。")
            DiagnosticLogger.info(
                category = "DOWNLOAD_YT_DLP",
                event = "media_file_selected",
                details = mapOf(
                    "name" to downloaded.name,
                    "extension" to downloaded.extension,
                    "bytes" to downloaded.length(),
                    "allFiles" to taskDirectory.walkTopDown().filter(File::isFile)
                        .joinToString { "${it.name}:${it.length()}" },
                ),
            )
            val finalMedia = if (
                mediaType == "GIF" && !downloaded.extension.equals("gif", ignoreCase = true)
            ) {
                convertDownloadedVideoToGif(downloaded, taskDirectory)
            } else {
                downloaded
            }
            ensureEnoughSpace(finalMedia.length(), outputStoragePath(), MIN_OUTPUT_FREE_BYTES)

            val extension = finalMedia.extension.lowercase(Locale.ROOT).ifBlank { "mp4" }
            val displayName = safeFileName(title, itemId, extension)
            val destination = createDestination(
                displayName,
                DownloadMimePolicy.fallback(mediaType, extension),
            )
            createdOutput = destination
            destination.outputStream.use { output ->
                finalMedia.inputStream().use { input ->
                    copyWithProgress(input, output, finalMedia.length(), 94, 99)
                }
            }
            return destination
        } finally {
            taskDirectory.deleteRecursively()
        }
    }

    private suspend fun convertDownloadedVideoToGif(input: File, taskDirectory: File): File {
        DiagnosticLogger.info(
            category = "DOWNLOAD_GIF_CONVERSION",
            event = "conversion_started",
            details = mapOf("input" to input.name, "bytes" to input.length()),
        )
        setProgress(workDataOf(KEY_PROGRESS to 90))
        setForeground(createForegroundInfo(90, "正在本地转换为 GIF"))
        val output = File(taskDirectory, "media_converted.gif")
        val paletteArguments = listOf(
            "-i", input.absolutePath,
            "-filter_complex",
            "[0:v]fps=15,split[s0][s1];[s0]palettegen=stats_mode=diff[p];" +
                "[s1][p]paletteuse=dither=sierra2_4a[out]",
            "-map", "[out]",
            "-loop", "0",
            output.absolutePath,
        )
        val simpleArguments = listOf(
            "-i", input.absolutePath,
            "-vf", "fps=12",
            "-loop", "0",
            output.absolutePath,
        )
        val firstFailure = runCatching { runBundledFfmpeg(paletteArguments) }.exceptionOrNull()
        if (firstFailure != null) {
            DiagnosticLogger.warning(
                category = "DOWNLOAD_GIF_CONVERSION",
                event = "palette_profile_failed_trying_simple",
                failure = firstFailure,
            )
            output.delete()
            runCatching { runBundledFfmpeg(simpleArguments) }.getOrElse { fallbackFailure ->
                DiagnosticLogger.error(
                    category = "DOWNLOAD_GIF_CONVERSION",
                    event = "all_profiles_failed",
                    failure = fallbackFailure,
                    details = mapOf("primaryFailure" to firstFailure.message),
                )
                throw DownloadException(
                    "动图已下载，但转换 GIF 失败：${fallbackFailure.message ?: firstFailure.message.orEmpty()}",
                )
            }
        }
        DiagnosticLogger.info(
            category = "DOWNLOAD_GIF_CONVERSION",
            event = "conversion_succeeded",
            details = mapOf("bytes" to output.length()),
        )
        if (!output.isFile || output.length() == 0L) {
            throw DownloadException("动图已下载，但 FFmpeg 没有生成有效 GIF。")
        }
        setProgress(workDataOf(KEY_PROGRESS to 94))
        return output
    }

    private suspend fun runBundledFfmpeg(arguments: List<String>) {
        val executable = bundledFfmpegExecutable()
        DiagnosticLogger.info(
            category = "DOWNLOAD_FFMPEG",
            event = "command_started",
            details = mapOf("arguments" to arguments.joinToString(" ")),
        )
        val process = ProcessBuilder(
            listOf(executable.absolutePath, "-y", "-hide_banner", "-loglevel", "error") + arguments,
        ).redirectErrorStream(true).apply {
            environment()["TMPDIR"] = applicationContext.cacheDir.absolutePath
        }.start()
        val cancellationHandle = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause is CancellationException) process.destroy()
        }
        val errors = ArrayDeque<String>()
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (errors.size >= MAX_FFMPEG_LOG_LINES) errors.removeFirst()
                    errors.addLast(line)
                    if (isStopped) process.destroy()
                }
            }
            val exitCode = process.waitFor()
            DiagnosticLogger.info(
                category = "DOWNLOAD_FFMPEG",
                event = "command_finished",
                details = mapOf(
                    "exitCode" to exitCode,
                    "output" to errors.joinToString(" || "),
                ),
            )
            if (isStopped) throw CancellationException("下载已取消。")
            if (exitCode != 0) {
                throw DownloadException(
                    errors.lastOrNull()?.take(180) ?: "FFmpeg 退出码 $exitCode",
                )
            }
        } finally {
            cancellationHandle.dispose()
            process.destroy()
        }
    }

    private fun bundledFfmpegExecutable(): File {
        val executable = File(applicationContext.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (!executable.isFile || executable.length() == 0L) {
            DiagnosticLogger.warning(
                category = "DOWNLOAD_FFMPEG",
                event = "native_component_missing",
                details = mapOf(
                    "executableExists" to executable.isFile,
                    "executableBytes" to executable.length(),
                    "nativeLibraryDir" to applicationContext.applicationInfo.nativeLibraryDir,
                ),
            )
            throw DownloadException("FFmpeg 本地组件初始化失败。")
        }
        DiagnosticLogger.info(
            category = "DOWNLOAD_FFMPEG",
            event = "native_component_ready",
            details = mapOf(
                "distribution" to "io.github.rbaucells:ffmpeg-android:1.22",
                "executableBytes" to executable.length(),
            ),
        )
        return executable
    }

    private fun validateResponseMime(mediaType: String, responseType: String?) {
        val mime = responseType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (mime.isBlank() || mime == "application/octet-stream") return
        val matchesExpectedType = when (mediaType) {
            "VIDEO" -> mime.startsWith("video/")
            "IMAGE", "COVER" -> mime.startsWith("image/")
            "GIF" -> mime == "image/gif" || mime.startsWith("video/")
            "AUDIO" -> mime.startsWith("audio/")
            else -> true
        }
        if (!matchesExpectedType) {
            throw DownloadException(
                if (mime.startsWith("text/") || mime.contains("json")) {
                    "资源地址已过期或返回了网页，请重新解析后再下载。"
                } else {
                    "服务器返回的文件类型与所选资源不一致（$mime）。"
                },
            )
        }
    }

    private suspend fun copyWithProgress(
        input: java.io.InputStream,
        output: OutputStream,
        totalBytes: Long?,
        startProgress: Int,
        endProgress: Int,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        var lastProgress = -1
        while (true) {
            if (isStopped) throw CancellationException("下载已取消。")
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            total += read
            val progress = totalBytes?.takeIf { it > 0 }?.let { size ->
                startProgress + ((total * (endProgress - startProgress)) / size)
                    .toInt().coerceIn(0, endProgress - startProgress)
            } ?: startProgress
            if (progress != lastProgress) {
                lastProgress = progress
                setProgress(workDataOf(KEY_PROGRESS to progress))
                if (progress == startProgress || progress % 5 == 0) {
                    setForeground(createForegroundInfo(progress, "正在保存文件"))
                }
            }
        }
        output.flush()
    }

    private fun createDestination(displayName: String, mimeType: String): OutputDestination {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val (collection, relativePath) = when {
                mimeType.startsWith("video/") ->
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI to
                        "${Environment.DIRECTORY_MOVIES}/FramePick"
                mimeType.startsWith("image/") ->
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI to
                        "${Environment.DIRECTORY_PICTURES}/FramePick"
                mimeType.startsWith("audio/") ->
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to
                        "${Environment.DIRECTORY_MUSIC}/FramePick"
                else ->
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI to
                        "${Environment.DIRECTORY_DOWNLOADS}/FramePick"
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = applicationContext.contentResolver.insert(
                collection,
                values,
            ) ?: throw DownloadException("无法创建下载文件，请检查存储空间。")
            val stream = applicationContext.contentResolver.openOutputStream(uri, "w")
                ?: run {
                    applicationContext.contentResolver.delete(uri, null, null)
                    throw DownloadException("输出路径不可写。")
                }
            OutputDestination(
                uri = uri,
                outputStream = stream,
                publishAction = {
                    val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    applicationContext.contentResolver.update(uri, published, null, null)
                },
                deleteAction = {
                    applicationContext.contentResolver.delete(uri, null, null)
                },
            )
        } else {
            val directory = applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw DownloadException("外部存储当前不可用。")
            if (!directory.exists() && !directory.mkdirs()) {
                throw DownloadException("无法创建输出目录。")
            }
            val file = uniqueFile(directory, displayName)
            val uri = FileProvider.getUriForFile(
                applicationContext,
                "${applicationContext.packageName}.files",
                file,
            )
            OutputDestination(uri, file.outputStream(), publishAction = {}) { file.delete() }
        }
    }

    private fun ensureEnoughSpace(expectedBytes: Long?, path: File, reserveBytes: Long) {
        val available = StatFs(path.absolutePath).availableBytes
        DiagnosticLogger.info(
            category = "STORAGE",
            event = "space_checked",
            details = mapOf(
                "expectedBytes" to expectedBytes,
                "reserveBytes" to reserveBytes,
                "availableBytes" to available,
            ),
        )
        if (expectedBytes == null) return
        if (available < expectedBytes + reserveBytes) {
            throw DownloadException("手机剩余空间不足，无法保存该文件。")
        }
    }

    private fun outputStoragePath(): File =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Environment.getExternalStorageDirectory()
        } else {
            applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: applicationContext.filesDir
        }

    private fun createForegroundInfo(progress: Int, state: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "媒体下载", NotificationManager.IMPORTANCE_LOW),
        )
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(if (progress > 0) "$state · $progress%" else state)
            .setProgress(100, progress, progress == 0)
            .setOngoing(progress < 100)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun friendlyError(cause: Throwable, sourceUrl: String): String {
        val text = cause.message.orEmpty()
        val lowered = text.lowercase(Locale.ROOT)
        val internationalMessage = InternationalPlatformFailurePolicy
            .accessRestrictionMessage(sourceUrl, text)
        return when {
            cause is DownloadException -> text
            internationalMessage != null -> internationalMessage
            listOf("login", "sign in", "cookie", "private", "403", "forbidden")
                .any(lowered::contains) -> accessRestricted().message.orEmpty()
            isTransientExtractorFailure(cause) -> "平台临时返回异常，请稍后重新下载。"
            isRetryable(cause) -> "下载过程中网络连接中断。"
            cause is YoutubeDLException -> "下载工具无法取得所选音质或清晰度，可能是平台规则发生变化。"
            else -> text.takeIf(String::isNotBlank) ?: "下载失败，请稍后重试。"
        }
    }

    private fun isRetryable(cause: Throwable): Boolean {
        if (cause is IOException && cause !is DownloadException) return true
        if (isTransientExtractorFailure(cause)) return true
        val text = cause.message.orEmpty().lowercase(Locale.ROOT)
        return listOf("network", "timed out", "timeout", "connection", "temporary failure")
            .any(text::contains)
    }

    private fun isTransientExtractorFailure(cause: Throwable): Boolean =
        cause is YoutubeDLException && cause.message.orEmpty()
            .contains("expected string or bytes-like object, got 'bool'", ignoreCase = true)

    private fun accessRestricted() = DownloadException(
        "该资源需要登录或存在访问限制，拾帧不支持提取。",
    )

    private fun failure(message: String): Result = Result.failure(workDataOf(KEY_ERROR to message))

    private fun chooseExtension(format: String?, contentType: String?, url: String): String {
        val normalized = format?.lowercase(Locale.ROOT)?.trim('.')
        if (normalized in MEDIA_EXTENSIONS) return normalized.orEmpty()
        MIME_EXTENSIONS[contentType?.substringBefore(';')?.lowercase(Locale.ROOT)]?.let { return it }
        return url.substringBefore('?').substringAfterLast('.', "bin")
            .lowercase(Locale.ROOT).takeIf { it.length in 2..6 } ?: "bin"
    }

    private fun safeFileName(title: String?, itemId: String, extension: String): String {
        val base = title?.trim()?.takeIf(String::isNotBlank) ?: "media_${itemId.take(8)}"
        val safe = base.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim('.', ' ')
            .take(80)
            .ifBlank { "media_${System.currentTimeMillis()}" }
        return "$safe.$extension"
    }

    private fun uniqueFile(directory: File, displayName: String): File {
        val first = File(directory, displayName)
        if (!first.exists()) return first
        val base = displayName.substringBeforeLast('.', displayName)
        val extension = displayName.substringAfterLast('.', "")
        var index = 1
        while (true) {
            val suffix = extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
            val candidate = File(directory, "$base ($index)$suffix")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private data class OutputDestination(
        val uri: Uri,
        val outputStream: OutputStream,
        val publishAction: () -> Unit,
        val deleteAction: () -> Unit,
    ) {
        fun publish() = publishAction()
        fun delete() = deleteAction()
    }

    private class DownloadException(message: String) : IOException(message)

    private class DirectForbiddenException : IOException("CDN direct link returned 401/403")

    companion object {
        const val KEY_MEDIA_URL = "media_url"
        const val KEY_SOURCE_URL = "source_url"
        const val KEY_BACKUP_URL = "backup_url"
        const val KEY_DOWNLOAD_STRATEGY = "download_strategy"
        const val KEY_FORMAT_SELECTOR = "format_selector"
        const val KEY_MEDIA_TYPE = "media_type"
        const val KEY_FORMAT = "format"
        const val KEY_TITLE = "title"
        const val KEY_ITEM_ID = "item_id"
        const val KEY_EXPECTED_SIZE = "expected_size"
        const val KEY_HISTORY_ID = "history_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_ERROR = "error"

        private const val CHANNEL_ID = "media_downloads"
        private const val NOTIFICATION_ID = 2101
        private const val MIN_OUTPUT_FREE_BYTES = 10L * 1024L * 1024L
        private const val MIN_CACHE_FREE_BYTES = 100L * 1024L * 1024L
        private const val MAX_FFMPEG_LOG_LINES = 40
        private const val MAX_YT_DLP_LOG_LINES = 50
        private const val BEST_SELECTOR =
            "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/bestvideo+bestaudio/best"
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
        private val MEDIA_EXTENSIONS = setOf(
            "mp4", "webm", "mkv", "mov", "m4v", "gif", "jpg", "jpeg", "png", "webp",
            "mp3", "m4a", "aac", "ogg", "wav", "opus", "flac",
        )
        private val AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "ogg", "wav", "opus", "flac", "webm",
        )
        private val MIME_EXTENSIONS = mapOf(
            "video/mp4" to "mp4",
            "video/webm" to "webm",
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/gif" to "gif",
            "audio/mpeg" to "mp3",
            "audio/mp4" to "m4a",
        )
    }
}

internal object DownloadMimePolicy {
    fun choose(mediaType: String, extension: String, responseType: String?): String {
        val responseMime = responseType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        return responseMime?.takeUnless {
            it.isBlank() || it == "application/octet-stream" || it == "application/binary"
        } ?: fallback(mediaType, extension)
    }

    fun fallback(mediaType: String, extension: String): String = when {
        mediaType == MediaType.AUDIO.name -> when (extension) {
            "m4a", "mp4" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "webm" -> "audio/webm"
            "ogg", "opus" -> "audio/ogg"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            else -> "audio/$extension"
        }
        extension == "mp4" -> "video/mp4"
        extension == "webm" -> "video/webm"
        extension == "mkv" -> "video/x-matroska"
        extension == "mov" -> "video/quicktime"
        extension == "jpg" || extension == "jpeg" -> "image/jpeg"
        extension == "png" -> "image/png"
        extension == "webp" -> "image/webp"
        extension == "gif" -> "image/gif"
        extension == "mp3" -> "audio/mpeg"
        extension == "m4a" -> "audio/mp4"
        extension == "ogg" -> "audio/ogg"
        mediaType == "VIDEO" -> "video/$extension"
        mediaType in setOf("IMAGE", "COVER", "GIF") -> "image/$extension"
        else -> "application/octet-stream"
    }
}

internal object VerifiedQqAudioDirectPolicy {
    private val supportedFormats = setOf("mp3", "m4a", "aac")

    fun canUse(
        mediaUrl: String,
        sourceUrl: String,
        mediaType: String,
        format: String?,
    ): Boolean {
        if (mediaType != MediaType.AUDIO.name) return false
        val normalizedFormat = format?.lowercase(Locale.ROOT)?.trim('.')
        if (normalizedFormat !in supportedFormats) return false
        val media = runCatching { URI(mediaUrl) }.getOrNull() ?: return false
        val source = runCatching { URI(sourceUrl) }.getOrNull() ?: return false
        if (media.scheme != "https" || source.scheme != "https") return false
        val mediaHost = media.host?.lowercase(Locale.ROOT).orEmpty()
        val sourceHost = source.host?.lowercase(Locale.ROOT).orEmpty()
        if (sourceHost != "y.qq.com" && !sourceHost.endsWith(".y.qq.com")) return false
        if (mediaHost != "stream.qqmusic.qq.com" &&
            !mediaHost.endsWith(".stream.qqmusic.qq.com")
        ) {
            return false
        }
        val extension = media.path.orEmpty().substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
        return extension == normalizedFormat ||
            (normalizedFormat == "aac" && extension == "m4a")
    }
}
