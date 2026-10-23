package com.temulator.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NativeGameMappingDao {

    @Query("SELECT * FROM native_game_mappings WHERE gameId = :gameId LIMIT 1")
    suspend fun getMapping(gameId: String): NativeGameMapping?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(mapping: NativeGameMapping)

    @Query("DELETE FROM native_game_mappings WHERE gameId = :gameId")
    suspend fun delete(gameId: String)
}
