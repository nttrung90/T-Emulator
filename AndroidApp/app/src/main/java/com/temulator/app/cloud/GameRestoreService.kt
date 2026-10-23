package com.temulator.app.cloud

import android.content.Context
import android.util.Log
import com.temulator.app.data.db.AppDatabase
import com.temulator.app.data.db.GameDao
import com.temulator.app.data.db.GameRecord
import com.temulator.app.storage.GameStorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

class GameRestoreService(
    val storage: GameStorageManager,
    val gameDao: GameDao? = null
) {
    constructor(context: Context) : this(
        GameStorageManager(context),
        try { AppDatabase.getInstance(context).gameDao() } catch (e: Exception) { null }
    )

    companion object {
        private const val TAG = "GameRestoreService"
        const val CURRENT_SCHEMA_VERSION = 1
        private const val MAX_UNCOMPRESSED_SIZE = 1024L * 1024 * 1024 // 1 GB safety cap
        private const val MAX_ENTRY_COUNT = 500
        private const val MAX_MANIFEST_SIZE = 1024L * 1024 // 1 MB
    }

    suspend fun restoreFromBundle(bundleZip: File): Result<GameRecord> = withContext(Dispatchers.IO) {
        val globalRestoreLease = GameOperationCoordinator.getInstance().acquireGlobalRestoreLock()
        var gameLease: GameLease? = null
        val restoreOpId = UUID.randomUUID().toString()
        val stagingDir = storage.getStagingDir(restoreOpId)
        val extractStaging = File(stagingDir, "extracted").canonicalFile
        if (!extractStaging.exists()) extractStaging.mkdirs()

        var resolvedGameId: String? = null
        var rollbackDir: File? = null
        var rollbackCreated = false
        var rollbackSucceeded = true
        var isNewGame = false

        fun writeJournal(stage: String, oldRec: GameRecord? = null, finalRec: GameRecord? = null) {
            try {
                val obj = JSONObject().apply {
                    put("opId", restoreOpId)
                    put("gameId", resolvedGameId ?: "")
                    put("stage", stage)
                    put("rollbackDir", rollbackDir?.canonicalPath ?: "")
                    put("isNewGame", isNewGame)
                    if (oldRec != null) put("oldGameRecord", gameRecordToJson(oldRec))
                    if (finalRec != null) put("finalGameRecord", gameRecordToJson(finalRec))
                    put("timestamp", System.currentTimeMillis())
                }
                val tmp = File(stagingDir, "restore_journal.json.tmp")
                tmp.writeText(obj.toString())
                val target = File(stagingDir, "restore_journal.json")
                tmp.renameTo(target)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write restore journal", e)
            }
        }

        try {
            // 1. Safe extraction with strict path traversal, zip bomb & canonical duplicate protection (F10, F11, H06, T02)
            var totalExtractedBytes = 0L
            var entryCount = 0
            val extractedCanonicalPaths = mutableSetOf<String>()

            ZipInputStream(FileInputStream(bundleZip)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    entryCount++
                    if (entryCount > MAX_ENTRY_COUNT) {
                        throw SecurityException("Backup bundle contains too many entries (exceeds $MAX_ENTRY_COUNT)")
                    }

                    val name = entry.name.replace('\\', '/')
                    // Block path traversal attack
                    if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                        throw SecurityException("Malicious path detected in backup archive: $name")
                    }

                    val destFile = File(extractStaging, name).canonicalFile
                    if (!destFile.path.startsWith(extractStaging.path + File.separator)) {
                        throw SecurityException("Path traversal outside extract directory: $name")
                    }

                    if (entry.isDirectory) {
                        destFile.mkdirs()
                    } else {
                        // T02: Canonical duplicate ZIP path check
                        if (!extractedCanonicalPaths.add(destFile.path)) {
                            throw SecurityException("Canonical duplicate ZIP entry detected: $name")
                        }

                        val parent = destFile.parentFile
                        if (parent != null && !parent.exists()) {
                            parent.mkdirs()
                        }

                        FileOutputStream(destFile).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var len: Int
                            while (zis.read(buffer).also { len = it } != -1) {
                                out.write(buffer, 0, len)
                                totalExtractedBytes += len
                                if (totalExtractedBytes > MAX_UNCOMPRESSED_SIZE) {
                                    throw SecurityException("Exceeded maximum safe uncompressed backup size")
                                }
                            }
                            out.flush()
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // 2. Read and strictly validate manifest.json (F10, F11, V06, H06, T01)
            val manifestFile = File(extractStaging, "manifest.json").canonicalFile
            if (!manifestFile.exists()) {
                throw IllegalStateException("Invalid backup bundle: manifest.json is missing.")
            }
            if (manifestFile.length() > MAX_MANIFEST_SIZE) {
                throw SecurityException("manifest.json exceeds maximum allowed size")
            }

            val manifest = JSONObject(manifestFile.readText())
            val schemaVersion = manifest.optInt("schemaVersion", 0)
            if (schemaVersion !in 1..CURRENT_SCHEMA_VERSION) {
                throw IllegalStateException("Unsupported backup schema version: $schemaVersion (supported: 1..$CURRENT_SCHEMA_VERSION)")
            }

            val manifestGameId = manifest.optString("gameId", "").trim()
            if (!storage.isValidGameId(manifestGameId)) {
                throw SecurityException("Invalid gameId in backup manifest: '$manifestGameId'")
            }

            val displayName = manifest.optString("displayName", "Restored Game")
            val originalFileName = storage.sanitizeFileName(manifest.optString("originalFileName", "game.rom"))
            val consoleType = manifest.optInt("consoleType", -1)
            if (consoleType !in 0..6) {
                throw IllegalStateException("Invalid consoleType in manifest: $consoleType")
            }

            val romChecksum = manifest.optString("romChecksum", "").trim()
            val backupType = manifest.optString("backupType", "").trim().uppercase()
            if (backupType != "ALL" && backupType != "SAVE_ONLY") {
                throw IllegalStateException("Unsupported backupType in manifest: '$backupType' (must be ALL or SAVE_ONLY)")
            }
            if (backupType == "ALL" && romChecksum.isEmpty()) {
                throw IllegalStateException("romChecksum is required for ALL backups")
            }
            if (backupType == "SAVE_ONLY" && romChecksum.isEmpty()) {
                throw IllegalStateException("romChecksum is required for SAVE_ONLY backups")
            }

            // 3. Verify manifest files checksums & sizes, and enforce manifest files[] list (F11, V06, G05, H06, T01)
            val manifestFiles = manifest.optJSONArray("files")
            val extractedSaveDirCheck = File(extractStaging, "Saves")
            val extractedStateDirCheck = File(extractStaging, "SaveStates")
            val hasSaveData = (extractedSaveDirCheck.exists() && extractedSaveDirCheck.listFiles()?.any { it.isFile } == true) ||
                    (extractedStateDirCheck.exists() && extractedStateDirCheck.listFiles()?.any { it.isFile } == true)

            if (hasSaveData && (manifestFiles == null || manifestFiles.length() == 0)) {
                throw IllegalStateException("Manifest 'files' array is required when backup contains save data.")
            }

            if (manifestFiles != null) {

            val declaredPaths = mutableSetOf<String>()
            for (i in 0 until manifestFiles.length()) {
                val fileObj = manifestFiles.getJSONObject(i)
                val relPath = fileObj.getString("path").replace('\\', '/')
                val expectedSize = fileObj.getLong("size")
                val expectedSha = fileObj.getString("sha256")

                if (relPath.contains("..") || relPath.startsWith("/")) {
                    throw SecurityException("Malicious path in manifest files: $relPath")
                }
                if (declaredPaths.contains(relPath)) {
                    throw IllegalStateException("Duplicate entry in manifest files: $relPath")
                }
                declaredPaths.add(relPath)

                val actualFile = File(extractStaging, relPath).canonicalFile
                if (!actualFile.exists()) {
                    throw IllegalStateException("Manifest references missing file: $relPath")
                }
                if (actualFile.length() != expectedSize) {
                    throw IllegalStateException("Size mismatch for $relPath: expected $expectedSize, got ${actualFile.length()}")
                }
                val actualSha = storage.calculateSha256(actualFile)
                if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                    throw IllegalStateException("SHA-256 checksum mismatch for $relPath: expected $expectedSha, got $actualSha")
                }
            }

            // Verify no unlisted files were extracted (V06, H06)
            fun checkUnlisted(dir: File) {
                dir.listFiles()?.forEach { file ->
                    if (file.isDirectory) {
                        checkUnlisted(file)
                    } else if (file.isFile) {
                        val rel = file.relativeTo(extractStaging).path.replace('\\', '/')
                        if (rel != "manifest.json" && !declaredPaths.contains(rel)) {
                            throw IllegalStateException("Unlisted file found in backup bundle: $rel")
                        }
                    }
                }
            }
            checkUnlisted(extractStaging)
        }

            // Validate bundle structure according to backupType (V06)
            val extractedRomDir = File(extractStaging, "rom")
            if (backupType == "SAVE_ONLY") {
                if (extractedRomDir.exists() && extractedRomDir.listFiles()?.any { it.isFile } == true) {
                    throw IllegalStateException("SAVE_ONLY backup bundle must not contain ROM files")
                }
            } else if (backupType == "ALL") {
                if (!extractedRomDir.exists()) {
                    throw IllegalStateException("Corrupted ALL backup bundle: missing 'rom/' directory")
                }
                val romFiles = extractedRomDir.listFiles()?.filter { it.isFile }.orEmpty()
                if (romFiles.isEmpty()) {
                    throw IllegalStateException("Corrupted ALL backup bundle: no ROM file in 'rom/' directory")
                }
                if (romFiles.size > 1) {
                    throw IllegalStateException("Corrupted ALL backup bundle: multiple ROM files in 'rom/' directory")
                }
                val romFile = romFiles.first()
                val verifiedSha = storage.calculateSha256(romFile)
                if (romChecksum.isNotEmpty() && !verifiedSha.equals(romChecksum, ignoreCase = true)) {
                    throw IllegalStateException("ROM checksum mismatch: expected $romChecksum, found $verifiedSha")
                }
            }

            // 4. Resolve local target game record and validate SAVE_ONLY mode (F12, V07, G07, N08, H07, T03, T04)
            if (gameDao == null) {
                throw IllegalStateException("Database DAO is unavailable.")
            }

            var existingGame: GameRecord? = null
            if (romChecksum.isNotEmpty()) {
                existingGame = gameDao.getAllGamesList().firstOrNull {
                    it.contentSha256?.equals(romChecksum, ignoreCase = true) == true && it.consoleType == consoleType
                }
            }
            if (existingGame == null) {
                val candidate = gameDao.getGameById(manifestGameId)
                if (candidate != null && candidate.consoleType == consoleType) {
                    val candidateSha = candidate.contentSha256?.trim() ?: ""
                    if (candidateSha.isNotEmpty()) {
                        if (candidateSha.equals(romChecksum, ignoreCase = true)) {
                            existingGame = candidate
                        } else if (backupType == "SAVE_ONLY") {
                            throw IllegalStateException("Cannot restore SAVE_ONLY: existing game ROM checksum does not match backup romChecksum")
                        }
                    } else {
                        val romPath = candidate.localRomPath ?: candidate.sourceUri
                        val romFile = File(romPath)
                        if (romFile.exists() && romFile.isFile) {
                            val calcSha = storage.calculateSha256(romFile)
                            if (calcSha.equals(romChecksum, ignoreCase = true)) {
                                existingGame = candidate
                            } else if (backupType == "SAVE_ONLY") {
                                throw IllegalStateException("Cannot restore SAVE_ONLY: existing ROM hash does not match backup romChecksum")
                            }
                        } else if (backupType == "SAVE_ONLY") {
                            throw IllegalStateException("Cannot restore SAVE_ONLY: existing game ROM is missing")
                        }
                    }
                }
            }

            if (backupType == "SAVE_ONLY") {
                if (existingGame == null) {
                    throw IllegalStateException(
                        "Cannot restore SAVE_ONLY backup: ROM with checksum '$romChecksum' and consoleType $consoleType was not found on this device."
                    )
                }
                // H07, T03: Strictly verify actual physical ROM file exists and matches hash
                val romPath = existingGame.localRomPath ?: existingGame.sourceUri
                val romFile = File(romPath)
                if (!romFile.exists() || !romFile.isFile) {
                    throw IllegalStateException("Cannot restore SAVE_ONLY: actual ROM file not found at $romPath")
                }
                val actualRomHash = storage.calculateSha256(romFile)
                if (!actualRomHash.equals(romChecksum, ignoreCase = true)) {
                    throw IllegalStateException("Cannot restore SAVE_ONLY: actual ROM hash '$actualRomHash' does not match backup checksum '$romChecksum'")
                }
            }

            // Avoid overwriting a different local game when manifest ID collides (G07, N07)
            var localGameId = existingGame?.gameId ?: manifestGameId
            if (existingGame == null) {
                val conflictingGame = gameDao.getGameById(manifestGameId)
                if (conflictingGame != null) {
                    localGameId = "${manifestGameId}_${UUID.randomUUID().toString().take(8)}"
                }
            }
            resolvedGameId = localGameId
            isNewGame = (existingGame == null)

            val lease = GameOperationCoordinator.getInstance().tryAcquire(localGameId, GameLockType.RESTORE)
            if (lease == null) {
                throw IllegalStateException("Game '$localGameId' is currently busy with emulation or another operation.")
            }
            gameLease = lease

            // 5. Create rollback backup for existing game files before touching anything (F11, V05)
            rollbackDir = File(stagingDir, "rollback_$localGameId")
            val rollbackCreatedSuccessfully = storage.createRollbackBackup(localGameId, rollbackDir)
            if (!rollbackCreatedSuccessfully) {
                throw IllegalStateException("Failed to create rollback backup for game '$localGameId'. Aborting to protect existing data.")
            }
            rollbackCreated = true
            writeJournal("ROLLBACK_PREPARED", oldRec = existingGame)

            // 6. Commit ROM if backupType is ALL (V08)
            var localRomFilePath: String? = existingGame?.localRomPath
            if (backupType == "ALL") {
                val romFile = extractedRomDir.listFiles()!!.first { it.isFile }
                val targetRomDir = storage.getRomDir(localGameId)
                val targetRomFile = File(targetRomDir, romFile.name)
                romFile.copyTo(targetRomFile, overwrite = true)
                localRomFilePath = targetRomFile.absolutePath
            }

            // Target native basename for mapping save/state filenames (V07, G06, N06, S05)
            val romCandidate = File(localRomFilePath ?: existingGame?.localRomPath ?: existingGame?.originalFileName ?: originalFileName)
            var targetBasename = romCandidate.nameWithoutExtension
            if (romCandidate.extension.lowercase() in setOf("zip", "7z")) {
                val manifestArchiveMember = manifest.optString("archiveMember", "").ifEmpty { null }
                val detectedMember = storage.resolveArchiveMember(romCandidate, consoleType)
                val member = manifestArchiveMember ?: detectedMember
                if (member != null) {
                    targetBasename = File(member).nameWithoutExtension
                } else {
                    val extractedSaveDir = File(extractStaging, "Saves")
                    val saveFiles = extractedSaveDir.listFiles()?.filter { it.isFile }.orEmpty()
                    if (saveFiles.isNotEmpty()) {
                        targetBasename = saveFiles.first().nameWithoutExtension
                    } else {
                        val extractedStateDir = File(extractStaging, "SaveStates")
                        val stateFiles = extractedStateDir.listFiles()?.filter { it.isFile }.orEmpty()
                        if (stateFiles.isNotEmpty()) {
                            val slotRegex = Regex("^(.*)_([0-9]+)$")
                            val first = stateFiles.first().nameWithoutExtension
                            val m = slotRegex.find(first)
                            targetBasename = if (m != null) m.groupValues[1] else first
                        }
                    }
                }
            }

            // 7. Commit Saves (SRAM) with native basename mapping & collision detection (H08)
            val extractedSaveDir = File(extractStaging, "Saves")
            val targetSaveDir = storage.getSaveDir(localGameId)
            if (extractedSaveDir.exists()) {
                val saveFiles = extractedSaveDir.listFiles()?.filter { it.isFile }.orEmpty()
                val saveDestinationNames = mutableSetOf<String>()
                val plannedSaveMapping = mutableListOf<Pair<File, String>>()

                for (file in saveFiles) {
                    val ext = file.extension
                    val mappedName = if (ext.isNotEmpty()) "$targetBasename.$ext" else targetBasename
                    if (!saveDestinationNames.add(mappedName)) {
                        throw IllegalStateException("SRAM save mapping collision: multiple files map to $mappedName")
                    }
                    plannedSaveMapping.add(Pair(file, mappedName))
                }

                for ((file, mappedName) in plannedSaveMapping) {
                    file.copyTo(File(targetSaveDir, mappedName), overwrite = true)
                }
            }

            // 8. Commit SaveStates with native basename mapping preserving slot numbers & collision detection (G06, N06, H08, T05)
            val extractedStateDir = File(extractStaging, "SaveStates")
            val targetStateDir = storage.getSaveStateDir(localGameId)
            if (extractedStateDir.exists()) {
                val stateFiles = extractedStateDir.listFiles()?.filter { it.isFile }.orEmpty()
                val slotRegex = Regex("^(.*)_([0-9]+)$")
                val stateDestinationNames = mutableSetOf<String>()
                val plannedStateMapping = mutableListOf<Pair<File, String>>()

                for (file in stateFiles) {
                    val slotMatch = slotRegex.find(file.nameWithoutExtension)
                    val mappedName = if (slotMatch != null) {
                        val slot = slotMatch.groupValues[2]
                        val ext = file.extension
                        if (ext.isNotEmpty()) "${targetBasename}_${slot}.$ext" else "${targetBasename}_${slot}"
                    } else {
                        val ext = file.extension
                        if (ext.isNotEmpty()) "$targetBasename.$ext" else targetBasename
                    }
                    if (!stateDestinationNames.add(mappedName)) {
                        throw IllegalStateException("Save state mapping collision: multiple files map to $mappedName")
                    }
                    plannedStateMapping.add(Pair(file, mappedName))
                }

                for ((file, mappedName) in plannedStateMapping) {
                    file.copyTo(File(targetStateDir, mappedName), overwrite = true)
                }
            }

            writeJournal("FILES_COMMITTED", oldRec = existingGame)

            // 9. Update Database Record safely updating ROM path for ALL restore (F12, V08)
            val finalRecord = if (existingGame != null) {
                if (backupType == "ALL") {
                    existingGame.copy(
                        localRomPath = localRomFilePath ?: existingGame.localRomPath,
                        originalFileName = originalFileName.ifEmpty { existingGame.originalFileName },
                        displayName = displayName.ifEmpty { existingGame.displayName },
                        contentSha256 = romChecksum.ifEmpty { existingGame.contentSha256 }
                    )
                } else {
                    existingGame.copy(
                        displayName = displayName.ifEmpty { existingGame.displayName },
                        contentSha256 = romChecksum.ifEmpty { existingGame.contentSha256 }
                    )
                }
            } else {
                val finalPath = localRomFilePath ?: File(storage.getRomDir(localGameId), originalFileName).absolutePath
                GameRecord(
                    gameId = localGameId,
                    sourceUri = finalPath,
                    sourceTreeUri = null,
                    localRomPath = finalPath,
                    displayName = displayName,
                    originalFileName = originalFileName,
                    consoleType = consoleType,
                    addedAt = System.currentTimeMillis(),
                    source = "restore",
                    contentSha256 = romChecksum.ifEmpty { null }
                )
            }

            gameDao.insertOrUpdate(finalRecord)
            writeJournal("DB_COMMITTED", oldRec = existingGame, finalRec = finalRecord)

            // Success: Clean up rollback backup & journal
            if (rollbackDir.exists()) {
                rollbackDir.deleteRecursively()
            }
            try {
                File(stagingDir, "restore_journal.json").delete()
            } catch (ign: Exception) {}

            Log.i(TAG, "Successfully restored game: $displayName (gameId=$localGameId, backupType=$backupType)")
            return@withContext Result.success(finalRecord)
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed, executing rollback", e)
            // Rollback on failure using resolved target ID (F11, V05, H09, T06)
            if (resolvedGameId != null) {
                if (isNewGame) {
                    try {
                        storage.getRomDir(resolvedGameId).deleteRecursively()
                        storage.getSaveDir(resolvedGameId).deleteRecursively()
                        storage.getSaveStateDir(resolvedGameId).deleteRecursively()
                    } catch (ign: Exception) {}
                    rollbackSucceeded = true
                } else if (rollbackCreated && rollbackDir != null) {
                    rollbackSucceeded = storage.restoreFromRollbackBackup(resolvedGameId, rollbackDir)
                    if (!rollbackSucceeded) {
                        Log.e(TAG, "CRITICAL: Rollback failed for game $resolvedGameId! Preserving rollback at ${rollbackDir.absolutePath}")
                        try {
                            File(stagingDir, "RECOVERY_REQUIRED.marker").createNewFile()
                        } catch (ign: Exception) {}
                    }
                }
            }
            return@withContext Result.failure(e)
        } finally {
            try {
                gameLease?.close()
            } catch (ign: Exception) {}
            try {
                globalRestoreLease.close()
            } catch (ign: Exception) {}
            if (rollbackSucceeded) {
                storage.cleanupStaging(restoreOpId)
            } else {
                Log.w(TAG, "Preserving staging directory for $restoreOpId due to unrecovered rollback")
            }
        }
    }

    suspend fun recoverPendingRestores(): Int = withContext(Dispatchers.IO) {
        var recoveredCount = 0
        val stagingRoot = storage.stagingRoot
        if (!stagingRoot.exists() || !stagingRoot.isDirectory) return@withContext 0

        val dirs = stagingRoot.listFiles()?.filter { it.isDirectory } ?: return@withContext 0
        for (dir in dirs) {
            val journalFile = File(dir, "restore_journal.json")
            if (!journalFile.exists()) continue
            val marker = File(dir, "RECOVERY_REQUIRED.marker")
            if (marker.exists()) {
                Log.w(TAG, "Skipping recovery for ${dir.name} as it has RECOVERY_REQUIRED marker")
                continue
            }

            try {
                val json = JSONObject(journalFile.readText())
                val gameId = json.optString("gameId", "")
                if (!storage.isValidGameId(gameId)) continue
                val stage = json.optString("stage", "")
                val isNewGame = json.optBoolean("isNewGame", false)
                val rollbackPath = json.optString("rollbackDir", "")
                val rollbackDir = if (rollbackPath.isNotEmpty()) File(rollbackPath).canonicalFile else null

                if (rollbackDir != null && !rollbackDir.canonicalPath.startsWith(stagingRoot.canonicalPath)) {
                    Log.e(TAG, "Security: Rollback directory outside staging: $rollbackPath")
                    continue
                }

                when (stage) {
                    "DB_COMMITTED", "COMPLETED" -> {
                        dir.deleteRecursively()
                        recoveredCount++
                    }
                    "FILES_COMMITTED", "ROLLBACK_PREPARED" -> {
                        if (isNewGame) {
                            storage.getRomDir(gameId).deleteRecursively()
                            storage.getSaveDir(gameId).deleteRecursively()
                            storage.getSaveStateDir(gameId).deleteRecursively()
                            dir.deleteRecursively()
                            recoveredCount++
                        } else if (rollbackDir != null && rollbackDir.exists() && rollbackDir.isDirectory) {
                            val ok = storage.restoreFromRollbackBackup(gameId, rollbackDir)
                            if (ok) {
                                dir.deleteRecursively()
                                recoveredCount++
                            } else {
                                File(dir, "RECOVERY_REQUIRED.marker").createNewFile()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recover journal in ${dir.name}", e)
            }
        }
        recoveredCount
    }

    private fun gameRecordToJson(record: GameRecord): JSONObject {
        return JSONObject().apply {
            put("gameId", record.gameId)
            put("sourceUri", record.sourceUri)
            put("sourceTreeUri", record.sourceTreeUri ?: JSONObject.NULL)
            put("localRomPath", record.localRomPath ?: JSONObject.NULL)
            put("displayName", record.displayName)
            put("originalFileName", record.originalFileName)
            put("consoleType", record.consoleType)
            put("addedAt", record.addedAt)
            put("source", record.source)
            put("contentSha256", record.contentSha256 ?: JSONObject.NULL)
        }
    }
}
