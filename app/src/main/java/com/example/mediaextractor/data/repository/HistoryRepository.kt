package com.example.mediaextractor.data.repository

import com.example.mediaextractor.data.database.HistoryDao
import com.example.mediaextractor.data.database.HistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class OperationType { PARSE, DOWNLOAD, VIDEO_TO_GIF, GIF_TO_MP4, EXTRACT_FRAMES }
enum class OperationStatus { WAITING, RUNNING, SUCCESS, FAILED, CANCELED }

data class HistoryRecord(
    val id: Long,
    val timestamp: Long,
    val operationType: OperationType,
    val source: String,
    val outputUri: String?,
    val status: OperationStatus,
    val errorReason: String?,
)

interface HistoryRepository {
    fun observeAll(): Flow<List<HistoryRecord>>
    suspend fun create(
        type: OperationType,
        source: String,
        status: OperationStatus = OperationStatus.WAITING,
    ): Long
    suspend fun updateResult(
        id: Long,
        status: OperationStatus,
        outputUri: String? = null,
        errorReason: String? = null,
    )
    suspend fun delete(id: Long)
    suspend fun clearAll()
    suspend fun markInterruptedConversions()
}

class RoomHistoryRepository(
    private val dao: HistoryDao,
) : HistoryRepository {
    override fun observeAll(): Flow<List<HistoryRecord>> = dao.observeAll().map { list ->
        list.map { it.toDomain() }
    }

    override suspend fun create(
        type: OperationType,
        source: String,
        status: OperationStatus,
    ): Long = dao.insert(
        HistoryEntity(
            timestamp = System.currentTimeMillis(),
            operationType = type.name,
            source = source,
            outputUri = null,
            status = status.name,
            errorReason = null,
        ),
    )

    override suspend fun updateResult(
        id: Long,
        status: OperationStatus,
        outputUri: String?,
        errorReason: String?,
    ) = dao.updateResult(id, status.name, outputUri, errorReason)

    override suspend fun delete(id: Long) = dao.deleteById(id)

    override suspend fun clearAll() = dao.clearAll()

    override suspend fun markInterruptedConversions() = dao.markInterruptedConversions()

    private fun HistoryEntity.toDomain() = HistoryRecord(
        id = id,
        timestamp = timestamp,
        operationType = enumValueOrDefault(operationType, OperationType.PARSE),
        source = source,
        outputUri = outputUri,
        status = enumValueOrDefault(status, OperationStatus.FAILED),
        errorReason = errorReason,
    )

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, fallback: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)
}
