package com.temulator.app.cloud

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.temulator.app.BuildConfig
import com.temulator.app.R
import com.temulator.app.auth.AuthRepository
import com.temulator.app.auth.AuthSession
import com.temulator.app.billing.BillingRepository
import com.temulator.app.billing.EntitlementRepository
import com.temulator.app.billing.VipDialogHelper
import com.temulator.app.data.db.AppDatabase
import com.temulator.app.data.db.CloudBackupRecord
import com.temulator.app.data.db.GameRecord
import com.temulator.app.data.db.TransferRecord
import com.temulator.app.storage.GameStorageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

object CloudBackupDialogHelper {

    fun initiateGameBackup(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        game: GameRecord,
        authRepo: AuthRepository,
        entitlementRepo: EntitlementRepository,
        billingRepo: BillingRepository,
        driveAuthManager: DriveAuthorizationManager,
        onDriveAuthRequired: () -> Unit,
        onComplete: (() -> Unit)? = null
    ) {
        val user = authRepo.getCurrentUser()
        if (user == null) {
            if (BuildConfig.DEBUG) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.dialog_login_emulator_fallback_title)
                    .setMessage(R.string.dialog_login_emulator_fallback_msg)
                    .setPositiveButton(R.string.dialog_btn_use_test_account) { _, _ ->
                        authRepo.signInWithTestAccount()
                        driveAuthManager.enableMockDrive()
                        Toast.makeText(activity, R.string.dev_login_success, Toast.LENGTH_SHORT).show()
                        initiateGameBackup(activity, lifecycleOwner, game, authRepo, entitlementRepo, billingRepo, driveAuthManager, onDriveAuthRequired, onComplete)
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            } else {
                Toast.makeText(activity, R.string.error_login_required, Toast.LENGTH_LONG).show()
            }
            return
        }

        val pinnedAppSession = authRepo.captureSession()
        val pinnedDriveSession = driveAuthManager.captureSession()

