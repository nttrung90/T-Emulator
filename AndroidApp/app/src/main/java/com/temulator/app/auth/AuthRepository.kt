package com.temulator.app.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.temulator.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.UUID

class AuthRepository private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("auth_session", Context.MODE_PRIVATE)
    private val credentialManager = CredentialManager.create(context)
    private val sessionTracker = AuthSessionTracker(loadInitialUser())

    val sessionState: StateFlow<UserSession> = sessionTracker.sessionState

    private val _authState = MutableStateFlow<AuthState>(
        sessionTracker.captureSession().user?.let { AuthState.Authenticated(it) } ?: AuthState.Unauthenticated
    )
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val isSigningOut = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        try {
            val fbAuth = FirebaseAuth.getInstance()
            fbAuth.addAuthStateListener { auth ->
                if (isSigningOut.get()) {
                    // Ignore stale AuthStateListener callback while sign-out is in progress
                    return@addAuthStateListener
                }
                val fbUser = auth.currentUser
                if (fbUser != null) {
                    val user = UserAccount(
                        uid = fbUser.uid,
                        displayName = fbUser.displayName,
                        email = fbUser.email,
                        photoUrl = fbUser.photoUrl?.toString()
                    )
                    saveSession(user)
                    sessionTracker.updateUser(user)
                    _authState.value = AuthState.Authenticated(user)
                } else if (!BuildConfig.DEBUG || !isTestAccount()) {
                    clearProfilePrefs()
                    sessionTracker.invalidateSession()
                    _authState.value = AuthState.Unauthenticated
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseAuth listener not initialized or unavailable", e)
        }
    }

    companion object {
        private const val TAG = "AuthRepository"
        private const val KEY_UID = "user_uid"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_EMAIL = "user_email"
        private const val KEY_PHOTO_URL = "photo_url"

        // Default web client ID configuration key - to be overridden by production config
        const val PREF_WEB_CLIENT_ID = "google_web_client_id"
        // Default placeholder for environments without configured Google Cloud Console project
        const val DEFAULT_SERVER_CLIENT_ID = "YOUR_GOOGLE_WEB_CLIENT_ID.apps.googleusercontent.com"

        @Volatile
        private var INSTANCE: AuthRepository? = null

        fun getInstance(context: Context): AuthRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AuthRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private fun loadInitialUser(): UserAccount? {
        val fbAuth = try { FirebaseAuth.getInstance() } catch (e: Exception) { null }
        if (!BuildConfig.DEBUG) {
            val fbUser = fbAuth?.currentUser
            return if (fbUser != null) {
                val user = UserAccount(
                    uid = fbUser.uid,
                    displayName = fbUser.displayName,
                    email = fbUser.email,
                    photoUrl = fbUser.photoUrl?.toString()
                )
                saveSession(user)
                user
            } else {
                clearProfilePrefs()
                null
            }
        } else {
            val fbUser = fbAuth?.currentUser
            if (fbUser != null) {
                val user = UserAccount(
                    uid = fbUser.uid,
                    displayName = fbUser.displayName,
                    email = fbUser.email,
                    photoUrl = fbUser.photoUrl?.toString()
                )
                saveSession(user)
                return user
            }
            val uid = prefs.getString(KEY_UID, null)
            val email = prefs.getString(KEY_EMAIL, null)
            return if (!uid.isNullOrEmpty() && (isTestAccount(uid) || BuildConfig.DEBUG)) {
                val displayName = prefs.getString(KEY_DISPLAY_NAME, null)
                val photoUrl = prefs.getString(KEY_PHOTO_URL, null)
                UserAccount(uid, displayName, email, photoUrl)
            } else {
                null
            }
        }
    }

    fun captureSession(): UserSession = sessionTracker.captureSession()

    fun isCurrent(session: UserSession): Boolean = sessionTracker.isCurrent(session)

    fun <T> commitIfCurrent(session: UserSession, block: () -> T): T? = sessionTracker.commitIfCurrent(session, block)

    fun getCurrentUser(): UserAccount? {
        return sessionTracker.captureSession().user
    }

    fun isUserLoggedIn(): Boolean {
        return getCurrentUser() != null
    }

    /**
     * Retrieves current valid Firebase ID Token (JWT) for authenticated backend requests.
     */
    suspend fun getIdToken(forceRefresh: Boolean = false): String? {
        val fbAuth = try { FirebaseAuth.getInstance() } catch (e: Exception) { null }
        val currentUser = fbAuth?.currentUser
        if (currentUser != null) {
            return try {
                currentUser.getIdToken(forceRefresh).await()?.token
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is com.google.firebase.auth.FirebaseAuthInvalidUserException) {
                    Log.w(TAG, "Firebase user is invalid or deleted, invalidating session: ${e.message}")
                    sessionTracker.invalidateSession()
                    clearProfilePrefs()
                    _authState.value = AuthState.Unauthenticated
                }
                Log.e(TAG, "Failed to get Firebase ID token", e)
                null
            }
        } else if (BuildConfig.DEBUG && isTestAccount()) {
            return "test_debug_bearer_token"
        } else {
            return null
        }
    }

    suspend fun signInWithGoogle(activity: Activity, webClientId: String = getServerClientId()): Result<UserAccount> {
        _authState.value = AuthState.Loading

        if (webClientId.isEmpty() || webClientId == DEFAULT_SERVER_CLIENT_ID) {
            val error = "Google Web Client ID is not configured. Please configure client ID in Google Cloud Console."
            Log.w(TAG, error)
            _authState.value = AuthState.Error(error)
            return Result.failure(IllegalStateException(error))
        }

        try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val response: GetCredentialResponse = credentialManager.getCredential(
                context = activity,
                request = request
            )

            val credential = response.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken
                val email = googleIdTokenCredential.id
                val displayName = googleIdTokenCredential.displayName
                val photoUrl = googleIdTokenCredential.profilePictureUri?.toString()

                // Sign in with Firebase Auth using Google ID token (F03)
                var resolvedUid: String? = null
                try {
                    val fbAuth = FirebaseAuth.getInstance()
                    val authCredential = GoogleAuthProvider.getCredential(idToken, null)
                    val authResult = fbAuth.signInWithCredential(authCredential).await()
                    resolvedUid = authResult.user?.uid
                } catch (fbErr: Exception) {
                    Log.w(TAG, "Firebase Auth sign-in failed: ${fbErr.message}")
                    if (!BuildConfig.DEBUG) {
                        val msg = "Firebase Authentication failed: ${fbErr.localizedMessage}"
                        _authState.value = AuthState.Error(msg)
                        return Result.failure(IllegalStateException(msg, fbErr))
                    }
                }

                // If Firebase unavailable in debug build, fallback to stable sub UID
                val finalUid = resolvedUid ?: generateUidFromIdToken(idToken, email)

                val user = UserAccount(
                    uid = finalUid,
                    displayName = displayName ?: email.substringBefore("@"),
                    email = email,
                    photoUrl = photoUrl
                )

                saveSession(user)
                sessionTracker.updateUser(user)
                _authState.value = AuthState.Authenticated(user)
                return Result.success(user)
            } else {
                val error = "Unsupported credential returned: ${credential.type}"
                _authState.value = AuthState.Error(error)
                return Result.failure(IllegalStateException(error))
            }
        } catch (e: GetCredentialCancellationException) {
            Log.i(TAG, "User canceled Google sign-in.")
            _authState.value = sessionTracker.captureSession().user?.let { AuthState.Authenticated(it) } ?: AuthState.Unauthenticated
            return Result.failure(e)
        } catch (e: NoCredentialException) {
            val error = "No Google credentials found on device."
            Log.w(TAG, error, e)
            _authState.value = AuthState.Error(error)
            return Result.failure(e)
        } catch (e: GetCredentialException) {
            val error = e.localizedMessage ?: "Google sign-in failed."
            Log.e(TAG, "GetCredentialException: $error", e)
            _authState.value = AuthState.Error(error)
            return Result.failure(e)
        } catch (e: Exception) {
            val error = e.localizedMessage ?: "Unexpected sign-in error."
            Log.e(TAG, "Sign-in error: $error", e)
            _authState.value = AuthState.Error(error)
            return Result.failure(e)
        }
    }

    private fun generateUidFromIdToken(idToken: String, fallbackEmail: String): String {
        return try {
            val parts = idToken.split(".")
            if (parts.size >= 2) {
                val payloadJson = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE))
                val json = JSONObject(payloadJson)
                val sub = json.optString("sub", "")
                if (sub.isNotEmpty()) {
                    return "goog_$sub"
                }
            }
            "goog_" + UUID.nameUUIDFromBytes(fallbackEmail.toByteArray()).toString()
        } catch (e: Exception) {
            "goog_" + UUID.nameUUIDFromBytes(fallbackEmail.toByteArray()).toString()
        }
    }

    fun signInWithTestAccount(
        displayName: String = "VIP Emulator Tester",
        email: String = "tester@emulator.local"
    ): UserAccount {
        if (!BuildConfig.DEBUG) {
            throw SecurityException("Test accounts are strictly disabled in release builds.")
        }
        val user = UserAccount(
            uid = "goog_test_emulator_vip_user",
            displayName = displayName,
            email = email,
            photoUrl = null
        )
        saveSession(user)
        sessionTracker.updateUser(user)
        _authState.value = AuthState.Authenticated(user)
        Log.i(TAG, "Signed in with emulator test account: $email")
        return user
    }

    fun isTestAccount(uid: String? = getCurrentUser()?.uid): Boolean {
        return BuildConfig.DEBUG && uid == "goog_test_emulator_vip_user"
    }

    fun saveSession(user: UserAccount) {
        prefs.edit()
            .putString(KEY_UID, user.uid)
            .putString(KEY_DISPLAY_NAME, user.displayName)
            .putString(KEY_EMAIL, user.email)
            .putString(KEY_PHOTO_URL, user.photoUrl)
            .apply()
    }

    fun clearProfilePrefs() {
        prefs.edit()
            .remove(KEY_UID)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_EMAIL)
            .remove(KEY_PHOTO_URL)
            .apply()
    }

    suspend fun signOut() {
        isSigningOut.set(true)
        try {
            // Invalidate session immediately before external credential cleanup so in-flight requests abort (Delta 4)
            sessionTracker.invalidateSession()
            clearProfilePrefs()
            _authState.value = AuthState.Unauthenticated
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w(TAG, "Error clearing credential state", e)
            }
            try {
                FirebaseAuth.getInstance().signOut()
            } catch (e: Exception) {
                Log.w(TAG, "Error signing out Firebase Auth", e)
            }
        } finally {
            isSigningOut.set(false)
        }
    }

    fun getServerClientId(): String {
        val saved = prefs.getString(PREF_WEB_CLIENT_ID, null)
        val candidate = if (!saved.isNullOrEmpty()) {
            saved
        } else if (com.temulator.app.BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()) {
            com.temulator.app.BuildConfig.GOOGLE_WEB_CLIENT_ID
        } else {
            try {
                val appInfo = context.packageManager.getApplicationInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_META_DATA
                )
                appInfo.metaData?.getString("com.temulator.app.GOOGLE_WEB_CLIENT_ID") ?: ""
            } catch (e: Exception) {
                ""
            }
        }
        if (candidate.isBlank() || candidate.contains("YOUR_GOOGLE_WEB_CLIENT_ID") || candidate.startsWith("YOUR_")) {
            return ""
        }
        return candidate
    }

    fun setServerClientId(clientId: String) {
        prefs.edit().putString(PREF_WEB_CLIENT_ID, clientId).apply()
    }
}
