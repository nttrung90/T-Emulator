package com.temulator.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "native_game_mappings")
data class NativeGameMapping(
    @PrimaryKey
    val gameId: String,
    val nativeBasename: String,
    val nativeConsole: Int,
    val archiveMember: String? = null,
    val saveDirectory: String,
    val stateDirectory: String
)
