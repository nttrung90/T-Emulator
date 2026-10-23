package com.temulator.app

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.temulator.app.auth.AuthRepository
import com.temulator.app.cloud.CloudBackupDialogHelper
import com.temulator.app.cloud.DriveAuthorizationManager
import com.temulator.app.cloud.GameRestoreService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Custom Application class responsible for:
 * 1. Programmatic Firebase initialization using configured BuildConfig options.
 * 2. Asynchronous startup crash and orphan recovery for cloud backups and restore journals.
 */
class TemulatorApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        initFirebase()
        runStartupRecovery()
    }

    private fun initFirebase() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                val apiKey = BuildConfig.FIREBASE_API_KEY
                val appId = BuildConfig.FIREBASE_APPLICATION_ID
                val projectId = BuildConfig.FIREBASE_PROJECT_ID

                if (apiKey.isNotBlank() && appId.isNotBlank() && projectId.isNotBlank() &&
                    !apiKey.startsWith("YOUR_") && !appId.startsWith("YOUR_")
                ) {
                    val options = FirebaseOptions.Builder()
                        .setApiKey(apiKey)
                        .setApplicationId(appId)
                        .setProjectId(projectId)
                        .build()
                    FirebaseApp.initializeApp(this, options)
                    Log.i(TAG, "Firebase initialized programmatically.")
                } else {
                    Log.w(TAG, "Firebase credentials unconfigured or placeholder; skipping programmatic initialization.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize Firebase programmatically: ${e.message}")
        }
    }

    private fun runStartupRecovery() {
        applicationScope.launch {
            try {
                val authRepo = AuthRepository.getInstance(this@TemulatorApplication)
                val driveAuth = DriveAuthorizationManager.getInstance(this@TemulatorApplication)
                CloudBackupDialogHelper.reconcileOrphanTransfers(this@TemulatorApplication, authRepo, driveAuth)
            } catch (e: Exception) {
                Log.e(TAG, "Error during orphan transfer reconciliation at startup", e)
            }

            try {
                GameRestoreService(this@TemulatorApplication).recoverPendingRestores()
            } catch (e: Exception) {
                Log.e(TAG, "Error during restore crash recovery at startup", e)
            }
        }
    }

    companion object {
        private const val TAG = "TemulatorApp"
    }
}
