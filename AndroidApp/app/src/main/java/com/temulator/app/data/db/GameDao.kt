package com.temulator.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {

    @Query("SELECT * FROM games ORDER BY addedAt DESC")
    fun getAllGamesFlow(): Flow<List<GameRecord>>

    @Query("SELECT * FROM games ORDER BY addedAt DESC")
    suspend fun getAllGamesList(): List<GameRecord>

    @Query("SELECT * FROM games WHERE gameId = :gameId LIMIT 1")
    suspend fun getGameById(gameId: String): GameRecord?

    @Query("SELECT * FROM games WHERE sourceUri = :sourceUri LIMIT 1")
    suspend fun getGameBySourceUri(sourceUri: String): GameRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(game: GameRecord)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(games: List<GameRecord>)

    @Query("DELETE FROM games WHERE gameId = :gameId")
    suspend fun deleteById(gameId: String)

    @Query("DELETE FROM games WHERE sourceUri = :sourceUri")
    suspend fun deleteBySourceUri(sourceUri: String)

    @Query("SELECT COUNT(*) FROM games")
    suspend fun getCount(): Int

    @Query("DELETE FROM games")
    suspend fun deleteAll()
}