        lifecycleOwner.lifecycleScope.launch {
            // Fresh authoritative entitlement check before opening backup scope
            val isVip = entitlementRepo.ensureFreshEntitlement(authRepo)
            if (!isVip) {
                VipDialogHelper.showVipPaywall(activity, billingRepo, authRepo) {
                    // Retry after VIP unlock
                    initiateGameBackup(activity, lifecycleOwner, game, authRepo, entitlementRepo, billingRepo, driveAuthManager, onDriveAuthRequired, onComplete)
                }
                return@launch
            }

            if (!driveAuthManager.isDriveAuthorized()) {
                Toast.makeText(activity, R.string.error_drive_permission_required, Toast.LENGTH_SHORT).show()
                onDriveAuthRequired()
                return@launch
            }

            // Show backup scope options dialog
            val options = arrayOf(
                activity.getString(R.string.backup_scope_all),
                activity.getString(R.string.backup_scope_save_only)
            )

            MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.backup_dialog_title, game.displayName))
                .setSingleChoiceItems(options, 0) { dialog, which ->
                    dialog.dismiss()
                    val backupType = if (which == 0) "ALL" else "SAVE_ONLY"
                    executeBackup(
                        activity,
                        lifecycleOwner,
                        game,
                        authRepo,
                        pinnedAppSession,
                        driveAuthManager,
                        pinnedDriveSession,
                        backupType,
                        onComplete
                    )
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun executeBackup(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        game: GameRecord,
        authRepo: AuthRepository,
        pinnedAppSession: AuthSession,
        driveAuthManager: DriveAuthorizationManager,
        pinnedDriveSession: DriveSession,
        backupType: String,
        onComplete: (() -> Unit)?
    ) {
        val progressDialogView = createProgressDialogView(activity, activity.getString(R.string.backup_uploading, 0))
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(progressDialogView.root)
            .setCancelable(false)
            .create()
        dialog.show()

        val jobId = UUID.randomUUID().toString()
        val storage = GameStorageManager(activity)
        val entitlementRepo = EntitlementRepository.getInstance(activity)
        val userUid = pinnedAppSession.uid ?: ""

        lifecycleOwner.lifecycleScope.launch {
            var createdBackupId: String? = null
            var transferJournalFile: File? = null
            try {
                // Pre-check session & VIP before initiating backup operations
                if (!authRepo.isCurrent(pinnedAppSession) || !driveAuthManager.isCurrent(pinnedDriveSession) || !entitlementRepo.isCachedVipValid(userUid)) {
                    dialog.dismiss()
                    Toast.makeText(activity, "Backup canceled: user session or VIP state changed.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val snapshotService = GameSnapshotService(storage)
                val driveRepo = DriveBackupRepository(activity, driveAuthManager, pinnedDriveSession)
                val db = AppDatabase.getInstance(activity)

                // Track transfer job without swallowing initial insert errors
                val transferRecord = TransferRecord(
                    jobId = jobId,
                    ownerUid = userUid,
                    backupId = "",
                    operation = "BACKUP",
                    stage = "PREPARING",
                    progress = 0
                )
                val insertSuccess = withContext(Dispatchers.IO) {
                    try {
                        db.transferDao().insertOrUpdate(transferRecord)
                        true
                    } catch (dbErr: Exception) {
                        Log.e("CloudBackupDialog", "Failed to insert initial transfer record", dbErr)
                        false
                    }
                }
                if (!insertSuccess) {
                    dialog.dismiss()
                    Toast.makeText(activity, activity.getString(R.string.backup_failed, "Database error initializing transfer"), Toast.LENGTH_LONG).show()
                    return@launch
                }

                val snapshot = withContext(Dispatchers.IO) {
                    snapshotService.createSnapshot(game, backupType)
                }

                if (snapshot == null) {
                    dialog.dismiss()
                    withContext(NonCancellable + Dispatchers.IO) {
                        try { db.transferDao().updateProgress(jobId, "FAILED", 0) } catch (_: Exception) {}
                    }
                    val errorMsg = if (backupType == "ALL") "Cannot create backup: ROM file is missing or unreadable." else "Failed to create snapshot."
                    Toast.makeText(activity, activity.getString(R.string.backup_failed, errorMsg), Toast.LENGTH_LONG).show()
                    return@launch
                }
                createdBackupId = snapshot.backupId

                // Persist durable transfer journal in staging before upload
                val stagingDir = storage.getStagingDir(snapshot.backupId)
                val journalFile = File(stagingDir, "transfer_journal.json")
                transferJournalFile = journalFile
                val journalJson = JSONObject().apply {
                    put("jobId", jobId)
                    put("backupId", snapshot.backupId)
                    put("ownerUid", userUid)
                    put("driveAccount", pinnedDriveSession.accountEmail ?: "unknown")
                    put("gameId", game.gameId)
                    put("displayName", game.displayName)
                    put("backupType", backupType)
                    put("bundleSha256", snapshot.bundleSha256)
                    put("totalSize", snapshot.totalSize)
                    put("stage", "UPLOADING")
                    put("timestamp", System.currentTimeMillis())
                }
                withContext(Dispatchers.IO) {
                    try { journalFile.writeText(journalJson.toString()) } catch (_: Exception) {}
                }

                // Check session & VIP before uploading to cloud
                if (!authRepo.isCurrent(pinnedAppSession) || !driveAuthManager.isCurrent(pinnedDriveSession) || !entitlementRepo.isCachedVipValid(userUid)) {
                    dialog.dismiss()
                    withContext(NonCancellable + Dispatchers.IO) {
                        try { db.transferDao().updateProgress(jobId, "CANCELED", 0) } catch (_: Exception) {}
                    }
                    Toast.makeText(activity, "Backup canceled: session or VIP entitlement invalid before upload.", Toast.LENGTH_LONG).show()
                    return@launch
                }

                progressDialogView.tvMessage.text = activity.getString(R.string.backup_uploading, 10)
                progressDialogView.progressBar.progress = 10

                withContext(Dispatchers.IO) {
                    try { db.transferDao().updateProgress(jobId, "UPLOADING", 10) } catch (_: Exception) {}
                }

                val uploadResult = driveRepo.uploadSnapshot(snapshot, game) { percent ->
                    activity.runOnUiThread {
                        progressDialogView.progressBar.progress = percent
                        progressDialogView.tvMessage.text = activity.getString(R.string.backup_uploading, percent)
                    }
                }

                dialog.dismiss()

                // Verify user session & Drive session were not switched during upload
                if (!authRepo.isCurrent(pinnedAppSession) || !driveAuthManager.isCurrent(pinnedDriveSession)) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        try { db.transferDao().updateProgress(jobId, "CANCELED", 0) } catch (_: Exception) {}
                    }
                    Toast.makeText(activity, "Backup canceled: logged-in user or Drive session changed during transfer.", Toast.LENGTH_LONG).show()
                    return@launch
                }

                if (uploadResult.isSuccess) {
                    val remoteFileId = uploadResult.getOrThrow()

                    // Update journal with remoteFileId before DB commit
                    withContext(Dispatchers.IO) {
                        try {
                            journalJson.put("remoteFileId", remoteFileId)
                            journalJson.put("stage", "UPLOADED")
                            journalFile.writeText(journalJson.toString())
                        } catch (_: Exception) {}
                    }

                    val record = CloudBackupRecord(
                        backupId = snapshot.backupId,
                        ownerUid = userUid,
                        driveAccount = pinnedDriveSession.accountEmail ?: "unknown",
                        gameId = game.gameId,
                        remoteFileId = remoteFileId,
                        schemaVersion = 1,
                        createdAt = System.currentTimeMillis(),
                        checksum = snapshot.bundleSha256,
                        size = snapshot.totalSize,
                        status = "COMPLETED",
                        backupType = backupType
                    )
                    val dbUpdated = withContext(Dispatchers.IO) {
                        try {
                            db.withTransaction {
                                db.cloudBackupDao().insertOrUpdate(record)
                                db.transferDao().updateProgress(jobId, "COMPLETED", 100)
                            }
                            true
                        } catch (dbErr: Exception) {
                            Log.e("CloudBackupDialog", "Failed to update backup records in transaction", dbErr)
                            false
                        }
                    }
                    if (dbUpdated) {
                        // DB commit succeeded; safely remove transfer journal
                        withContext(Dispatchers.IO) {
                            try { journalFile.delete() } catch (_: Exception) {}
                        }
                        Toast.makeText(activity, R.string.backup_success, Toast.LENGTH_LONG).show()
                        onComplete?.invoke()
                    } else {
                        // DB commit failed; preserve journal for orphan reconciliation
                        Toast.makeText(activity, activity.getString(R.string.backup_failed, "Failed to persist local backup record"), Toast.LENGTH_LONG).show()
                    }
                } else {
                    val ex = uploadResult.exceptionOrNull()
                    val errorMsg = ex?.localizedMessage ?: "Upload failed"
                    withContext(NonCancellable + Dispatchers.IO) {
                        try { db.transferDao().updateProgress(jobId, "FAILED", 0) } catch (_: Exception) {}
                    }
                    Toast.makeText(activity, activity.getString(R.string.backup_failed, errorMsg), Toast.LENGTH_LONG).show()
                }
            } catch (e: CancellationException) {
                dialog.dismiss()
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        AppDatabase.getInstance(activity).transferDao().updateProgress(jobId, "CANCELED", 0)
                    } catch (_: Exception) {}
                }
                throw e
            } catch (e: Exception) {
                dialog.dismiss()
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        AppDatabase.getInstance(activity).transferDao().updateProgress(jobId, "FAILED", 0)
                    } catch (_: Exception) {}
                }
                Toast.makeText(activity, activity.getString(R.string.backup_failed, e.localizedMessage ?: "Unexpected error"), Toast.LENGTH_LONG).show()
            } finally {
                if (createdBackupId != null) {
                    withContext(Dispatchers.IO) {
                        val staging = storage.getStagingDir(createdBackupId)
                        val hasPendingJournal = File(staging, "transfer_journal.json").exists()
                        if (!hasPendingJournal) {
                            storage.cleanupStaging(createdBackupId)
                        } else {
                            Log.w("CloudBackupDialog", "Preserving staging for $createdBackupId due to pending transfer journal")
                        }
                    }
                }
            }
        }
    }

    /**
     * Restore existing backups is allowed even if VIP is currently inactive (preserves user data access).
     */
    fun showRestoreBackupsDialog(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        authRepo: AuthRepository,
        driveAuthManager: DriveAuthorizationManager,
        onDriveAuthRequired: () -> Unit,
        onGameRestored: (() -> Unit)? = null
    ) {
        val user = authRepo.getCurrentUser()
        if (user == null) {
            if (BuildConfig.DEBUG) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.dialog_login_emulator_fallback_title)
                    .setMessage(R.string.dialog_login_emulator_fallback_msg)
                    .setPositiveButton(R.string.dialog_btn_use_test_account) { _, _ ->
                        authRepo.signInWithTestAccount()
                        driveAuthManager.enableMockDrive()
                        Toast.makeText(activity, R.string.dev_login_success, Toast.LENGTH_SHORT).show()
                        showRestoreBackupsDialog(activity, lifecycleOwner, authRepo, driveAuthManager, onDriveAuthRequired, onGameRestored)
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            } else {
                Toast.makeText(activity, R.string.error_login_required, Toast.LENGTH_LONG).show()
            }
            return
        }

        if (!driveAuthManager.isDriveAuthorized()) {
            Toast.makeText(activity, R.string.error_drive_permission_required, Toast.LENGTH_SHORT).show()
            onDriveAuthRequired()
            return
        }

        val pinnedAppSession = authRepo.captureSession()
        val pinnedDriveSession = driveAuthManager.captureSession()

        val progressView = createProgressDialogView(activity, "Loading backups from Google Drive…")
        val progressDialog = MaterialAlertDialogBuilder(activity)
            .setView(progressView.root)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleOwner.lifecycleScope.launch {
            val driveRepo = DriveBackupRepository(activity, driveAuthManager, pinnedDriveSession)
            val result = driveRepo.listBackups()
            progressDialog.dismiss()

            when (result) {
                is DriveListResult.NeedsAuthorization -> {
                    Toast.makeText(activity, R.string.error_drive_permission_required, Toast.LENGTH_SHORT).show()
                    onDriveAuthRequired()
                }
                is DriveListResult.Error -> {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("Backup Query Error")
                        .setMessage("Could not load backups from Google Drive: ${result.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
                is DriveListResult.Success -> {
                    val backups = result.items
                    if (backups.isEmpty()) {
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.backups_dialog_title)
                            .setMessage(R.string.backups_empty)
                            .setPositiveButton("OK", null)
                            .show()
                        return@launch
                    }

                    val items = backups.map { backup ->
                        val typeLabel = if (backup.backupType == "SAVE_ONLY") "Save data" else "Full"
                        "${backup.displayName ?: backup.name}\n[$typeLabel • ${formatFileSize(backup.size)}]"
                    }.toTypedArray()

                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.backups_dialog_title)
                        .setItems(items) { _, which ->
                            val selected = backups[which]
                            confirmAndExecuteRestore(
                                activity,
                                lifecycleOwner,
                                selected,
                                driveRepo,
                                authRepo,
                                pinnedAppSession,
                                driveAuthManager,
                                pinnedDriveSession,
                                onGameRestored
                            )
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                }
            }
        }
    }

    private fun confirmAndExecuteRestore(
        activity: Activity,
        lifecycleOwner: LifecycleOwner,
        backupItem: DriveBackupItem,
        driveRepo: DriveBackupRepository,
        authRepo: AuthRepository,
        pinnedAppSession: AuthSession,
        driveAuthManager: DriveAuthorizationManager,
        pinnedDriveSession: DriveSession,
        onGameRestored: (() -> Unit)?
    ) {
        val name = backupItem.displayName ?: backupItem.name
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.restore_dialog_title)
            .setMessage(activity.getString(R.string.restore_dialog_message, name))
            .setPositiveButton(R.string.restore_start_button) { _, _ ->
                val progressView = createProgressDialogView(activity, activity.getString(R.string.restore_downloading, 0))
                val progressDialog = MaterialAlertDialogBuilder(activity)
                    .setView(progressView.root)
                    .setCancelable(false)
                    .create()
                progressDialog.show()

                lifecycleOwner.lifecycleScope.launch {
                    val storage = GameStorageManager(activity)
                    val restoreOpId = UUID.randomUUID().toString()
                    val stagingDir = storage.getStagingDir(restoreOpId)
                    val tempBundle = File(stagingDir, "restore_bundle.zip")

                    try {
                        if (!authRepo.isCurrent(pinnedAppSession) || !driveAuthManager.isCurrent(pinnedDriveSession)) {
                            progressDialog.dismiss()
                            Toast.makeText(activity, "Restore canceled: user or Drive session changed before download.", Toast.LENGTH_SHORT).show()
                            return@launch
                        }

                        val downloadSuccess = driveRepo.downloadBackup(backupItem.fileId, tempBundle) { percent ->
                            activity.runOnUiThread {
                                progressView.progressBar.progress = percent
                                progressView.tvMessage.text = activity.getString(R.string.restore_downloading, percent)
                            }
                        }

                        progressDialog.dismiss()

                        if (downloadSuccess) {
                            if (!authRepo.isCurrent(pinnedAppSession) || !driveAuthManager.isCurrent(pinnedDriveSession)) {
                                Toast.makeText(activity, "Restore canceled: user or Drive session changed during download.", Toast.LENGTH_SHORT).show()
                                return@launch
                            }

                            val restoreService = GameRestoreService(activity)
                            val restoreResult = restoreService.restoreFromBundle(tempBundle)
                            if (restoreResult.isSuccess) {
                                Toast.makeText(activity, R.string.restore_success, Toast.LENGTH_LONG).show()
                                onGameRestored?.invoke()
                            } else {
                                val err = restoreResult.exceptionOrNull()?.localizedMessage ?: "Verification error"
                                Toast.makeText(activity, activity.getString(R.string.restore_failed, err), Toast.LENGTH_LONG).show()
                            }
                        } else {
                            Toast.makeText(activity, activity.getString(R.string.restore_failed, "Download failed"), Toast.LENGTH_LONG).show()
                        }
                    } catch (e: CancellationException) {
                        progressDialog.dismiss()
                        throw e
                    } catch (e: Exception) {
                        progressDialog.dismiss()
                        Toast.makeText(activity, activity.getString(R.string.restore_failed, e.localizedMessage ?: "Restore error"), Toast.LENGTH_LONG).show()
                    } finally {
                        storage.cleanupStaging(restoreOpId)
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    suspend fun reconcileOrphanTransfers(
        context: Context,
        authRepo: AuthRepository,
        driveAuthManager: DriveAuthorizationManager
    ): Int = withContext(Dispatchers.IO) {
        var reconciledCount = 0
        val storage = GameStorageManager(context)
        val stagingRoot = storage.stagingRoot
        if (!stagingRoot.exists() || !stagingRoot.isDirectory) return@withContext 0
        val currentAppSession = authRepo.captureSession()
        val currentDriveSession = driveAuthManager.captureSession()
        val currentUid = currentAppSession.uid
        if (currentUid.isNullOrEmpty()) return@withContext 0

        val dirs = stagingRoot.listFiles()?.filter { it.isDirectory } ?: return@withContext 0
        val db = AppDatabase.getInstance(context)

        for (dir in dirs) {
            val journalFile = File(dir, "transfer_journal.json")
            if (!journalFile.exists()) continue
            try {
                val json = JSONObject(journalFile.readText())
                val ownerUid = json.optString("ownerUid", "")
                val driveAccount = json.optString("driveAccount", "")
                val remoteFileId = json.optString("remoteFileId", "")
                val backupId = json.optString("backupId", "")
                val gameId = json.optString("gameId", "")
                val backupType = json.optString("backupType", "ALL")
                val bundleSha256 = json.optString("bundleSha256", "")
                val totalSize = json.optLong("totalSize", 0L)
                val jobId = json.optString("jobId", "")

                if (ownerUid == currentUid && (currentDriveSession.accountEmail == null || driveAccount == currentDriveSession.accountEmail)) {
                    if (remoteFileId.isNotEmpty() && backupId.isNotEmpty()) {
                        val record = CloudBackupRecord(
                            backupId = backupId,
                            ownerUid = ownerUid,
                            driveAccount = driveAccount,
                            gameId = gameId,
                            remoteFileId = remoteFileId,
                            schemaVersion = 1,
                            createdAt = System.currentTimeMillis(),
                            checksum = bundleSha256,
                            size = totalSize,
                            status = "COMPLETED",
                            backupType = backupType
                        )
                        db.withTransaction {
                            db.cloudBackupDao().insertOrUpdate(record)
                            if (jobId.isNotEmpty()) {
                                db.transferDao().updateProgress(jobId, "COMPLETED", 100)
                            }
                        }
                        journalFile.delete()
                        storage.cleanupStaging(dir.name)
                        reconciledCount++
                        Log.i("CloudBackupDialog", "Reconciled orphan transfer for backupId $backupId")
                    }
                }
            } catch (e: Exception) {
                Log.e("CloudBackupDialog", "Failed to reconcile transfer journal in ${dir.name}", e)
            }
        }
        reconciledCount
    }

    private data class ProgressViewBinding(val root: android.view.View, val tvMessage: TextView, val progressBar: ProgressBar)

    private fun createProgressDialogView(context: Context, initialMessage: String): ProgressViewBinding {
        val layout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (24 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val tv = TextView(context).apply {
            text = initialMessage
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0, 0, 0, (16 * context.resources.displayMetrics.density).toInt())
        }
        val pb = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
        }
        layout.addView(tv)
        layout.addView(pb)
        return ProgressViewBinding(layout, tv, pb)
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
