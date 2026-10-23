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
        val root = Files.createTempDirectory("temulator-restore-v3-").toFile()
        try { body(root, GameStorageManager(root)); println("PASS: $name") }
        catch (e: Throwable) { failed++; println("FAIL: $name: ${e.message}") }
        finally { root.deleteRecursively() }
    }
    fun entries(root: File, storage: GameStorageManager, files: Map<String, String>): JSONArray {
        val list = JSONArray()
        files.forEach { (path, contents) ->
            val temp = File(root, "checksum-input").apply { writeText(contents) }
            list.put(JSONObject().put("path", path).put("size", temp.length()).put("sha256", storage.calculateSha256(temp)))
        }
        return list
    }
    probe("N05 missing files manifest must be rejected") { root, storage ->
        val zip = bundle(root, "game-audit", "ALL", "", mapOf("rom/game.nes" to "ROM", "Saves/game.sav" to "UNCHECKED"))
        val result = GameRestoreService(storage).restoreFromBundle(zip)
        check(result.isFailure) { "Accepted missing files[] and committed unchecked save" }
    }
    probe("N06 mapped states must preserve slot numbers") { root, storage ->
        val rom = File(storage.getRomDir("game-local"), "local.nes").apply { writeText("ROM") }
        val record = GameRecord("game-local", rom.path, localRomPath = rom.path, displayName = "Local", originalFileName = "local.nes", consoleType = 2, addedAt = 1, contentSha256 = storage.calculateSha256(rom))
        val files = mapOf("SaveStates/remote_1.mss" to "SLOT_ONE", "SaveStates/remote_2.mss" to "SLOT_TWO")
        val zip = bundle(root, "game-remote", "SAVE_ONLY", record.contentSha256!!, files, entries(root, storage, files))
        GameRestoreService(storage, dao(record)).restoreFromBundle(zip).getOrThrow()
        val stateDir = storage.getSaveStateDir(record.gameId)
        check(File(stateDir, "local_1.mss").exists() && File(stateDir, "local_2.mss").exists()) {
            "Expected local_1/local_2; got ${stateDir.listFiles()?.map { it.name }}"
        }
        check(File(stateDir, "local_1.mss").readText() == "SLOT_ONE")
        check(File(stateDir, "local_2.mss").readText() == "SLOT_TWO")
    }
    probe("N07 conflicting remote ID must not overwrite a different local ROM") { root, storage ->
        val rom = File(storage.getRomDir("same-game-id"), "game.nes").apply { writeText("ORIGINAL_ROM") }
        val record = GameRecord("same-game-id", rom.path, localRomPath = rom.path, displayName = "Local", originalFileName = "game.nes", consoleType = 2, addedAt = 1, contentSha256 = storage.calculateSha256(rom))
        val incoming = File(root, "incoming.nes").apply { writeText("DIFFERENT_ROM") }
        val files = mapOf("rom/game.nes" to "DIFFERENT_ROM")
        val zip = bundle(root, record.gameId, "ALL", storage.calculateSha256(incoming), files, entries(root, storage, files))
        GameRestoreService(storage, dao(record)).restoreFromBundle(zip)
        check(rom.readText() == "ORIGINAL_ROM") { "Different ROM overwrote existing game because manifest ID collided" }
    }
    probe("N08 equal checksum with wrong console must not accept SAVE_ONLY") { root, storage ->
        val rom = File(storage.getRomDir("game-local"), "game.nes").apply { writeText("ROM") }
        val record = GameRecord("game-local", rom.path, localRomPath = rom.path, displayName = "Local", originalFileName = "game.nes", consoleType = 1, addedAt = 1, contentSha256 = storage.calculateSha256(rom))
        val files = mapOf("Saves/game.sav" to "WRONG_CONSOLE_SAVE")
        val zip = bundle(root, "game-remote", "SAVE_ONLY", record.contentSha256!!, files, entries(root, storage, files)) // bundle console=2
        val result = GameRestoreService(storage, dao(record)).restoreFromBundle(zip)
        check(result.isFailure) { "Accepted checksum match despite console mismatch" }
    }
    println("V3 restore audit: $failed failures / 4 probes")
    check(failed == 0) { "Remaining restore regressions reproduced" }
}
