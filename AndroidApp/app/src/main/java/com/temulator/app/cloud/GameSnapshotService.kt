package com.temulator.app.cloud

import android.content.Context
import android.util.Log
import com.temulator.app.data.db.GameRecord
import com.temulator.app.storage.GameStorageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SnapshotResult(
    val backupId: String,
    val bundleFile: File,
    val bundleSha256: String,
    val totalSize: Long,
    val manifestJson: String
)

class GameSnapshotService(val storage: GameStorageManager) {

    constructor(context: Context) : this(GameStorageManager(context))

    companion object {
        private const val TAG = "GameSnapshotService"
        const val SCHEMA_VERSION = 1
    }

    /**
     * Resolves the physical ROM file on disk for a game record.
     * S04: Explicit valid localRomPath must be prioritized over arbitrary files in isolated folder.
     */
    private fun resolveRomFile(game: GameRecord): File? {
        val localPath = game.localRomPath
        if (!localPath.isNullOrEmpty()) {
            val f = File(localPath)
            if (f.exists() && f.isFile) return f
        }

        val romDir = storage.getRomDir(game.gameId)
        val matchingRom = romDir.listFiles()?.firstOrNull { it.isFile && it.name == game.originalFileName }
        if (matchingRom != null && matchingRom.exists()) return matchingRom

        val dirRom = romDir.listFiles()?.firstOrNull { it.isFile }
        if (dirRom != null && dirRom.exists()) return dirRom

        val prep = storage.prepareRomFile(game.sourceUri, game.originalFileName, game.gameId)
        if (prep != null) {
            val f = File(prep.first)
            if (f.exists() && f.isFile) return f
        }

        return null
    }

    /**
     * Creates a self-contained ZIP bundle with manifest and game data in isolated staging.
     */
    fun createSnapshot(game: GameRecord, backupType: String = "ALL"): SnapshotResult? {
        val lease = GameOperationCoordinator.getInstance().tryAcquire(game.gameId, GameLockType.SNAPSHOT)
        if (lease == null) {
            Log.e(TAG, "Cannot create snapshot: Game ${game.gameId} is currently busy.")
            return null
        }
        val backupId = UUID.randomUUID().toString()
        val stagingDir = storage.getStagingDir(backupId)
        val contentStaging = File(stagingDir, "content")
        val bundleFile = File(stagingDir, "bundle.zip")

        try {
            contentStaging.mkdirs()
            val manifestFiles = JSONArray()
            val filesToZip = mutableListOf<Pair<File, String>>() // Pair<SourceFile, ZipEntryPath>

            // Resolve physical ROM file to hash actual current ROM bytes (J04, S01, S02, S04)
            val resolvedRom = resolveRomFile(game)

            var romChecksum = if (resolvedRom != null && resolvedRom.exists()) {
                storage.calculateSha256(resolvedRom)
            } else {
                game.contentSha256 ?: ""
            }

            // 1. Package ROM if backupType is ALL (F11: ALL must contain valid ROM)
            if (backupType == "ALL") {
                if (resolvedRom == null || !resolvedRom.exists()) {
                    Log.e(TAG, "Cannot create ALL snapshot: ROM file missing or inaccessible for game ${game.gameId}")
                    storage.cleanupStaging(backupId)
                    return null
                }

                // Copy to staging content folder first for snapshot coherence
                val stagingRomDir = File(contentStaging, "rom")
                stagingRomDir.mkdirs()
                val stagedRomFile = File(stagingRomDir, resolvedRom.name)
                resolvedRom.copyTo(stagedRomFile, overwrite = true)
                romChecksum = storage.calculateSha256(stagedRomFile)

                val zipPath = "rom/${stagedRomFile.name}"
                filesToZip.add(Pair(stagedRomFile, zipPath))
                manifestFiles.put(JSONObject().apply {
                    put("path", zipPath)
                    put("size", stagedRomFile.length())
                    put("sha256", romChecksum)
                    put("type", "ROM")
                })
            } else if (backupType == "SAVE_ONLY") {
                // S02: SAVE_ONLY must resolve a valid ROM checksum
                if (romChecksum.isEmpty()) {
                    Log.e(TAG, "Cannot create SAVE_ONLY snapshot: unable to resolve ROM checksum for game ${game.gameId}")
                    storage.cleanupStaging(backupId)
                    return null
                }
            }

            // 2. Package Saves (SRAM battery save) - staged for coherence
            val saveDir = storage.getSaveDir(game.gameId)
            val stagingSaveDir = File(contentStaging, "Saves")
            saveDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    if (!stagingSaveDir.exists()) stagingSaveDir.mkdirs()
                    val stagedSave = File(stagingSaveDir, file.name)
                    file.copyTo(stagedSave, overwrite = true)
                    val zipPath = "Saves/${stagedSave.name}"
                    val sha = storage.calculateSha256(stagedSave)
                    filesToZip.add(Pair(stagedSave, zipPath))
                    manifestFiles.put(JSONObject().apply {
                        put("path", zipPath)
                        put("size", stagedSave.length())
                        put("sha256", sha)
                        put("type", "SAVE")
                    })
                }
            }

