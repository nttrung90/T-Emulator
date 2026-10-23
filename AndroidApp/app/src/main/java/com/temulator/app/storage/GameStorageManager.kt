package com.temulator.app.storage

import android.content.Context
import android.net.Uri
import com.temulator.app.data.db.NativeGameMapping
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

class GameStorageManager(
    val filesDir: File,
    private val context: Context? = null
) {
    constructor(context: Context) : this(context.filesDir, context)

    private val gamesRoot: File = File(filesDir, "games")
    val stagingRoot: File = File(filesDir, "staging")

    init {
        if (!gamesRoot.exists()) gamesRoot.mkdirs()
        if (!stagingRoot.exists()) stagingRoot.mkdirs()
    }

    /**
     * Enforces valid game ID / operation ID format:
     * strictly alphanumeric, underscores, and hyphens (3 to 64 chars).
     * Disallows '.', '/', '\' and empty strings.
     */
    fun isValidGameId(id: String): Boolean {
        if (id.isBlank() || id.length < 3 || id.length > 64) return false
        return id.matches(Regex("^[a-zA-Z0-9_-]{3,64}$"))
    }

    fun getGameDir(gameId: String): File {
        if (!isValidGameId(gameId)) {
            throw IllegalArgumentException("Invalid gameId: contains illegal characters or invalid length: '$gameId'")
        }
        val target = File(gamesRoot, gameId).canonicalFile
        val canonicalRoot = gamesRoot.canonicalFile
        if (!target.path.startsWith(canonicalRoot.path + File.separator)) {
            throw SecurityException("Path traversal attempt in game directory: $gameId")
        }
        if (!target.exists()) target.mkdirs()
        return target
    }

    fun getRomDir(gameId: String): File {
        val dir = File(getGameDir(gameId), "rom")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getSaveDir(gameId: String): File {
        val dir = File(getGameDir(gameId), "Saves")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getSaveStateDir(gameId: String): File {
        val dir = File(getGameDir(gameId), "SaveStates")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getStagingDir(operationId: String): File {
        if (!isValidGameId(operationId)) {
            throw IllegalArgumentException("Invalid operationId: contains illegal characters or invalid length: '$operationId'")
        }
        val target = File(stagingRoot, operationId).canonicalFile
        val canonicalRoot = stagingRoot.canonicalFile
        if (!target.path.startsWith(canonicalRoot.path + File.separator)) {
            throw SecurityException("Path traversal attempt in staging directory: $operationId")
        }
        if (!target.exists()) target.mkdirs()
        return target
    }

    fun sanitizeFileName(fileName: String): String {
        // Prevent path traversal and illegal filesystem characters
        val clean = fileName.replace("[/\\\\:*?\"<>|]".toRegex(), "_")
            .replace("\\.\\.+".toRegex(), ".")
            .trim()
        return if (clean.isEmpty()) "unnamed_game.rom" else clean
    }

    /**
     * Copies ROM from SAF Content Uri or local file path into isolated game folder.
     * Uses atomic temporary file write to prevent partial/corrupted files.
     */
    fun prepareRomFile(sourceUriStr: String, originalFileName: String, gameId: String): Pair<String, String>? {
        val sanitizedName = sanitizeFileName(originalFileName)
        val romDir = getRomDir(gameId)
        val targetFile = File(romDir, sanitizedName)
        val tempFile = File(romDir, "${sanitizedName}.tmp.${System.currentTimeMillis()}")

        var inputStream: InputStream? = null
        try {
            if (sourceUriStr.startsWith("content://")) {
                if (context == null) return null
                val uri = Uri.parse(sourceUriStr)
                inputStream = context.contentResolver.openInputStream(uri)
                    ?: return null
            } else {
                val sourceFile = File(sourceUriStr)
                if (!sourceFile.exists()) return null
                inputStream = FileInputStream(sourceFile)
            }

            FileOutputStream(tempFile).use { out ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    out.write(buffer, 0, bytesRead)
                }
                out.flush()
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                // Fallback copy if rename fails
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            val sha256 = calculateSha256(targetFile)
            return Pair(targetFile.absolutePath, sha256)
        } catch (e: Exception) {
            e.printStackTrace()
            if (tempFile.exists()) tempFile.delete()
            return null
        } finally {
            try {
                inputStream?.close()
            } catch (_: Exception) {}
        }
    }

    fun calculateSha256(file: File): String {
        if (!file.exists()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(64 * 1024)
            var n: Int
            while (fis.read(buffer).also { n = it } != -1) {
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun resolveArchiveMember(archiveFile: File, consoleType: Int): String? {
        if (!archiveFile.exists() || !archiveFile.isFile) return null
        val ext = archiveFile.extension.lowercase()
        if (ext == "zip") {
            return try {
                java.util.zip.ZipFile(archiveFile).use { zip ->
                    val entries = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
                    val romExtensions = listOf(
                        ".nes", ".fds", ".qd", ".unif", ".unf", ".nsf", ".nsfe", ".studybox",
                        ".sfc", ".swc", ".fig", ".smc", ".bs", ".st", ".spc",
                        ".gb", ".gbc", ".gbx", ".gbs",
                        ".pce", ".sgx", ".cue", ".hes",
                        ".sms", ".gg", ".sg", ".col",
                        ".gba", ".agb", ".bin",
                        ".ws", ".wsc"
                    )
                    val romEntries = entries.filter { name ->
                        romExtensions.any { e -> name.endsWith(e, ignoreCase = true) }
                    }
                    fun matchesConsole(name: String): Boolean {
                        val lower = name.lowercase()
                        return when (consoleType) {
                            0 -> lower.endsWith(".sfc") || lower.endsWith(".smc") || lower.endsWith(".fig")
                            1 -> lower.endsWith(".gb") || lower.endsWith(".gbc") || lower.endsWith(".sgb")
                            2 -> lower.endsWith(".nes") || lower.endsWith(".fds") || lower.endsWith(".unf")
                            3 -> lower.endsWith(".pce") || lower.endsWith(".sgx")
                            4 -> lower.endsWith(".sms") || lower.endsWith(".gg") || lower.endsWith(".col")
                            5 -> lower.endsWith(".gba") || lower.endsWith(".agb") || lower.endsWith(".bin")
                            6 -> lower.endsWith(".ws") || lower.endsWith(".wsc")
                            else -> false
                        }
                    }
                    romEntries.firstOrNull { matchesConsole(it) }
                        ?: romEntries.firstOrNull()
                        ?: entries.firstOrNull { name ->
                            val entryExt = name.substringAfterLast('.', "").lowercase()
                            entryExt !in listOf("txt", "nfo", "doc", "jpg", "png", "pdf")
                        }
                }
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    fun getNativeMapping(gameId: String, romPath: String, consoleType: Int): NativeGameMapping {
        val file = File(romPath)
        val archiveMember = if (file.extension.lowercase() in setOf("zip", "7z")) {
            resolveArchiveMember(file, consoleType)
        } else {
            null
        }
        val nativeBasename = if (archiveMember != null) {
            File(archiveMember).nameWithoutExtension
        } else {
            file.nameWithoutExtension
        }
        val saveDir = getSaveDir(gameId).absolutePath
        val stateDir = getSaveStateDir(gameId).absolutePath
        return NativeGameMapping(
            gameId = gameId,
            nativeBasename = nativeBasename,
            nativeConsole = consoleType,
            archiveMember = archiveMember,
            saveDirectory = saveDir,
            stateDirectory = stateDir
        )
    }

    /**
     * Creates a backup copy of a game directory for rollback safety before restore operations.
     */
    fun createRollbackBackup(gameId: String, rollbackDir: File): Boolean {
        return try {
            if (rollbackDir.exists()) rollbackDir.deleteRecursively()
            rollbackDir.mkdirs()
            val gameDir = getGameDir(gameId)
            if (gameDir.exists() && gameDir.listFiles()?.isNotEmpty() == true) {
                gameDir.copyRecursively(rollbackDir, overwrite = true)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Restores game directory from rollback backup in case of error.
     * S06: Refuse absent or non-directory backup BEFORE modifying live target, preserving live data.
     */
    fun restoreFromRollbackBackup(gameId: String, rollbackDir: File): Boolean {
        if (!rollbackDir.exists() || !rollbackDir.isDirectory) {
            return false
        }
        return try {
            val gameDir = getGameDir(gameId)
            if (gameDir.exists() && !gameDir.deleteRecursively()) {
                return false
            }
            val files = rollbackDir.listFiles()
            if (files != null && files.isNotEmpty()) {
                if (!gameDir.exists() && !gameDir.mkdirs()) return false
                rollbackDir.copyRecursively(gameDir, overwrite = true)
            } else {
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Cleans up staging directories older than 1 day or for a specific operation.
     * Does not purge staging directories containing pending recovery markers or journals.
     */
    fun cleanupStaging(operationId: String? = null) {
        try {
            if (operationId != null && isValidGameId(operationId)) {
                val dir = File(stagingRoot, operationId)
                if (dir.exists()) dir.deleteRecursively()
            } else {
                if (stagingRoot.exists() && stagingRoot.isDirectory) {
                    val oneDayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000
                    stagingRoot.listFiles()?.forEach { file ->
                        val hasPendingRecovery = file.isDirectory && (
                            File(file, "RECOVERY_REQUIRED.marker").exists() ||
                            File(file, "restore_journal.json").exists() ||
                            File(file, "transfer_journal.json").exists()
                        )
                        if (!hasPendingRecovery && file.lastModified() < oneDayAgo) {
                            file.deleteRecursively()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
