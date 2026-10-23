package com.temulator.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudBackupDao {

    @Query("SELECT * FROM cloud_backups WHERE ownerUid = :ownerUid ORDER BY createdAt DESC")
    fun getBackupsForUserFlow(ownerUid: String): Flow<List<CloudBackupRecord>>

    @Query("SELECT * FROM cloud_backups WHERE ownerUid = :ownerUid AND gameId = :gameId ORDER BY createdAt DESC")
    suspend fun getBackupsForGame(ownerUid: String, gameId: String): List<CloudBackupRecord>

    @Query("SELECT * FROM cloud_backups WHERE backupId = :backupId LIMIT 1")
    suspend fun getBackupById(backupId: String): CloudBackupRecord?

    @Query("SELECT * FROM cloud_backups WHERE remoteFileId = :remoteFileId LIMIT 1")
    suspend fun getBackupByRemoteFileId(remoteFileId: String): CloudBackupRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(backup: CloudBackupRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(backups: List<CloudBackupRecord>)

    @Query("DELETE FROM cloud_backups WHERE backupId = :backupId")
    suspend fun delete(backupId: String)

    @Query("DELETE FROM cloud_backups WHERE ownerUid = :ownerUid")
    suspend fun deleteAllForUser(ownerUid: String)
}
