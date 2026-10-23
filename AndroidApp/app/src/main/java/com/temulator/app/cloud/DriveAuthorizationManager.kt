package com.temulator.app.cloud

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.temulator.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class DriveSession(
    val accountId: String?,
    val accountEmail: String?,
    val generation: Long
)

class DriveAuthorizationManager private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("drive_session", Context.MODE_PRIVATE)
    private val generationCounter = java.util.concurrent.atomic.AtomicLong(1L)

    companion object {
        private const val TAG = "DriveAuthManager"
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val KEY_DRIVE_ACCOUNT_EMAIL = "drive_account_email"
        private const val KEY_MOCK_DRIVE = "mock_drive_enabled"

        val SCOPE_DRIVE_FILE = Scope(DRIVE_FILE_SCOPE)

        @Volatile
        private var INSTANCE: DriveAuthorizationManager? = null

        fun getInstance(context: Context): DriveAuthorizationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DriveAuthorizationManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val _driveAccountEmail = MutableStateFlow<String?>(loadSavedDriveAccount())
    val driveAccountEmail: StateFlow<String?> = _driveAccountEmail.asStateFlow()

    fun captureSession(): DriveSession {
        val lastAccount = if (!isMockDriveEnabled()) GoogleSignIn.getLastSignedInAccount(context) else null
        val email = _driveAccountEmail.value
        val id = lastAccount?.id ?: email
        return DriveSession(id, email, generationCounter.get())
    }

    fun isCurrent(session: DriveSession): Boolean {
        if (session.generation != generationCounter.get()) return false
        val current = captureSession()
        return current.generation == session.generation &&
            (current.accountId == session.accountId || current.accountEmail == session.accountEmail)
    }

    private fun loadSavedDriveAccount(): String? {
        if (!BuildConfig.DEBUG && prefs.getBoolean(KEY_MOCK_DRIVE, false)) {
            // Clean up legacy mock drive setting in release
            prefs.edit().remove(KEY_MOCK_DRIVE).apply()
        }
        val email = prefs.getString(KEY_DRIVE_ACCOUNT_EMAIL, null)
        if (isMockDriveEnabled()) {
            return email ?: "tester@emulator.local"
        }
        val lastAccount = GoogleSignIn.getLastSignedInAccount(context)
        return if (lastAccount != null && GoogleSignIn.hasPermissions(lastAccount, SCOPE_DRIVE_FILE)) {
            email ?: lastAccount.email
        } else {
            null
        }
    }

    fun isDriveAuthorized(): Boolean {
        if (isMockDriveEnabled()) return true
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        return GoogleSignIn.hasPermissions(account, SCOPE_DRIVE_FILE)
    }

    fun enableMockDrive(email: String = "tester@emulator.local") {
        if (!BuildConfig.DEBUG) {
            Log.e(TAG, "Mock Drive is strictly disabled in release builds.")
            return
        }
        prefs.edit()
            .putBoolean(KEY_MOCK_DRIVE, true)
            .putString(KEY_DRIVE_ACCOUNT_EMAIL, email)
            .apply()
        _driveAccountEmail.value = email
        Log.i(TAG, "Mock Google Drive enabled for debug testing: $email")
    }

    fun isMockDriveEnabled(): Boolean {
        return BuildConfig.DEBUG && prefs.getBoolean(KEY_MOCK_DRIVE, false)
    }

    fun getGoogleSignInClient(activity: Activity): com.google.android.gms.auth.api.signin.GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(SCOPE_DRIVE_FILE)
            .build()
        return GoogleSignIn.getClient(activity, gso)
    }

    /**
     * Handles result from Google Drive OAuth consent.
     * Note (F09): Drive authorization is decoupled from application authentication.
     * Connecting Google Drive MUST NOT alter or switch the app's logged-in Firebase user session.
     */
    fun handleSignInResult(account: GoogleSignInAccount?): Boolean {
        return if (account != null && GoogleSignIn.hasPermissions(account, SCOPE_DRIVE_FILE)) {
            generationCounter.incrementAndGet()
            val email = account.email ?: "google_user"
            prefs.edit().putString(KEY_DRIVE_ACCOUNT_EMAIL, email).apply()
            _driveAccountEmail.value = email

            Log.i(TAG, "Drive authorization granted for account: $email (gen=${generationCounter.get()})")
            true
        } else {
            Log.w(TAG, "Drive authorization was denied or account was null.")
            false
        }
    }

    suspend fun getAccessToken(expectedSession: DriveSession? = null): String? = withContext(Dispatchers.IO) {
        if (expectedSession != null && !isCurrent(expectedSession)) {
            Log.w(TAG, "Discarding token request: Drive session is no longer current.")
            return@withContext null
        }
        if (isMockDriveEnabled()) {
            return@withContext "mock_sandbox_access_token"
        }
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return@withContext null
        if (!GoogleSignIn.hasPermissions(account, SCOPE_DRIVE_FILE)) {
            Log.w(TAG, "GoogleSignInAccount lacks DRIVE_FILE scope.")
            return@withContext null
        }
        if (expectedSession != null) {
            val currentId = account.id ?: account.email
            if (currentId != expectedSession.accountId && account.email != expectedSession.accountEmail) {
                Log.w(TAG, "Pinned Drive account mismatch during token acquisition.")
                return@withContext null
            }
        }

        try {
            val scopeString = "oauth2:$DRIVE_FILE_SCOPE"
            GoogleAuthUtil.getToken(context, account.account!!, scopeString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve Drive OAuth access token", e)
            null
        }
    }

    fun disconnectDrive() {
        generationCounter.incrementAndGet()
        prefs.edit().clear().apply()
        _driveAccountEmail.value = null
        try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
            GoogleSignIn.getClient(context, gso).signOut()
        } catch (e: Exception) {
            Log.w(TAG, "Error disconnecting Drive", e)
        }
    }
}
