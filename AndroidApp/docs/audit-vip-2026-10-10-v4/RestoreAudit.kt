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

// Production restore/storage; injected DAO only; isolated temporary game data.
private fun dao(record: GameRecord?, failCommit: Boolean = false): GameDao = Proxy.newProxyInstance(
    GameDao::class.java.classLoader, arrayOf(GameDao::class.java)
) { _, method, args -> when (method.name) {
    "getAllGamesList" -> listOfNotNull(record)
    "getGameById" -> record?.takeIf { args!![0] == it.gameId }
    "insertOrUpdate" -> { if (failCommit) error("Injected DB commit failure"); Unit }
    else -> error("Unexpected DAO method: ${method.name}")
} } as GameDao

private fun zip(root: File, id: String, type: String, hash: String, files: List<Pair<String, String>>, declared: JSONArray?): File {
    val manifest = JSONObject().put("schemaVersion", 1).put("gameId", id).put("consoleType", 2)
        .put("originalFileName", "remote.nes").put("displayName", "Audit").put("romChecksum", hash).put("backupType", type)
    if (declared != null) manifest.put("files", declared)
    return File(root, "bundle.zip").also { file -> ZipOutputStream(file.outputStream()).use { out ->
        for ((name, value) in listOf("manifest.json" to manifest.toString()) + files) {
            out.putNextEntry(ZipEntry(name)); out.write(value.toByteArray()); out.closeEntry()
        }
    } }
}
private fun checksum(root: File, storage: GameStorageManager, value: String): String = storage.calculateSha256(File(root, "hash-input").apply { writeText(value) })
private fun declarations(root: File, storage: GameStorageManager, files: List<Pair<String, String>>): JSONArray = JSONArray().also { array ->
    files.forEach { (name, value) -> array.put(JSONObject().put("path", name).put("size", value.toByteArray().size).put("sha256", checksum(root, storage, value))) }
}
private fun local(root: File, storage: GameStorageManager, createRom: Boolean = true): GameRecord {
    val rom = File(storage.getRomDir("local-game"), "local.nes")
    if (createRom) rom.writeText("ROM")
    return GameRecord("local-game", rom.path, localRomPath = rom.path, displayName = "Local", originalFileName = rom.name, consoleType = 2, addedAt = 1, contentSha256 = checksum(root, storage, "ROM"))
}
fun main() = runBlocking {
    var failed = 0
    suspend fun probe(name: String, body: suspend (File, GameStorageManager) -> Unit) {
        val root = Files.createTempDirectory("temulator-restore-v4-").toFile()
        try { body(root, GameStorageManager(root)); println("PASS: $name") }
        catch (error: Throwable) { failed++; println("FAIL: $name: ${error.message}") }
        finally { root.deleteRecursively() }
    }
    probe("T01 SAVE_ONLY without files[] must fail before writing save") { root, storage ->
        val record = local(root, storage)
        val bundle = zip(root, "remote-game", "SAVE_ONLY", record.contentSha256!!, listOf("Saves/remote.sav" to "UNCHECKED"), null)
        val result = GameRestoreService(storage, dao(record)).restoreFromBundle(bundle)
        check(result.isFailure) { "Accepted SAVE_ONLY without files[]; saves=${storage.getSaveDir(record.gameId).list()?.toList()}" }
    }
    probe("T02 canonical duplicate ZIP paths must be rejected") { root, storage ->
        val record = local(root, storage)
        val files = listOf("Saves/remote.sav" to "FIRST", "Saves/./remote.sav" to "LAST")
        val declared = declarations(root, storage, listOf("Saves/remote.sav" to "LAST"))
        val result = GameRestoreService(storage, dao(record)).restoreFromBundle(zip(root, "remote-game", "SAVE_ONLY", record.contentSha256!!, files, declared))
        check(result.isFailure) { "Accepted two ZIP entries resolving to same file" }
    }
    probe("T03 SAVE_ONLY must reject a missing actual ROM despite cached matching hash") { root, storage ->
        val record = local(root, storage, createRom = false)
        val files = listOf("Saves/remote.sav" to "SAVE")
        val result = GameRestoreService(storage, dao(record)).restoreFromBundle(zip(root, "remote-game", "SAVE_ONLY", record.contentSha256!!, files, declarations(root, storage, files)))
        check(result.isFailure) { "Restored saves against a nonexistent ROM" }
    }
    probe("T04 restore without usable DAO must not commit and report success") { root, storage ->
        val files = listOf("rom/remote.nes" to "ROM")
        val result = GameRestoreService(storage).restoreFromBundle(zip(root, "new-game", "ALL", checksum(root, storage, "ROM"), files, declarations(root, storage, files)))
        check(result.isFailure) { "Success and ROM committed with no database" }
    }
    probe("T05 different prefixes mapping to same state slot must not overwrite") { root, storage ->
        val record = local(root, storage)
        val files = listOf("SaveStates/remote_1.mss" to "SLOT_FIRST", "SaveStates/other_1.mss" to "SLOT_SECOND")
        val result = GameRestoreService(storage, dao(record)).restoreFromBundle(zip(root, "remote-game", "SAVE_ONLY", record.contentSha256!!, files, declarations(root, storage, files)))
        check(result.isFailure) { "Both valid checksummed states map to local_1.mss; final=${File(storage.getSaveStateDir(record.gameId), "local_1.mss").readText()}" }
    }
    probe("T06 new-game DB failure must remove newly committed files") { root, storage ->
        val files = listOf("rom/remote.nes" to "ROM", "Saves/remote.sav" to "SAVE")
        val result = GameRestoreService(storage, dao(null, failCommit = true)).restoreFromBundle(zip(root, "new-game", "ALL", checksum(root, storage, "ROM"), files, declarations(root, storage, files)))
        check(result.isFailure)
        check(!File(storage.getRomDir("new-game"), "remote.nes").exists()) { "DB insert failed but orphan ROM/save remains after rollback" }
    }
    println("V4 restore audit: $failed failures / 6 probes")
    check(failed == 0) { "Remaining restore regressions reproduced" }
}
