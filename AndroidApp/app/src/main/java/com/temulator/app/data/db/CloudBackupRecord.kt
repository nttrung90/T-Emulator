package com.temulator.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cloud_backups",
    indices = [
        Index(value = ["ownerUid", "driveAccount"]),
        Index(value = ["gameId"])
    ]
)
data class CloudBackupRecord(
    @PrimaryKey
    val backupId: String,
    val ownerUid: String,
    val driveAccount: String,
    val gameId: String,
    val remoteFileId: String,
    val schemaVersion: Int = 1,
    val createdAt: Long,
    val checksum: String,
    val size: Long,
    val status: String, // "COMPLETED", "FAILED", "REVOKED"
    val backupType: String = "ALL" // "ALL", "SAVE_ONLY"
)
