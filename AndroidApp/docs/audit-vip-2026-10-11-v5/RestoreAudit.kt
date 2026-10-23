package audit
import com.temulator.app.cloud.GameSnapshotService
import com.temulator.app.cloud.GameRestoreService
import com.temulator.app.data.db.GameDao
import com.temulator.app.data.db.GameRecord
import com.temulator.app.storage.GameStorageManager
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.lang.reflect.Proxy
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking

fun main() {
    var failed = 0
    fun probe(name: String, body: (File, GameStorageManager) -> Unit) {
        val root = Files.createTempDirectory("temulator-snapshot-v5-").toFile()
        try { body(root, GameStorageManager(root)); println("PASS: $name") }
        catch (e: Throwable) { failed++; println("FAIL: $name: ${e.message}") }
        finally { root.deleteRecursively() }
    }
    probe("S01 ALL snapshot must hash actual ROM instead of stale DB checksum") { root, storage ->
        val rom = File(storage.getRomDir("game-audit"), "game.nes").apply { writeText("ORIGINAL") }
        val stale = storage.calculateSha256(rom); rom.writeText("CURRENT_ROM")
        val record = GameRecord("game-audit", rom.path, localRomPath = rom.path, displayName = "Audit", originalFileName = rom.name, consoleType = 2, addedAt = 1, contentSha256 = stale)
        val result = GameSnapshotService(storage).createSnapshot(record, "ALL") ?: error("Snapshot failed")
        val manifest = JSONObject(result.manifestJson)
        check(manifest.getString("romChecksum") == storage.calculateSha256(rom)) { "Backup reports success but ROM checksum describes old bytes" }
    }
    probe("S02 SAVE_ONLY snapshot must resolve a valid ROM checksum when DB hash is absent") { root, storage ->
        val rom = File(storage.getRomDir("game-audit"), "game.nes").apply { writeText("ROM") }
        File(storage.getSaveDir("game-audit"), "game.sav").writeText("SAVE")
        val record = GameRecord("game-audit", rom.path, localRomPath = rom.path, displayName = "Audit", originalFileName = rom.name, consoleType = 2, addedAt = 1, contentSha256 = null)
        val result = GameSnapshotService(storage).createSnapshot(record, "SAVE_ONLY") ?: error("Readable local ROM should allow a snapshot")
        check(JSONObject(result.manifestJson).getString("romChecksum") == storage.calculateSha256(rom)) { "Created SAVE_ONLY backup with empty checksum; restore will reject it" }
    }
    probe("S03 snapshot must use the ROM referenced by GameRecord instead of another file in rom directory") { root, storage ->
        File(storage.getRomDir("game-audit"), "old.nes").writeText("OLD_UNRELATED_BYTES")
        val chosen = File(storage.getRomDir("game-audit"), "chosen.nes").apply { writeText("CHOSEN_ROM") }
        val record = GameRecord("game-audit", chosen.path, localRomPath = chosen.path, displayName = "Audit", originalFileName = chosen.name, consoleType = 2, addedAt = 1, contentSha256 = storage.calculateSha256(chosen))
        val result = GameSnapshotService(storage).createSnapshot(record, "ALL") ?: error("Snapshot failed")
        val manifest = JSONObject(result.manifestJson)
        check(manifest.getString("romChecksum") == storage.calculateSha256(chosen)) { "Backed up another ROM despite an explicit valid localRomPath" }
        check(manifest.getJSONArray("files").getJSONObject(0).getString("path") == "rom/chosen.nes")
    }
    probe("S04 explicit valid localRomPath outside isolated folder must beat an unrelated isolated ROM") { root, storage ->
        File(storage.getRomDir("game-audit"), "stale.nes").writeText("UNRELATED_ROM")
        val chosen = File(root, "current.nes").apply { writeText("CURRENT_ROM") }
        val record = GameRecord("game-audit", chosen.path, localRomPath = chosen.path, displayName = "Audit", originalFileName = chosen.name, consoleType = 2, addedAt = 1, contentSha256 = storage.calculateSha256(chosen))
        val result = GameSnapshotService(storage).createSnapshot(record, "ALL") ?: error("Snapshot failed")
        check(JSONObject(result.manifestJson).getString("romChecksum") == storage.calculateSha256(chosen)) { "Snapshot chose stale isolated ROM instead of explicit current localRomPath" }
    }
    probe("S05 archive ROM roundtrip must preserve native inner ROM save/state basename") { root, storage ->
        val archive = File(storage.getRomDir("archive-game"), "outer.zip")
        val nesImage = ByteArray(16 + 16_384)
        nesImage[0] = 0x4e; nesImage[1] = 0x45; nesImage[2] = 0x53; nesImage[3] = 0x1a
        nesImage[4] = 1; nesImage[6] = 2 // iNES mapper 0, one PRG bank, CHR RAM, battery
        ZipOutputStream(archive.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("folder/inner.nes")); out.write(nesImage); out.closeEntry()
        }
        File(storage.getSaveDir("archive-game"), "inner.sav").writeText("BATTERY")
        File(storage.getSaveStateDir("archive-game"), "inner_1.mss").writeText("STATE_SLOT_1")
        val record = GameRecord("archive-game", archive.path, localRomPath = archive.path, displayName = "Archive", originalFileName = archive.name, consoleType = 2, addedAt = 1, contentSha256 = storage.calculateSha256(archive))
        val bundle = GameSnapshotService(storage).createSnapshot(record, "ALL") ?: error("Archive snapshot failed")
        val target = GameStorageManager(File(root, "target"))
        val dao = Proxy.newProxyInstance(GameDao::class.java.classLoader, arrayOf(GameDao::class.java)) { _, method, _ ->
            when (method.name) { "getAllGamesList" -> emptyList<GameRecord>(); "getGameById" -> null; "insertOrUpdate" -> Unit; else -> error("Unexpected DAO call ${method.name}") }
        } as GameDao
        val result = runBlocking { GameRestoreService(target, dao).restoreFromBundle(bundle.bundleFile) }
        check(result.isSuccess) { "Valid archive roundtrip failed: ${result.exceptionOrNull()?.message}" }
        val restored = result.getOrThrow()
        // JNI uses VirtualFile(outerArchive, innerMember); native SaveStateManager/BatteryManager
        // derive names from inner.nes, not from outer.zip.
        check(File(target.getSaveDir(restored.gameId), "inner.sav").takeIf { it.exists() }?.readText() == "BATTERY") { "Restore renamed native inner.sav to archive basename; emulator cannot see the battery save" }
        check(File(target.getSaveStateDir(restored.gameId), "inner_1.mss").takeIf { it.exists() }?.readText() == "STATE_SLOT_1") { "Native inner ROM save-state slot was lost by archive basename remapping" }
    }
    probe("S06 missing rollback backup must not delete the only live save") { root, storage ->
        val live = File(storage.getSaveDir("rollback-game"), "game.sav").apply { writeText("ONLY_LIVE_SAVE") }
        val missing = File(root, "missing-rollback")
        check(!storage.restoreFromRollbackBackup("rollback-game", missing)) { "Missing rollback was reported as restored" }
        check(live.exists() && live.readText() == "ONLY_LIVE_SAVE") { "Rollback with missing source destroyed live data" }
    }
    println("V5 snapshot audit: $failed failures / 6 probes")
    check(failed == 0) { "Remaining snapshot regressions reproduced" }
}
