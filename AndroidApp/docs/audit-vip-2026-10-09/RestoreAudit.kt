package audit

import com.temulator.app.cloud.GameRestoreService
import com.temulator.app.data.db.GameDao
import com.temulator.app.data.db.GameRecord
import com.temulator.app.storage.GameStorageManager
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Real restore/storage implementation; fake DAO only, no Android device or real Room.
private fun dao(record: GameRecord, failCommit: Boolean = false): GameDao = Proxy.newProxyInstance(
    GameDao::class.java.classLoader, arrayOf(GameDao::class.java)
) { _, method, args ->
    when (method.name) {
        "getAllGamesList" -> listOf(record)
        "getGameById" -> if (args!![0] == record.gameId) record else null
        "insertOrUpdate" -> { if (failCommit) error("Injected DB commit failure"); Unit }
        else -> error("Unexpected DAO method: ${method.name}")
    }
} as GameDao

private fun bundle(root: File, id: String, type: String, checksum: String, files: Map<String, String>, manifestFiles: JSONArray? = null): File {
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

fun main() = runBlocking {
    var failed = 0
    suspend fun probe(name: String, body: suspend (File, GameStorageManager) -> Unit) {
        val root = Files.createTempDirectory("temulator-restore-audit-").toFile()
        try { body(root, GameStorageManager(root)); println("PASS: $name") }
        catch (e: Throwable) { failed++; println("FAIL: $name: ${e.message}") }
        finally { root.deleteRecursively() } // Only the freshly created probe directory.
    }

    probe("R06 unlisted save must be rejected") { root, storage ->
        val rom = File(root, "reference.nes").apply { writeText("ROM") }
        val hash = storage.calculateSha256(rom)
        val entries = JSONArray().put(JSONObject().put("path", "rom/game.nes").put("size", 3).put("sha256", hash))
        val zip = bundle(root, "game-audit", "ALL", hash, mapOf("rom/game.nes" to "ROM", "Saves/game.sav" to "UNVERIFIED_SAVE"), entries)
        val result = GameRestoreService(storage).restoreFromBundle(zip)
        check(result.isFailure) { "Unlisted save accepted and written: ${File(storage.getSaveDir("game-audit"), "game.sav").readText()}" }
    }
    probe("R07 SAVE_ONLY must reject a different ROM checksum") { root, storage ->
        val record = GameRecord("game-local", "/existing/game.nes", displayName = "Existing", originalFileName = "game.nes", consoleType = 2, addedAt = 1, contentSha256 = "a".repeat(64))
        val zip = bundle(root, record.gameId, "SAVE_ONLY", "b".repeat(64), mapOf("Saves/game.sav" to "WRONG_ROM_SAVE"))
        val result = GameRestoreService(storage, dao(record)).restoreFromBundle(zip)
        check(result.isFailure) { "Different checksum accepted through gameId fallback" }
    }
    probe("R08 rollback must restore resolved local ID, not remote ID") { root, storage ->
        val record = GameRecord("game-local", "/existing/game.nes", displayName = "Existing", originalFileName = "game.nes", consoleType = 2, addedAt = 1, contentSha256 = "c".repeat(64))
        val oldSave = File(storage.getSaveDir(record.gameId), "game.sav").apply { writeText("OLD_SAVE") }
        val zip = bundle(root, "game-remote", "SAVE_ONLY", record.contentSha256!!, mapOf("Saves/game.sav" to "NEW_SAVE"))
        val result = GameRestoreService(storage, dao(record, failCommit = true)).restoreFromBundle(zip)
        check(result.isFailure)
        check(oldSave.readText() == "OLD_SAVE") { "Local save after failed restore: ${oldSave.readText()}; rollback was copied to remote ID" }
    }
    probe("R09 ALL must update an existing record to the restored ROM path") { root, storage ->
        val rom = File(root, "reference.nes").apply { writeText("ROM") }
        val record = GameRecord("game-local", "/missing/old.nes", localRomPath = "/missing/old.nes", displayName = "Existing", originalFileName = "old.nes", consoleType = 2, addedAt = 1, contentSha256 = storage.calculateSha256(rom))
        val zip = bundle(root, "game-remote", "ALL", record.contentSha256!!, mapOf("rom/game.nes" to "ROM"))
        val result = GameRestoreService(storage, dao(record)).restoreFromBundle(zip).getOrThrow()
        check(File(result.localRomPath!!).isFile) { "Restore succeeded but returned stale path: ${result.localRomPath}" }
    }
    probe("R10 unknown backupType must be rejected") { root, storage ->
        val zip = bundle(root, "game-audit", "UNSUPPORTED", "", mapOf("Saves/game.sav" to "SAVE"))
        val result = GameRestoreService(storage).restoreFromBundle(zip)
        check(result.isFailure) { "Unknown type accepted without a ROM" }
    }
    println("Restore audit: $failed failures / 5 probes")
    check(failed == 0) { "Remaining restore regressions reproduced" }
}
