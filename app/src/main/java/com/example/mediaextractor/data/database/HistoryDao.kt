package com.example.mediaextractor.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_records ORDER BY timestamp DESC, id DESC")
    fun observeAll(): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: HistoryEntity): Long

    @Query(
        """
        UPDATE history_records
        SET status = :status, outputUri = :outputUri, errorReason = :errorReason
        WHERE id = :id
        """,
    )
    suspend fun updateResult(id: Long, status: String, outputUri: String?, errorReason: String?)

    @Query("DELETE FROM history_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM history_records")
    suspend fun clearAll()

    @Query(
        """
        UPDATE history_records
        SET status = 'FAILED', errorReason = '应用上次运行期间转换被中断。'
        WHERE status = 'RUNNING'
          AND operationType IN ('VIDEO_TO_GIF', 'GIF_TO_MP4', 'EXTRACT_FRAMES')
        """,
    )
    suspend fun markInterruptedConversions()
}
