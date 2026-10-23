package com.temulator.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {

    @Query("SELECT * FROM transfers WHERE ownerUid = :ownerUid ORDER BY updatedAt DESC")
    fun getTransfersFlow(ownerUid: String): Flow<List<TransferRecord>>

    @Query("SELECT * FROM transfers WHERE jobId = :jobId LIMIT 1")
    suspend fun getTransfer(jobId: String): TransferRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(record: TransferRecord)

    @Query("UPDATE transfers SET stage = :stage, progress = :progress, updatedAt = :updatedAt WHERE jobId = :jobId")
    suspend fun updateProgress(jobId: String, stage: String, progress: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM transfers WHERE jobId = :jobId")
    suspend fun delete(jobId: String)

    @Query("DELETE FROM transfers WHERE ownerUid = :ownerUid")
    suspend fun deleteAllForUser(ownerUid: String)
}
