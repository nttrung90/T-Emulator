package com.temulator.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transfers")
data class TransferRecord(
    @PrimaryKey
    val jobId: String,
    val batchId: String? = null,
    val ownerUid: String,
    val accountSessionVersion: Int = 1,
    val backupId: String,
    val operation: String, // "BACKUP", "RESTORE"
    val stage: String, // "QUEUED", "PREPARING", "UPLOADING", "DOWNLOADING", "VERIFYING", "COMPLETED", "FAILED", "CANCELED"
    val progress: Int = 0,
    val resumableSession: String? = null,
    val errorMetadata: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)
