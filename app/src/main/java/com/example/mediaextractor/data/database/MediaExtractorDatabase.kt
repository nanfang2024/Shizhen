package com.example.mediaextractor.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [HistoryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class MediaExtractorDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
}
