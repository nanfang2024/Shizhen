package com.framepick.app.data.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.lifecycle.asFlow
import com.framepick.app.data.repository.HistoryRepository
import com.framepick.app.data.repository.OperationStatus
import com.framepick.app.data.repository.OperationType
import com.framepick.app.domain.model.MediaItem
import com.framepick.app.util.DiagnosticLogger
import com.framepick.app.worker.MediaDownloadWorker
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class DownloadTask(
    val workId: UUID,
    val mediaItemId: String,
    val state: WorkInfo.State,
    val progress: Int,
    val outputUri: String?,
    val errorMessage: String?,
)

interface DownloadRepository {
    fun observeDownloads(): Flow<List<DownloadTask>>
    suspend fun enqueue(item: MediaItem, title: String?): UUID
    fun cancel(workId: UUID)
}

class WorkManagerDownloadRepository(
    context: Context,
    private val historyRepository: HistoryRepository,
) : DownloadRepository {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun observeDownloads(): Flow<List<DownloadTask>> =
        workManager.getWorkInfosByTagLiveData(TAG_ALL).asFlow().map { infos ->
            infos.mapNotNull { info ->
                val itemId = info.tags.firstOrNull { it.startsWith(TAG_ITEM_PREFIX) }
                    ?.removePrefix(TAG_ITEM_PREFIX)
                    ?: return@mapNotNull null
                DownloadTask(
                    workId = info.id,
                    mediaItemId = itemId,
                    state = info.state,
                    progress = info.progress.getInt(MediaDownloadWorker.KEY_PROGRESS, 0),
                    outputUri = info.outputData.getString(MediaDownloadWorker.KEY_OUTPUT_URI),
                    errorMessage = info.outputData.getString(MediaDownloadWorker.KEY_ERROR),
                )
            }
        }

    override suspend fun enqueue(item: MediaItem, title: String?): UUID {
        DiagnosticLogger.info(
            category = "DOWNLOAD",
            event = "enqueue_requested",
            details = mapOf(
                "itemId" to item.id,
                "type" to item.type.name,
                "format" to item.format,
                "quality" to item.qualityLabel,
                "strategy" to item.downloadStrategy.name,
                "selector" to item.formatSelector,
                "previewOnly" to item.isPreviewOnly,
                "mediaUrl" to item.mediaUrl,
                "sourceUrl" to item.downloadSourceUrl,
            ),
        )
        val displayTitle = title?.let {
            if (item.isPreviewOnly) "${it}_仅试听片段" else it
        } ?: if (item.isPreviewOnly) "仅试听片段" else null
        val historyId = historyRepository.create(
            type = OperationType.DOWNLOAD,
            source = displayTitle?.let { "$it\n${item.downloadSourceUrl}" }
                ?: item.downloadSourceUrl,
        )
        val inputData = Data.Builder()
            .putString(MediaDownloadWorker.KEY_MEDIA_URL, item.mediaUrl)
            .putString(MediaDownloadWorker.KEY_SOURCE_URL, item.downloadSourceUrl)
            .putString(MediaDownloadWorker.KEY_BACKUP_URL, item.backupUrl)
            .putString(MediaDownloadWorker.KEY_DOWNLOAD_STRATEGY, item.downloadStrategy.name)
            .putString(MediaDownloadWorker.KEY_FORMAT_SELECTOR, item.formatSelector)
            .putString(MediaDownloadWorker.KEY_MEDIA_TYPE, item.type.name)
            .putString(MediaDownloadWorker.KEY_FORMAT, item.format)
            .putString(MediaDownloadWorker.KEY_TITLE, displayTitle)
            .putString(MediaDownloadWorker.KEY_ITEM_ID, item.id)
            .putLong(MediaDownloadWorker.KEY_EXPECTED_SIZE, item.fileSize ?: -1L)
            .putLong(MediaDownloadWorker.KEY_HISTORY_ID, historyId)
            .build()
        val request = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
            .setInputData(inputData)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .addTag(TAG_ALL)
            .addTag("$TAG_ITEM_PREFIX${item.id}")
            .build()
        workManager.enqueueUniqueWork(
            "media_download_${item.id}",
            ExistingWorkPolicy.KEEP,
            request,
        )
        DiagnosticLogger.info(
            category = "DOWNLOAD",
            event = "work_enqueued",
            details = mapOf("workId" to request.id, "itemId" to item.id),
        )
        return request.id
    }

    override fun cancel(workId: UUID) {
        DiagnosticLogger.info(
            category = "DOWNLOAD",
            event = "cancel_requested",
            details = mapOf("workId" to workId),
        )
        workManager.cancelWorkById(workId)
    }

    companion object {
        const val TAG_ALL = "media_download"
        const val TAG_ITEM_PREFIX = "media_item:"
    }
}
