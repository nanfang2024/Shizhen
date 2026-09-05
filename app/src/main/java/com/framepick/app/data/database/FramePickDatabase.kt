package com.framepick.app.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [HistoryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class FramePickDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
}
