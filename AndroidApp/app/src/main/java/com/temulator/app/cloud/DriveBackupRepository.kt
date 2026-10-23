package com.temulator.app.cloud

import android.content.Context
import android.util.Log
import com.temulator.app.BuildConfig
import com.temulator.app.data.db.GameRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DriveBackupItem(
    val fileId: String,
    val name: String,
    val size: Long,
    val createdTime: String,
    val gameId: String?,
    val backupId: String?,
    val displayName: String?,
    val originalFileName: String?,
    val consoleType: Int,
    val backupType: String
)

sealed interface DriveListResult {
    data class Success(val items: List<DriveBackupItem>) : DriveListResult
    data class Error(val message: String) : DriveListResult
    object NeedsAuthorization : DriveListResult
}

sealed interface FolderLookupResult {
    data class Found(val folderId: String) : FolderLookupResult
    object NotFound : FolderLookupResult
    data class Error(val message: String) : FolderLookupResult
    object NeedsAuthorization : FolderLookupResult
}

class DriveBackupRepository(
    private val context: Context,
    private val authManager: DriveAuthorizationManager,
    private val pinnedSession: DriveSession? = null
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "DriveBackupRepo"
        private const val FOLDER_NAME = "T-Emulator Backups"
        private const val BASE_DRIVE_URL = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD_DRIVE_URL = "https://www.googleapis.com/upload/drive/v3"
    }

    /**
     * Finds the backup folder ID without creating a new one (used for listing) (F14, V13).
     */
    suspend fun findBackupFolderId(): FolderLookupResult = withContext(Dispatchers.IO) {
        if (pinnedSession != null && !authManager.isCurrent(pinnedSession)) {
            return@withContext FolderLookupResult.NeedsAuthorization
        }
        if (authManager.isMockDriveEnabled()) {
            val dir = File(context.filesDir, "emulator_cloud_backups")
            return@withContext if (dir.exists()) FolderLookupResult.Found(dir.absolutePath) else FolderLookupResult.NotFound
        }

        val token = authManager.getAccessToken(pinnedSession) ?: return@withContext FolderLookupResult.NeedsAuthorization
        val query = "name = '$FOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val searchUrl = "$BASE_DRIVE_URL/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=files(id,name)"

        val searchRequest = Request.Builder()
            .url(searchUrl)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            httpClient.newCall(searchRequest).execute().use { response ->
                if (response.code == 401 || response.code == 403) {
                    return@withContext FolderLookupResult.NeedsAuthorization
                }
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val files = json.optJSONArray("files") ?: JSONArray()
                    if (files.length() > 0) {
                        return@withContext FolderLookupResult.Found(files.getJSONObject(0).getString("id"))
                    } else {
                        return@withContext FolderLookupResult.NotFound
                    }
                } else {
                    return@withContext FolderLookupResult.Error("Lookup failed with HTTP ${response.code}: ${response.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding backup folder", e)
            return@withContext FolderLookupResult.Error(e.localizedMessage ?: "Network error during folder lookup")
        }
    }

    /**
     * Looks up or creates the backup folder.
     * F14: Only creates the folder if lookup succeeded and confirmed non-existent.
     */
    suspend fun getOrCreateBackupFolder(): String? = withContext(Dispatchers.IO) {
        val lookup = findBackupFolderId()
        when (lookup) {
            is FolderLookupResult.Found -> return@withContext lookup.folderId
            FolderLookupResult.NotFound -> { /* proceed to create below */ }
            is FolderLookupResult.NeedsAuthorization -> {
                Log.w(TAG, "Cannot get or create folder: Authorization required.")
                return@withContext null
            }
            is FolderLookupResult.Error -> {
                Log.e(TAG, "Cannot get or create folder: ${lookup.message}")
                return@withContext null
            }
        }

        val token = authManager.getAccessToken(pinnedSession) ?: return@withContext null

        val createBody = JSONObject().apply {
            put("name", FOLDER_NAME)
            put("mimeType", "application/vnd.google-apps.folder")
        }.toString().toRequestBody("application/json".toMediaType())

        val createRequest = Request.Builder()
            .url("$BASE_DRIVE_URL/files")
            .header("Authorization", "Bearer $token")
            .post(createBody)
            .build()

        try {
            httpClient.newCall(createRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val newFolderId = json.getString("id")
                    Log.i(TAG, "Created new backup folder ID: $newFolderId")
                    return@withContext newFolderId
                } else {
                    Log.e(TAG, "Create folder failed: ${response.code} ${response.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating Drive folder", e)
        }

        null
    }

    suspend fun uploadSnapshot(
        snapshot: SnapshotResult,
        game: GameRecord,
        onProgress: ((Int) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val safeName = "temulator_${game.originalFileName}_${snapshot.backupId.take(8)}.zip"
        val backupType = try {
            JSONObject(snapshot.manifestJson).optString("backupType", "ALL")
        } catch (e: Exception) {
            "ALL"
        }

        if (authManager.isMockDriveEnabled()) {
            val dir = File(context.filesDir, "emulator_cloud_backups")
            if (!dir.exists()) dir.mkdirs()
            val destZip = File(dir, safeName)
            snapshot.bundleFile.copyTo(destZip, overwrite = true)

            val metaFile = File(dir, "$safeName.meta")
            val metaJson = JSONObject().apply {
                put("name", safeName)
                put("gameId", game.gameId)
                put("backupId", snapshot.backupId)
                put("displayName", game.displayName)
                put("originalFileName", game.originalFileName)
                put("consoleType", game.consoleType)
                put("backupType", backupType)
                put("createdTime", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date()))
            }
            metaFile.writeText(metaJson.toString())
            onProgress?.invoke(100)
            Log.i(TAG, "Uploaded mock snapshot to emulator sandbox: $safeName")
            return@withContext Result.success(snapshot.backupId)
        }

        if (pinnedSession != null && !authManager.isCurrent(pinnedSession)) {
            return@withContext Result.failure(IllegalStateException("Drive session changed before upload"))
        }

        val token = authManager.getAccessToken(pinnedSession)
            ?: return@withContext Result.failure(IllegalStateException("Google Drive authorization token unavailable"))

        val folderId = getOrCreateBackupFolder()
            ?: return@withContext Result.failure(IOException("Failed to find or create T-Emulator Backups folder on Google Drive"))

        val bundleFile = snapshot.bundleFile
        val totalBytes = bundleFile.length()

        val metadata = JSONObject().apply {
            put("name", safeName)
            put("parents", JSONArray().put(folderId))
            put("appProperties", JSONObject().apply {
                put("t_emulator", "backup")
                put("gameId", game.gameId)
                put("backupId", snapshot.backupId)
                put("schemaVersion", "1")
                put("displayName", game.displayName)
                put("originalFileName", game.originalFileName)
                put("consoleType", game.consoleType.toString())
                put("romChecksum", game.contentSha256 ?: "")
                put("bundleChecksum", snapshot.bundleSha256)
                put("backupType", backupType) // F14: Write backupType to metadata
            })
        }

        try {
            // Initiate resumable upload session
            val sessionRequest = Request.Builder()
                .url("$UPLOAD_DRIVE_URL/files?uploadType=resumable")
                .header("Authorization", "Bearer $token")
                .header("X-Upload-Content-Type", "application/zip")
                .header("X-Upload-Content-Length", totalBytes.toString())
                .post(metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .build()

            var uploadUrl: String? = null
            httpClient.newCall(sessionRequest).execute().use { resp ->
                if (resp.isSuccessful) {
                    uploadUrl = resp.header("Location")
                } else {
                    return@withContext Result.failure(IOException("Failed to initiate upload session: HTTP ${resp.code} ${resp.message}"))
                }
            }

            if (uploadUrl.isNullOrEmpty()) {
                return@withContext Result.failure(IOException("Upload URL Location header missing from Google Drive response"))
            }

            // Stream file content with progress tracking (F13)
            val countedBody = object : RequestBody() {
                override fun contentType() = "application/zip".toMediaType()
                override fun contentLength() = totalBytes

                override fun writeTo(sink: BufferedSink) {
                    bundleFile.source().use { source ->
                        var uploaded: Long = 0
                        val buffer = okio.Buffer()
                        val chunkSize = 64 * 1024L
                        var read: Long
                        while (source.read(buffer, chunkSize).also { read = it } != -1L) {
                            if (pinnedSession != null && !authManager.isCurrent(pinnedSession)) {
                                throw IOException("Drive session changed during chunked upload")
                            }
                            sink.write(buffer, read)
                            uploaded += read
                            val percent = if (totalBytes > 0) ((uploaded * 100) / totalBytes).toInt() else 0
                            onProgress?.invoke(percent)
                        }
                    }
                }
            }

            val uploadRequest = Request.Builder()
                .url(uploadUrl!!)
                .put(countedBody)
                .build()

            var remoteId: String? = null
            httpClient.newCall(uploadRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    remoteId = json.getString("id")
                    onProgress?.invoke(100)
                } else {
                    return@withContext Result.failure(IOException("Upload failed with HTTP ${response.code}: ${response.message}"))
                }
            }

            if (pinnedSession != null && !authManager.isCurrent(pinnedSession)) {
                return@withContext Result.failure(IllegalStateException("Drive session changed before finalizing upload"))
            }

            if (remoteId != null) {
                return@withContext Result.success(remoteId!!)
            }
            Result.failure(IOException("Upload response did not return a remote file ID"))
        } catch (e: Exception) {
            Log.e(TAG, "Exception during backup upload", e)
            Result.failure(e)
        }
    }

    /**
     * Lists backups with complete pagination and structured result status (F14).
     */
    suspend fun listBackups(): DriveListResult = withContext(Dispatchers.IO) {
        if (authManager.isMockDriveEnabled()) {
            val dir = File(context.filesDir, "emulator_cloud_backups")
            if (!dir.exists()) return@withContext DriveListResult.Success(emptyList())
            val list = mutableListOf<DriveBackupItem>()
            dir.listFiles()?.filter { it.extension == "zip" }?.forEach { zipFile ->
                val metaFile = File(dir, "${zipFile.name}.meta")
                var gameId: String? = null
                var backupId: String? = null
                var displayName: String? = zipFile.name
                var originalFileName: String? = null
                var consoleType = 0
                var backupType = "ALL"
                var createdTime = ""

                if (metaFile.exists()) {
                    try {
                        val json = JSONObject(metaFile.readText())
                        gameId = json.optString("gameId")
                        backupId = json.optString("backupId")
                        displayName = json.optString("displayName", zipFile.name)
                        originalFileName = json.optString("originalFileName")
                        consoleType = json.optInt("consoleType", 0)
                        backupType = json.optString("backupType", "ALL")
                        createdTime = json.optString("createdTime", "")
                    } catch (e: Exception) {
                        Log.w(TAG, "Error parsing meta file", e)
                    }
                }
                list.add(
                    DriveBackupItem(
                        fileId = zipFile.name,
                        name = zipFile.name,
                        size = zipFile.length(),
                        createdTime = createdTime,
                        gameId = gameId,
                        backupId = backupId ?: zipFile.name,
                        displayName = displayName,
                        originalFileName = originalFileName,
                        consoleType = consoleType,
                        backupType = backupType
                    )
                )
            }
            return@withContext DriveListResult.Success(list)
        }

        if (pinnedSession != null && !authManager.isCurrent(pinnedSession)) {
            return@withContext DriveListResult.NeedsAuthorization
        }
        val token = authManager.getAccessToken(pinnedSession) ?: return@withContext DriveListResult.NeedsAuthorization
        val lookup = findBackupFolderId()
        val folderId = when (lookup) {
            is FolderLookupResult.NeedsAuthorization -> return@withContext DriveListResult.NeedsAuthorization
            is FolderLookupResult.Error -> return@withContext DriveListResult.Error(lookup.message)
            FolderLookupResult.NotFound -> return@withContext DriveListResult.Success(emptyList())
            is FolderLookupResult.Found -> lookup.folderId
        }

        val results = mutableListOf<DriveBackupItem>()
        var pageToken: String? = null

        try {
            do {
                val query = "'$folderId' in parents and trashed = false"
                var url = "$BASE_DRIVE_URL/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=nextPageToken,files(id,name,size,createdTime,appProperties)&pageSize=50"
                if (pageToken != null) {
                    url += "&pageToken=$pageToken"
                }

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext DriveListResult.Error("Drive API error: HTTP ${response.code} ${response.message}")
                    }
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    pageToken = json.optString("nextPageToken").ifEmpty { null }
                    val files = json.optJSONArray("files") ?: JSONArray()
                    for (i in 0 until files.length()) {
                        val file = files.getJSONObject(i)
                        val appProps = file.optJSONObject("appProperties")
                        val item = DriveBackupItem(
                            fileId = file.getString("id"),
                            name = file.getString("name"),
                            size = file.optLong("size", 0L),
                            createdTime = file.optString("createdTime", ""),
                            gameId = appProps?.optString("gameId"),
                            backupId = appProps?.optString("backupId"),
                            displayName = appProps?.optString("displayName") ?: file.getString("name"),
                            originalFileName = appProps?.optString("originalFileName"),
                            consoleType = appProps?.optString("consoleType")?.toIntOrNull() ?: 0,
                            backupType = appProps?.optString("backupType", "ALL") ?: "ALL"
                        )
                        results.add(item)
                    }
                }
            } while (pageToken != null)

            DriveListResult.Success(results)
        } catch (e: Exception) {
            Log.e(TAG, "Error listing Drive backups", e)
            DriveListResult.Error(e.localizedMessage ?: "Failed to list backups")
        }
    }

    suspend fun downloadBackup(fileId: String, destFile: File, onProgress: ((Int) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        if (authManager.isMockDriveEnabled()) {
            val dir = File(context.filesDir, "emulator_cloud_backups")
            val src = File(dir, fileId)
            val fileToCopy = if (src.exists()) src else dir.listFiles()?.firstOrNull { it.name == fileId || it.name.contains(fileId) }
            if (fileToCopy != null && fileToCopy.exists()) {
                fileToCopy.copyTo(destFile, overwrite = true)
                onProgress?.invoke(100)
                return@withContext true
            }
            return@withContext false
        }

        if (pinnedSession != null && !authManager.isCurrent(pinnedSession)) {
            return@withContext false
        }
        val token = authManager.getAccessToken(pinnedSession) ?: return@withContext false
        val url = "$BASE_DRIVE_URL/files/$fileId?alt=media"

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body ?: return@withContext false
                    val totalBytes = body.contentLength()
                    var downloaded = 0L

                    FileOutputStream(destFile).use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var len: Int
                            while (input.read(buffer).also { len = it } != -1) {
                                out.write(buffer, 0, len)
                                downloaded += len
                                if (totalBytes > 0) {
                                    val percent = ((downloaded * 100) / totalBytes).toInt()
                                    onProgress?.invoke(percent)
                                }
                            }
                            out.flush()
                        }
                    }
                    onProgress?.invoke(100)
                    return@withContext destFile.exists() && destFile.length() > 0
                } else {
                    Log.e(TAG, "Download failed: ${response.code} ${response.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading backup file", e)
        }
        false
    }
}
