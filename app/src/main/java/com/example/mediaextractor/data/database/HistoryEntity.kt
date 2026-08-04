package com.example.mediaextractor.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history_records")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val operationType: String,
    val source: String,
    val outputUri: String?,
    val status: String,
    val errorReason: String?,
)