            // 3. Package SaveStates (MSS slots) - staged for coherence
            val stateDir = storage.getSaveStateDir(game.gameId)
            val stagingStateDir = File(contentStaging, "SaveStates")
            stateDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    if (!stagingStateDir.exists()) stagingStateDir.mkdirs()
                    val stagedState = File(stagingStateDir, file.name)
                    file.copyTo(stagedState, overwrite = true)
                    val zipPath = "SaveStates/${stagedState.name}"
                    val sha = storage.calculateSha256(stagedState)
                    filesToZip.add(Pair(stagedState, zipPath))
                    manifestFiles.put(JSONObject().apply {
                        put("path", zipPath)
                        put("size", stagedState.length())
                        put("sha256", sha)
                        put("type", "STATE")
                    })
                }
            }

            val archiveMember = if (resolvedRom != null && resolvedRom.extension.lowercase() in setOf("zip", "7z")) {
                storage.resolveArchiveMember(resolvedRom, game.consoleType)
            } else null

            // 4. Create manifest.json
            val manifestJson = JSONObject().apply {
                put("schemaVersion", SCHEMA_VERSION)
                put("backupId", backupId)
                put("gameId", game.gameId)
                put("displayName", game.displayName)
                put("originalFileName", game.originalFileName)
                put("consoleType", game.consoleType)
                put("romChecksum", romChecksum)
                put("backupType", backupType)
                put("createdAt", System.currentTimeMillis())
                if (archiveMember != null) {
                    put("archiveMember", archiveMember)
                }
                put("files", manifestFiles)
            }.toString(2)

            val manifestFile = File(stagingDir, "manifest.json")
            manifestFile.writeText(manifestJson)
            filesToZip.add(0, Pair(manifestFile, "manifest.json"))

            // 5. Compress into bundle.zip
            ZipOutputStream(FileOutputStream(bundleFile)).use { zos ->
                val buffer = ByteArray(64 * 1024)
                for ((srcFile, entryPath) in filesToZip) {
                    val entry = ZipEntry(entryPath)
                    zos.putNextEntry(entry)
                    FileInputStream(srcFile).use { fis ->
                        var len: Int
                        while (fis.read(buffer).also { len = it } > 0) {
                            zos.write(buffer, 0, len)
                        }
                    }
                    zos.closeEntry()
                }
            }

            val bundleSha256 = storage.calculateSha256(bundleFile)
            Log.i(TAG, "Created backup snapshot ${bundleFile.name}, size=${bundleFile.length()} bytes, sha256=$bundleSha256")

            return SnapshotResult(
                backupId = backupId,
                bundleFile = bundleFile,
                bundleSha256 = bundleSha256,
                totalSize = bundleFile.length(),
                manifestJson = manifestJson
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create snapshot bundle", e)
            storage.cleanupStaging(backupId)
            return null
        } finally {
            lease.close()
        }
    }
}
