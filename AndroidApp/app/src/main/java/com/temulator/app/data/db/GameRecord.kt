package com.temulator.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "games",
    indices = [
        Index(value = ["sourceUri"], unique = true),
        Index(value = ["displayName"]),
        Index(value = ["addedAt"])
    ]
)
data class GameRecord(
    @PrimaryKey
    val gameId: String,
    val sourceUri: String,
    val sourceTreeUri: String? = null,
    val localRomPath: String? = null,
    val displayName: String,
    val originalFileName: String,
    val consoleType: Int,
    val addedAt: Long,
    val source: String = "scan", // "scan", "manual", "restore"
    val contentSha256: String? = null
)
