package com.temulator.app.cloud

import com.temulator.app.data.db.GameDao
import com.temulator.app.data.db.GameRecord
import com.temulator.app.storage.GameStorageManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Proxy
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GameRestoreSecurityTest {

    private lateinit var tempRoot: File
    private lateinit var storage: GameStorageManager
    private lateinit var restoreService: GameRestoreService

    @Before
    fun setUp() {
        tempRoot = File(System.getProperty("java.io.tmpdir"), "temulator_test_" + System.currentTimeMillis())
        tempRoot.mkdirs()
        storage = GameStorageManager(tempRoot)
        restoreService = GameRestoreService(storage, mockDao(null))
    }

    @After
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    @Test
    fun testZipEntryPathTraversalBlockedByRealService() = runBlocking {
        // F10: Zip entry traversal attempt (../../dangerous_file.txt)
        val bundle = File(tempRoot, "traversal_entry.zip")
        ZipOutputStream(FileOutputStream(bundle)).use { zos ->
            zos.putNextEntry(ZipEntry("../../dangerous_override.txt"))
            zos.write("malicious_payload".toByteArray())
            zos.closeEntry()
        }

        val result = restoreService.restoreFromBundle(bundle)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is SecurityException)
        assertTrue(ex?.message?.contains("Malicious path") == true || ex?.message?.contains("Path traversal") == true)

        // Ensure no file was created outside staging/games
        assertFalse(File(tempRoot, "dangerous_override.txt").exists())
    }

    @Test
    fun testManifestGameIdTraversalBlockedByRealService() = runBlocking {
        // F10: Manifest gameId traversal attempt ("../escaped_directory")
        val bundle = File(tempRoot, "traversal_manifest.zip")
        ZipOutputStream(FileOutputStream(bundle)).use { zos ->
            // Normal safe zip entries
            zos.putNextEntry(ZipEntry("manifest.json"))
            val manifest = JSONObject().apply {
                put("schemaVersion", 1)
                put("gameId", "../escaped_directory")
                put("displayName", "Malicious Game")
                put("originalFileName", "game.nes")
                put("consoleType", 2)
                put("romChecksum", "")
                put("backupType", "ALL")
            }.toString()
            zos.write(manifest.toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("rom/game.nes"))
            zos.write("rom_bytes".toByteArray())
            zos.closeEntry()
        }

        val result = restoreService.restoreFromBundle(bundle)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is SecurityException || ex is IllegalArgumentException)

        // Ensure no directory or files were created outside the games root
        assertFalse(File(tempRoot, "escaped_directory").exists())
        assertFalse(File(tempRoot, "games/../escaped_directory").exists())
    }

    @Test
    fun testCorruptedRomChecksumRejectsRestore() = runBlocking {
        // F11: Bundle claims a specific checksum, but actual ROM bytes do not match
        val validGameId = "valid-game-uuid-1111"
        val bundle = File(tempRoot, "bad_checksum.zip")

        ZipOutputStream(FileOutputStream(bundle)).use { zos ->
            val romBytes = "ACTUAL_ROM_CONTENT".toByteArray()
            val fakeChecksum = "0000000000000000000000000000000000000000000000000000000000000000"

            val filesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("path", "rom/game.nes")
                    put("size", romBytes.size.toLong())
                    put("sha256", fakeChecksum)
                })
            }

            val manifest = JSONObject().apply {
                put("schemaVersion", 1)
                put("gameId", validGameId)
                put("displayName", "Test Checksum")
                put("originalFileName", "game.nes")
                put("consoleType", 2)
                put("romChecksum", fakeChecksum)
                put("backupType", "ALL")
                put("files", filesArray)
            }.toString()

            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("rom/game.nes"))
            zos.write(romBytes)
            zos.closeEntry()
        }

        val result = restoreService.restoreFromBundle(bundle)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is IllegalStateException)
        assertTrue(ex?.message?.contains("checksum mismatch", ignoreCase = true) == true)
    }

    @Test
    fun testUnsupportedSchemaVersionIsRejected() = runBlocking {
        // F11: Unsupported schema version (> 1)
        val bundle = File(tempRoot, "unsupported_schema.zip")
        ZipOutputStream(FileOutputStream(bundle)).use { zos ->
            val manifest = JSONObject().apply {
                put("schemaVersion", 999) // Future unsupported schema
                put("gameId", "game-schema-999")
                put("displayName", "Future Backup")
                put("originalFileName", "game.nes")
                put("consoleType", 2)
            }.toString()

            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toByteArray())
            zos.closeEntry()
        }

        val result = restoreService.restoreFromBundle(bundle)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is IllegalStateException)
        assertTrue(ex?.message?.contains("Unsupported backup schema version") == true)
    }

    @Test
    fun testValidRoundTripBundleRestoresAllFiles() = runBlocking {
        // F11: Round-trip valid bundle extraction (ROM + SRAM + States)
        val gameId = "game-uuid-round-trip-001"
        val romContent = "GENUINE_ROM_BYTES_FOR_NES"
        val saveContent = "SAVED_PROGRESS_BATTERY_SRAM"
        val stateContent = "SAVED_STATE_SLOT_1_DATA"

        val romFile = File.createTempFile("temp_rom", ".nes")
        romFile.writeBytes(romContent.toByteArray())
        val romSha = storage.calculateSha256(romFile)
        romFile.delete()

        val saveFile = File.createTempFile("temp_save", ".sav")
        saveFile.writeBytes(saveContent.toByteArray())
        val saveSha = storage.calculateSha256(saveFile)
        saveFile.delete()

        val stateFile = File.createTempFile("temp_state", ".mss")
        stateFile.writeBytes(stateContent.toByteArray())
        val stateSha = storage.calculateSha256(stateFile)
        stateFile.delete()

        val bundle = File(tempRoot, "valid_bundle.zip")
        ZipOutputStream(FileOutputStream(bundle)).use { zos ->
            val filesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("path", "rom/super_mario.nes")
                    put("size", romContent.toByteArray().size.toLong())
                    put("sha256", romSha)
                })
                put(JSONObject().apply {
                    put("path", "Saves/super_mario.sav")
                    put("size", saveContent.toByteArray().size.toLong())
                    put("sha256", saveSha)
                })
                put(JSONObject().apply {
                    put("path", "SaveStates/super_mario.mss")
                    put("size", stateContent.toByteArray().size.toLong())
                    put("sha256", stateSha)
                })
            }

            val manifest = JSONObject().apply {
                put("schemaVersion", 1)
                put("gameId", gameId)
                put("displayName", "Super Mario Bros")
                put("originalFileName", "super_mario.nes")
                put("consoleType", 2)
                put("romChecksum", romSha)
                put("backupType", "ALL")
                put("files", filesArray)
            }.toString()

            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("rom/super_mario.nes"))
            zos.write(romContent.toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("Saves/super_mario.sav"))
            zos.write(saveContent.toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("SaveStates/super_mario.mss"))
            zos.write(stateContent.toByteArray())
            zos.closeEntry()
        }

        val result = restoreService.restoreFromBundle(bundle)
        assertTrue(result.isSuccess)
        val record = result.getOrNull()
        assertNotNull(record)
        assertEquals(gameId, record?.gameId)
        assertEquals("Super Mario Bros", record?.displayName)

        // Verify filesystem content was committed to the correct isolated game directories
        val restoredRom = File(storage.getRomDir(gameId), "super_mario.nes")
        val restoredSave = File(storage.getSaveDir(gameId), "super_mario.sav")
        val restoredState = File(storage.getSaveStateDir(gameId), "super_mario.mss")

        assertTrue(restoredRom.exists())
        assertEquals(romContent, restoredRom.readText())
        assertTrue(restoredSave.exists())
        assertEquals(saveContent, restoredSave.readText())
        assertTrue(restoredState.exists())
        assertEquals(stateContent, restoredState.readText())
    }

    @Test
    fun testRollbackPreservesOriginalSavesOnFailedRestore() = runBlocking {
        // F11: If restore fails partway, existing save data must NOT be destroyed
        val gameId = "game-rollback-test"

        // Setup existing save data in local game folder
        val saveDir = storage.getSaveDir(gameId)
        val originalSave = File(saveDir, "game.sav")
        originalSave.writeText("CRITICAL_USER_PROGRESS_DO_NOT_LOSE")

        // Create corrupt bundle that passes zip extraction but fails file SHA verification
        val bundle = File(tempRoot, "corrupted_at_verification.zip")
        ZipOutputStream(FileOutputStream(bundle)).use { zos ->
            val filesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("path", "Saves/game.sav")
                    put("size", 100L) // Incorrect size
                    put("sha256", "wrong_hash")
                })
            }
            val manifest = JSONObject().apply {
                put("schemaVersion", 1)
                put("gameId", gameId)
                put("displayName", "Corrupt Restore")
                put("originalFileName", "game.nes")
                put("consoleType", 2)
                put("backupType", "SAVE_ONLY")
                put("files", filesArray)
            }.toString()

            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("Saves/game.sav"))
            zos.write("NEW_PARTIAL_OVERWRITE".toByteArray())
            zos.closeEntry()
        }

        val result = restoreService.restoreFromBundle(bundle)
        assertTrue(result.isFailure)

        // Original save MUST still exist with original content!
        assertTrue(originalSave.exists())
        assertEquals("CRITICAL_USER_PROGRESS_DO_NOT_LOSE", originalSave.readText())
    }

    @Test
    fun testSaveOnlyRestoreWithoutRomFailsGracefully() = runBlocking {
        // F12: SAVE_ONLY restore when device lacks base ROM
        val gameId = "game-save-only-no-rom"
        val bundle = File(tempRoot, "save_only_bundle.zip")

        ZipOutputStream(FileOutputStream(bundle)).use { zos ->
            val sramBytes = "sram_content".toByteArray()
            val tempF = File(tempRoot, "tmp_sram").apply { writeBytes(sramBytes) }
            val sramSha = storage.calculateSha256(tempF)
            tempF.delete()

            val filesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("path", "Saves/game.sav")
                    put("size", sramBytes.size.toLong())
                    put("sha256", sramSha)
                })
            }

            val manifest = JSONObject().apply {
                put("schemaVersion", 1)
                put("gameId", gameId)
                put("displayName", "Save Only Game")
                put("originalFileName", "game.nes")
                put("consoleType", 2)
                put("romChecksum", "abc123sha256ofMissingRom")
                put("backupType", "SAVE_ONLY")
                put("files", filesArray)
            }.toString()

            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("Saves/game.sav"))
            zos.write(sramBytes)
            zos.closeEntry()
        }

        val result = restoreService.restoreFromBundle(bundle)
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex is IllegalStateException)
        assertTrue(ex?.message?.contains("ROM with checksum") == true || ex?.message?.contains("not found on this device") == true)
    }

    private fun mockDao(record: GameRecord?, failCommit: Boolean = false): GameDao = Proxy.newProxyInstance(
        GameDao::class.java.classLoader, arrayOf(GameDao::class.java)
    ) { _, method, args ->
        when (method.name) {
            "getAllGamesList" -> listOfNotNull(record)
            "getGameById" -> record?.takeIf { args!![0] == it.gameId }
            "insertOrUpdate" -> { if (failCommit) error("Injected DB commit failure"); Unit }
            else -> error("Unexpected DAO method: ${method.name}")
        }
    } as GameDao

    private fun createTestBundle(
        root: File,
        id: String,
        type: String,
        checksum: String,
        files: Map<String, String>,
        manifestFiles: JSONArray? = null
    ): File {
        val manifest = JSONObject().put("schemaVersion", 1).put("gameId", id)
            .put("displayName", "Audit game").put("originalFileName", "game.nes")
            .put("consoleType", 2).put("romChecksum", checksum).put("backupType", type)
        if (manifestFiles != null) manifest.put("files", manifestFiles)
        return File(root, "bundle.zip").also { zip ->
            ZipOutputStream(zip.outputStream()).use { out ->
                for ((name, value) in mapOf("manifest.json" to manifest.toString()) + files) {
                    out.putNextEntry(ZipEntry(name)); out.write(value.toByteArray()); out.closeEntry()
                }
            }
        }
    }

    @Test
    fun testR06UnlistedSaveMustBeRejected() = runBlocking {
        val rom = File(tempRoot, "reference.nes").apply { writeText("ROM") }
        val hash = storage.calculateSha256(rom)
        val entries = JSONArray().put(JSONObject().put("path", "rom/game.nes").put("size", 3).put("sha256", hash))
        val zip = createTestBundle(tempRoot, "game-audit", "ALL", hash, mapOf("rom/game.nes" to "ROM", "Saves/game.sav" to "UNVERIFIED_SAVE"), entries)
        val result = GameRestoreService(storage).restoreFromBundle(zip)
        assertTrue("Unlisted save must be rejected", result.isFailure)
    }

    @Test
    fun testR07SaveOnlyMustRejectDifferentRomChecksum() = runBlocking {
        val record = GameRecord("game-local", "/existing/game.nes", displayName = "Existing", originalFileName = "game.nes", consoleType = 2, addedAt = 1, contentSha256 = "a".repeat(64))
        val zip = createTestBundle(tempRoot, record.gameId, "SAVE_ONLY", "b".repeat(64), mapOf("Saves/game.sav" to "WRONG_ROM_SAVE"))
        val result = GameRestoreService(storage, mockDao(record)).restoreFromBundle(zip)
        assertTrue("Different checksum must be rejected for SAVE_ONLY", result.isFailure)
    }

    @Test
    fun testR08RollbackMustRestoreResolvedLocalIdNotRemoteId() = runBlocking {
        val record = GameRecord("game-local", "/existing/game.nes", displayName = "Existing", originalFileName = "game.nes", consoleType = 2, addedAt = 1, contentSha256 = "c".repeat(64))
        val oldSave = File(storage.getSaveDir(record.gameId), "game.sav").apply { writeText("OLD_SAVE") }
        val zip = createTestBundle(tempRoot, "game-remote", "SAVE_ONLY", record.contentSha256!!, mapOf("Saves/game.sav" to "NEW_SAVE"))
        val result = GameRestoreService(storage, mockDao(record, failCommit = true)).restoreFromBundle(zip)
        assertTrue(result.isFailure)
        assertEquals("Local save after failed restore must be preserved", "OLD_SAVE", oldSave.readText())
    }

    @Test
    fun testR09AllMustUpdateExistingRecordToRestoredRomPath() = runBlocking {
        val rom = File(tempRoot, "reference.nes").apply { writeText("ROM") }
        val record = GameRecord("game-local", "/missing/old.nes", localRomPath = "/missing/old.nes", displayName = "Existing", originalFileName = "old.nes", consoleType = 2, addedAt = 1, contentSha256 = storage.calculateSha256(rom))
        val zip = createTestBundle(tempRoot, "game-remote", "ALL", record.contentSha256!!, mapOf("rom/game.nes" to "ROM"))
        val result = GameRestoreService(storage, mockDao(record)).restoreFromBundle(zip).getOrThrow()
        assertTrue("Restore succeeded and returned valid local ROM path", File(result.localRomPath!!).isFile)
    }

    @Test
    fun testR10UnknownBackupTypeMustBeRejected() = runBlocking {
        val zip = createTestBundle(tempRoot, "game-audit", "UNSUPPORTED", "", mapOf("Saves/game.sav" to "SAVE"))
        val result = GameRestoreService(storage).restoreFromBundle(zip)
        assertTrue("Unknown type must be rejected", result.isFailure)
    }

    @Test
    fun testN05MissingFilesManifestMustBeRejected() = runBlocking {
        val zip = createTestBundle(tempRoot, "game-audit", "ALL", "", mapOf("rom/game.nes" to "ROM", "Saves/game.sav" to "UNCHECKED"))
        val result = GameRestoreService(storage).restoreFromBundle(zip)
        assertTrue("Missing files manifest or missing romChecksum must be rejected", result.isFailure)
    }

    @Test
    fun testN06MappedStatesMustPreserveSlotNumbers() = runBlocking {
        val rom = File(storage.getRomDir("game-local"), "local.nes").apply { writeText("ROM") }
        val record = GameRecord("game-local", rom.path, localRomPath = rom.path, displayName = "Local", originalFileName = "local.nes", consoleType = 2, addedAt = 1, contentSha256 = storage.calculateSha256(rom))
        val files = mapOf("SaveStates/remote_1.mss" to "SLOT_ONE", "SaveStates/remote_2.mss" to "SLOT_TWO")
        val entries = JSONArray()
        files.forEach { (path, contents) ->
            val temp = File(tempRoot, "checksum-input").apply { writeText(contents) }
            entries.put(JSONObject().put("path", path).put("size", temp.length()).put("sha256", storage.calculateSha256(temp)))
        }
        val zip = createTestBundle(tempRoot, "game-remote", "SAVE_ONLY", record.contentSha256!!, files, entries)
        GameRestoreService(storage, mockDao(record)).restoreFromBundle(zip).getOrThrow()
        val stateDir = storage.getSaveStateDir(record.gameId)
        assertTrue("Expected local_1.mss", File(stateDir, "local_1.mss").exists())
        assertTrue("Expected local_2.mss", File(stateDir, "local_2.mss").exists())
        assertEquals("SLOT_ONE", File(stateDir, "local_1.mss").readText())
        assertEquals("SLOT_TWO", File(stateDir, "local_2.mss").readText())
    }

    @Test
    fun testN07ConflictingRemoteIdMustNotOverwriteDifferentLocalRom() = runBlocking {
        val rom = File(storage.getRomDir("same-game-id"), "game.nes").apply { writeText("ORIGINAL_ROM") }
        val record = GameRecord("same-game-id", rom.path, localRomPath = rom.path, displayName = "Local", originalFileName = "game.nes", consoleType = 2, addedAt = 1, contentSha256 = storage.calculateSha256(rom))
        val incoming = File(tempRoot, "incoming.nes").apply { writeText("DIFFERENT_ROM") }
        val files = mapOf("rom/game.nes" to "DIFFERENT_ROM")
        val entries = JSONArray().put(JSONObject().put("path", "rom/game.nes").put("size", incoming.length()).put("sha256", storage.calculateSha256(incoming)))
        val zip = createTestBundle(tempRoot, record.gameId, "ALL", storage.calculateSha256(incoming), files, entries)
        GameRestoreService(storage, mockDao(record)).restoreFromBundle(zip)
        assertEquals("Original ROM must not be overwritten", "ORIGINAL_ROM", rom.readText())
    }

    @Test
    fun testN08EqualChecksumWithWrongConsoleMustNotAcceptSaveOnly() = runBlocking {
        val rom = File(storage.getRomDir("game-local"), "game.nes").apply { writeText("ROM") }
        val record = GameRecord("game-local", rom.path, localRomPath = rom.path, displayName = "Local", originalFileName = "game.nes", consoleType = 1, addedAt = 1, contentSha256 = storage.calculateSha256(rom))
        val files = mapOf("Saves/game.sav" to "WRONG_CONSOLE_SAVE")
        val temp = File(tempRoot, "save-input").apply { writeText("WRONG_CONSOLE_SAVE") }
        val entries = JSONArray().put(JSONObject().put("path", "Saves/game.sav").put("size", temp.length()).put("sha256", storage.calculateSha256(temp)))
        val zip = createTestBundle(tempRoot, "game-remote", "SAVE_ONLY", record.contentSha256!!, files, entries) // bundle consoleType=2, local=1
        val result = GameRestoreService(storage, mockDao(record)).restoreFromBundle(zip)
        assertTrue("Different consoleType must be rejected for SAVE_ONLY", result.isFailure)
    }

    @Test
    fun testT01SaveOnlyWithoutFilesMustFail() = runBlocking {
        val rom = File(storage.getRomDir("local-game"), "local.nes").apply { writeText("ROM") }
        val record = GameRecord("local-game", rom.path, localRomPath = rom.path, displayName = "Local", originalFileName = rom.name, consoleType = 2, addedAt = 1, contentSha256 = storage.calculateSha256(rom))
        val bundle = createTestBundle(tempRoot, "remote-game", "SAVE_ONLY", record.contentSha256!!, mapOf("Saves/remote.sav" to "UNCHECKED"), null)
        val result = GameRestoreService(storage, mockDao(record)).restoreFromBundle(bundle)
        assertTrue("SAVE_ONLY without files[] must fail", result.isFailure)
    }

    @Test
    fun testT02CanonicalDuplicateZipPathsMustBeRejected() = runBlocking {
        val rom = File(storage.getRomDir("local-game"), "local.nes").apply { writeText("ROM") }
        val record = GameRecord("local-game", rom.path, localRomPath = rom.path, displayName = "Local", originalFileName = rom.name, consoleType = 2, addedAt = 1, contentSha256 = storage.calculateSha256(rom))
        val files = listOf("Saves/remote.sav" to "FIRST", "Saves/./remote.sav" to "LAST")
        val declared = JSONArray().put(JSONObject().put("path", "Saves/remote.sav").put("size", 4L).put("sha256", storage.calculateSha256(File(tempRoot, "hash").apply { writeText("LAST") })))
        val manifest = JSONObject().put("schemaVersion", 1).put("gameId", "remote-game").put("consoleType", 2)
            .put("originalFileName", "remote.nes").put("displayName", "Audit").put("romChecksum", record.contentSha256!!).put("backupType", "SAVE_ONLY").put("files", declared)
        val zipFile = File(tempRoot, "dup.zip").also { f ->
            ZipOutputStream(f.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("manifest.json")); out.write(manifest.toString().toByteArray()); out.closeEntry()
                for ((name, value) in files) {
                    out.putNextEntry(ZipEntry(name)); out.write(value.toByteArray()); out.closeEntry()
                }
            }
        }
        val result = GameRestoreService(storage, mockDao(record)).restoreFromBundle(zipFile)
        assertTrue("Canonical duplicate zip entries must be rejected", result.isFailure)
    }

    @Test
    fun testT03SaveOnlyMustRejectMissingActualRom() = runBlocking {
        val rom = File(storage.getRomDir("local-game"), "local.nes") // not created!
        val record = GameRecord("local-game", rom.path, localRomPath = rom.path, displayName = "Local", originalFileName = rom.name, consoleType = 2, addedAt = 1, contentSha256 = "c".repeat(64))
        val files = mapOf("Saves/remote.sav" to "SAVE")
        val declared = JSONArray().put(JSONObject().put("path", "Saves/remote.sav").put("size", 4L).put("sha256", storage.calculateSha256(File(tempRoot, "hash2").apply { writeText("SAVE") })))
        val zipFile = createTestBundle(tempRoot, "remote-game", "SAVE_ONLY", record.contentSha256!!, files, declared)
        val result = GameRestoreService(storage, mockDao(record)).restoreFromBundle(zipFile)
        assertTrue("SAVE_ONLY with missing actual ROM file must fail", result.isFailure)
    }

    @Test
    fun testT04RestoreWithoutUsableDaoMustFail() = runBlocking {
        val files = mapOf("rom/remote.nes" to "ROM")
        val hash = storage.calculateSha256(File(tempRoot, "h").apply { writeText("ROM") })
        val declared = JSONArray().put(JSONObject().put("path", "rom/remote.nes").put("size", 3L).put("sha256", hash))
        val zipFile = createTestBundle(tempRoot, "new-game", "ALL", hash, files, declared)
        val result = GameRestoreService(storage, null).restoreFromBundle(zipFile)
        assertTrue("Restore without usable DAO must fail", result.isFailure)
    }

    @Test
    fun testT05DifferentPrefixesMappingToSameStateSlotMustNotOverwrite() = runBlocking {
        val rom = File(storage.getRomDir("local-game"), "local.nes").apply { writeText("ROM") }
        val record = GameRecord("local-game", rom.path, localRomPath = rom.path, displayName = "Local", originalFileName = "local.nes", consoleType = 2, addedAt = 1, contentSha256 = storage.calculateSha256(rom))
        val files = mapOf("SaveStates/remote_1.mss" to "SLOT_FIRST", "SaveStates/other_1.mss" to "SLOT_SECOND")
        val entries = JSONArray()
        files.forEach { (path, contents) ->
            val temp = File(tempRoot, "inp").apply { writeText(contents) }
            entries.put(JSONObject().put("path", path).put("size", temp.length()).put("sha256", storage.calculateSha256(temp)))
        }
        val zipFile = createTestBundle(tempRoot, "remote-game", "SAVE_ONLY", record.contentSha256!!, files, entries)
        val result = GameRestoreService(storage, mockDao(record)).restoreFromBundle(zipFile)
        assertTrue("Collision mapping to same slot must be rejected", result.isFailure)
    }

    @Test
    fun testT06NewGameDbFailureMustRemoveNewlyCommittedFiles() = runBlocking {
        val files = mapOf("rom/remote.nes" to "ROM", "Saves/remote.sav" to "SAVE")
        val hash = storage.calculateSha256(File(tempRoot, "h2").apply { writeText("ROM") })
        val entries = JSONArray()
        files.forEach { (path, contents) ->
            val temp = File(tempRoot, "inp2").apply { writeText(contents) }
            entries.put(JSONObject().put("path", path).put("size", temp.length()).put("sha256", storage.calculateSha256(temp)))
        }
        val zipFile = createTestBundle(tempRoot, "new-game", "ALL", hash, files, entries)
        val result = GameRestoreService(storage, mockDao(null, failCommit = true)).restoreFromBundle(zipFile)
        assertTrue(result.isFailure)
        assertFalse("New game ROM must be cleaned up on commit failure", File(storage.getRomDir("new-game"), "remote.nes").exists())
    }
}
