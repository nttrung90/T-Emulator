package com.temulator.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.temulator.app.data.db.AppDatabase
import com.temulator.app.data.db.GameRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class GameEntry(
    val filePath: String,
    val fileName: String,
    val consoleType: Int,
    val addedTimestamp: Long,
    val source: String
) {
    fun toRecord(treeUri: Uri? = null): GameRecord {
        val id = GameRepository.generateGameId(filePath)
        return GameRecord(
            gameId = id,
            sourceUri = filePath,
            sourceTreeUri = treeUri?.toString(),
            localRomPath = if (!filePath.startsWith("content://")) filePath else null,
            displayName = fileName,
            originalFileName = fileName,
            consoleType = consoleType,
            addedAt = addedTimestamp,
            source = source
        )
    }
}

fun GameRecord.toEntry(): GameEntry {
    return GameEntry(
        filePath = localRomPath ?: sourceUri,
        fileName = originalFileName,
        consoleType = consoleType,
        addedTimestamp = addedAt,
        source = source
    )
}

class GameRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("game_library", Context.MODE_PRIVATE)
    private val db = AppDatabase.getInstance(context)
    private val gameDao = db.gameDao()
    private val ioScope = CoroutineScope(Dispatchers.IO)

    init {
        migrateFromLegacyPrefsIfNeeded()
    }

    companion object {
        private const val TAG = "GameRepository"
        private const val KEY_GAMES_JSON = "games_json"
        private const val KEY_GAMES_JSON_BACKUP = "games_json_migrated_backup"
        private const val KEY_MIGRATED_TO_ROOM = "migrated_to_room_v1"
        private const val KEY_SCAN_FOLDER_URI = "scan_folder_uri"

        val SUPPORTED_EXTENSIONS = setOf(
            "nes", "fds", "unf", "unif", "nsf",
            "sfc", "smc", "fig", "swc",
            "gb", "gbc", "gbx",
            "gba", "agb",
            "pce", "sgx", "hes", "cue",
            "sms", "gg", "sg", "col",
            "ws", "wsc",
            "zip", "7z"
        )

        fun generateGameId(sourceUri: String): String {
            // Stable deterministic UUID derived from source URI so re-scans don't change gameId
            return UUID.nameUUIDFromBytes(sourceUri.toByteArray(Charsets.UTF_8)).toString()
        }

        fun extensionToConsoleType(ext: String, fileName: String = ""): Int {
            return when (ext.lowercase()) {
                "sfc", "smc", "fig", "swc" -> 0
                "gb", "gbc", "gbx" -> 1
                "nes", "fds", "unf", "unif", "nsf" -> 2
                "pce", "sgx", "hes", "cue" -> 3
                "sms", "gg", "sg", "col" -> 4
                "gba", "agb" -> 5
                "ws", "wsc" -> 6
                "zip", "7z" -> {
                    val lower = fileName.lowercase()
                    when {
                        lower.contains(".nes") || lower.contains("(nes)") || lower.contains(".fds") -> 2
                        lower.contains(".gba") || lower.contains("(gba)") || lower.contains(".agb") -> 5
                        lower.contains(".sfc") || lower.contains("(sfc)") || lower.contains(".smc") || lower.contains(".fig") -> 0
                        lower.contains(".gbc") || lower.contains(".gb") -> 1
                        lower.contains(".pce") || lower.contains(".sgx") -> 3
                        lower.contains(".sms") || lower.contains(".gg") || lower.contains(".col") -> 4
                        lower.contains(".ws") || lower.contains(".wsc") -> 6
                        else -> 2
                    }
                }
                else -> 2
            }
        }
    }

    private fun migrateFromLegacyPrefsIfNeeded() {
        if (prefs.getBoolean(KEY_MIGRATED_TO_ROOM, false)) {
            return
        }
        val jsonStr = prefs.getString(KEY_GAMES_JSON, null)
        if (jsonStr.isNullOrEmpty()) {
            prefs.edit().putBoolean(KEY_MIGRATED_TO_ROOM, true).apply()
            return
        }

        try {
            val jsonArray = JSONArray(jsonStr)
            val migratedRecords = mutableListOf<GameRecord>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val path = obj.getString("filePath")
                val name = obj.getString("fileName")
                val console = obj.getInt("consoleType")
                val added = obj.getLong("addedTimestamp")
                val src = obj.optString("source", "manual")
                val record = GameRecord(
                    gameId = generateGameId(path),
                    sourceUri = path,
                    sourceTreeUri = null,
                    localRomPath = if (!path.startsWith("content://")) path else null,
                    displayName = name,
                    originalFileName = name,
                    consoleType = console,
                    addedAt = added,
                    source = src
                )
                migratedRecords.add(record)
            }

            runBlocking(Dispatchers.IO) {
                gameDao.insertOrUpdateAll(migratedRecords)
            }

            // Safely backup old JSON and mark migration complete
            prefs.edit()
                .putString(KEY_GAMES_JSON_BACKUP, jsonStr)
                .remove(KEY_GAMES_JSON)
                .putBoolean(KEY_MIGRATED_TO_ROOM, true)
                .apply()

            Log.i(TAG, "Successfully migrated ${migratedRecords.size} games to Room DB.")
        } catch (e: Exception) {
            Log.e(TAG, "Error during migration from legacy JSON", e)
        }
    }

    fun getAllGamesFlow(): Flow<List<GameRecord>> {
        return gameDao.getAllGamesFlow()
    }

    fun getAllGames(): List<GameRecord> {
        return runBlocking(Dispatchers.IO) {
            gameDao.getAllGamesList()
        }
    }

    suspend fun getAllGamesSuspend(): List<GameRecord> {
        return gameDao.getAllGamesList()
    }

    suspend fun getGameById(gameId: String): GameRecord? {
        return gameDao.getGameById(gameId)
    }

    fun addGame(entry: GameEntry) {
        val record = entry.toRecord()
        ioScope.launch {
            gameDao.insertOrUpdate(record)
        }
    }

    fun addGameRecord(record: GameRecord) {
        ioScope.launch {
            gameDao.insertOrUpdate(record)
        }
    }

    fun addGames(entries: List<GameEntry>) {
        val records = entries.map { it.toRecord() }
        ioScope.launch {
            gameDao.insertOrUpdateAll(records)
        }
    }

    fun addGameRecords(records: List<GameRecord>) {
        ioScope.launch {
            gameDao.insertOrUpdateAll(records)
        }
    }

    fun removeGame(filePathOrGameId: String) {
        ioScope.launch {
            if (filePathOrGameId.contains("/") || filePathOrGameId.startsWith("content:")) {
                gameDao.deleteBySourceUri(filePathOrGameId)
            } else {
                gameDao.deleteById(filePathOrGameId)
            }
        }
    }

    fun getScanFolderUri(): Uri? {
        val uriStr = prefs.getString(KEY_SCAN_FOLDER_URI, null) ?: return null
        return try {
            Uri.parse(uriStr)
        } catch (e: Exception) {
            null
        }
    }

    fun setScanFolderUri(uri: Uri) {
        prefs.edit().putString(KEY_SCAN_FOLDER_URI, uri.toString()).apply()
    }

    fun scanDirectory(treeUri: Uri): List<GameRecord> {
        val results = mutableListOf<GameRecord>()
        try {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            scanDocumentTree(treeUri, rootDocId, results)
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning directory tree", e)
        }
        return results
    }

    private fun scanDocumentTree(treeUri: Uri, parentDocId: String, results: MutableList<GameRecord>) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
        cursor?.use {
            while (it.moveToNext()) {
                val docId = it.getString(0)
                val name = it.getString(1) ?: continue
                val mimeType = it.getString(2) ?: ""

                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    scanDocumentTree(treeUri, docId, results)
                } else {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in SUPPORTED_EXTENSIONS) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        val docUriStr = docUri.toString()
                        val consoleType = extensionToConsoleType(ext, name)
                        val gameId = generateGameId(docUriStr)
                        results.add(
                            GameRecord(
                                gameId = gameId,
                                sourceUri = docUriStr,
                                sourceTreeUri = treeUri.toString(),
                                localRomPath = null,
                                displayName = name,
                                originalFileName = name,
                                consoleType = consoleType,
                                addedAt = System.currentTimeMillis(),
                                source = "scan"
                            )
                        )
                    }
                }
            }
        }
    }
}
