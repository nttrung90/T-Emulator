package com.temulator.app.billing

import android.content.Context
import android.util.Log
import com.android.billingclient.api.Purchase
import com.temulator.app.BuildConfig
import com.temulator.app.auth.AuthRepository
import com.temulator.app.auth.UserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class EntitlementRepository private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("entitlement_cache", Context.MODE_PRIVATE)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val operationMutex = Mutex()
    private var expiryJob: Job? = null

    companion object {
        private const val TAG = "EntitlementRepository"
        private const val KEY_IS_VIP_PREFIX = "is_vip_"
        private const val KEY_PRODUCT_ID_PREFIX = "product_id_"
        private const val KEY_IS_LIFETIME_PREFIX = "is_lifetime_"
        private const val KEY_EXPIRY_PREFIX = "expiry_"
        private const val KEY_VERIFIED_AT_PREFIX = "verified_at_"
        private const val KEY_PURCHASE_TOKEN_PREFIX = "last_purchase_token_"
        private const val KEY_ORDER_ID_PREFIX = "last_order_id_"
        private const val KEY_BACKEND_URL = "backend_verify_url"

        @Volatile
        private var INSTANCE: EntitlementRepository? = null

        fun getInstance(context: Context): EntitlementRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: EntitlementRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val _isVipActive = MutableStateFlow(false)
    val isVipActive: StateFlow<Boolean> = _isVipActive.asStateFlow()

    init {
        migrateAndInvalidateLegacyCache()
        // Automatically observe AuthRepository session changes
        val authRepo = AuthRepository.getInstance(context)
        syncWithSession(authRepo.captureSession())
        CoroutineScope(Dispatchers.Main.immediate).launch {
            authRepo.sessionState.collect { session ->
                syncWithSession(session)
            }
        }
    }

    private fun syncWithSession(session: UserSession) {
        expiryJob?.cancel()
        val uid = session.uid
        if (uid.isNullOrEmpty()) {
            _isVipActive.value = false
        } else {
            val record = getCachedRecord(uid)
            val now = System.currentTimeMillis()
            val valid = VipCachePolicy.isValid(record, now)
            _isVipActive.value = valid
            if (valid && record != null) {
                scheduleTimerForRecord(uid, session.generation, record, now)
            }
        }
    }

    /**
     * Purges legacy un-keyed VIP cache or mock tokens to prevent unauthorized VIP entitlement (F01, F05).
     */
    private fun migrateAndInvalidateLegacyCache() {
        val legacyIsVip = prefs.getBoolean("is_vip", false)
        val legacyToken = prefs.getString("last_purchase_token", "") ?: ""
        val legacyOrder = prefs.getString("last_order_id", "") ?: ""

        if (legacyIsVip || legacyToken.startsWith("test_token_") || legacyOrder.startsWith("GPA.TEST")) {
            Log.i(TAG, "Invalidating legacy or mock VIP cache from prior versions.")
            prefs.edit()
                .remove("is_vip")
                .remove("last_purchase_token")
                .remove("last_order_id")
                .remove("verified_at")
                .apply()
        }
    }

    fun onUserChanged(uid: String?) {
        val authRepo = AuthRepository.getInstance(context)
        syncWithSession(authRepo.captureSession())
    }

    fun getCachedRecord(uid: String): VipCacheRecord? {
        if (!prefs.contains(KEY_IS_VIP_PREFIX + uid)) return null
        val token = prefs.getString(KEY_PURCHASE_TOKEN_PREFIX + uid, "") ?: ""
        val orderId = prefs.getString(KEY_ORDER_ID_PREFIX + uid, "") ?: ""
        val isMock = token.startsWith("test_token_") || orderId.startsWith("GPA.TEST")
        if (!BuildConfig.DEBUG && isMock) {
            // Reject mock cached records in release builds
            return null
        }
        val isVip = prefs.getBoolean(KEY_IS_VIP_PREFIX + uid, false)
        val productId = prefs.getString(KEY_PRODUCT_ID_PREFIX + uid, null)
        val isLifetime = prefs.getBoolean(KEY_IS_LIFETIME_PREFIX + uid, false)
        val expiry = if (prefs.contains(KEY_EXPIRY_PREFIX + uid)) prefs.getLong(KEY_EXPIRY_PREFIX + uid, 0L) else null
        val verifiedAt = prefs.getLong(KEY_VERIFIED_AT_PREFIX + uid, 0L)
        return VipCacheRecord(
            isVip = isVip,
            productId = productId,
            isLifetime = isLifetime,
            expiryTimeMillis = expiry,
            verifiedAt = verifiedAt
        )
    }

    fun isCachedVipValid(uid: String, now: Long = System.currentTimeMillis()): Boolean {
        val record = getCachedRecord(uid)
        return VipCachePolicy.isValid(record, now)
    }

    /**
     * Verifies purchase with backend server using Firebase ID Token.
     */
    suspend fun verifyAndAcknowledge(
        purchase: Purchase,
        authRepo: AuthRepository
    ): Boolean = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val session = authRepo.captureSession()
            val uid = session.uid
            if (uid.isNullOrEmpty()) {
                Log.e(TAG, "Cannot verify purchase: user is not logged in.")
                return@withLock false
            }

            val isTestOrder = purchase.orderId?.startsWith("GPA.TEST") == true || purchase.purchaseToken.startsWith("test_token_")
            if (isTestOrder) {
                if (BuildConfig.DEBUG && (authRepo.isTestAccount(uid) || BuildConfig.DEBUG)) {
                    Log.i(TAG, "Debug build: granting simulated test VIP for debug test order.")
                    authRepo.commitIfCurrent(session) {
                        saveEntitlementMetadata(
                            uid = uid,
                            isVip = true,
                            productId = "vip_lifetime",
                            isLifetime = true,
                            expiryTimeMillis = null,
                            purchaseToken = purchase.purchaseToken,
                            orderId = purchase.orderId
                        )
                    }
                    return@withLock true
                } else {
                    Log.e(TAG, "Test purchase tokens are rejected in release builds.")
                    return@withLock false
                }
            }

            val backendUrl = getBackendUrl()
            if (backendUrl.isNullOrEmpty()) {
                Log.e(TAG, "Backend URL is not configured. Purchase cannot be verified without backend authority.")
                return@withLock false
            }

            if (!authRepo.isCurrent(session)) return@withLock false
            val idToken = authRepo.getIdToken()
            if (idToken.isNullOrEmpty()) {
                Log.e(TAG, "Failed to obtain Firebase ID token for purchase verification.")
                return@withLock false
            }
            if (!authRepo.isCurrent(session)) return@withLock false

            val verifyResponse = verifyWithServer(backendUrl, purchase, idToken, uid)
            if (!authRepo.isCurrent(session)) {
                Log.w(TAG, "Discarding verification response because session changed during network call.")
                return@withLock false
            }

            val now = System.currentTimeMillis()
            val decoded = VipMetadataDecoder.decode(verifyResponse, now)
            when (decoded) {
                is VipMetadataDecodeResult.Success -> {
                    val valid = authRepo.commitIfCurrent(session) {
                        saveEntitlementMetadata(
                            uid = uid,
                            isVip = true,
                            productId = decoded.record.productId,
                            isLifetime = decoded.record.isLifetime,
                            expiryTimeMillis = decoded.record.expiryTimeMillis,
                            verifiedAt = decoded.record.verifiedAt,
                            purchaseToken = purchase.purchaseToken,
                            orderId = purchase.orderId
                        )
                        isCachedVipValid(uid, now)
                    } ?: false
                    return@withLock valid
                }
                is VipMetadataDecodeResult.Inactive -> {
                    authRepo.commitIfCurrent(session) {
                        revokeEntitlement(uid)
                    }
                    return@withLock false
                }
                is VipMetadataDecodeResult.Invalid -> {
                    // New verify contract missing metadata: fallback to authoritative GET
                    Log.w(TAG, "Verify response missing complete metadata (${decoded.error}), refreshing via GET")
                    return@withLock refreshEntitlementInternal(authRepo, session)
                }
            }
        }
    }

    private fun verifyWithServer(
        backendUrl: String,
        purchase: Purchase,
        idToken: String,
        uid: String
    ): JSONObject? {
        return try {
            val jsonBody = JSONObject().apply {
                put("uid", uid)
                put("purchaseToken", purchase.purchaseToken)
                put("orderId", purchase.orderId)
                put("packageName", purchase.packageName)
                put("productId", purchase.products.firstOrNull() ?: BillingRepository.VIP_PRODUCT_ID)
                put("purchaseTime", purchase.purchaseTime)
            }
            val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$backendUrl/v1/purchases/verify")
                .header("Authorization", "Bearer $idToken")
                .post(requestBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val respStr = response.body?.string() ?: ""
                    JSONObject(respStr)
                } else {
                    Log.e(TAG, "Backend verify endpoint returned HTTP ${response.code}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to verification backend", e)
            null
        }
    }

    /**
     * Refreshes VIP entitlement from backend server.
     */
    suspend fun refreshEntitlement(authRepo: AuthRepository): Boolean = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val session = authRepo.captureSession()
            refreshEntitlementInternal(authRepo, session)
        }
    }

    private suspend fun refreshEntitlementInternal(authRepo: AuthRepository, session: UserSession): Boolean {
        val uid = session.uid ?: return false
        val backendUrl = getBackendUrl()
        if (backendUrl.isNullOrEmpty()) {
            if (BuildConfig.DEBUG && authRepo.isTestAccount(uid)) {
                return isCachedVipValid(uid)
            }
            return false
        }
        if (!authRepo.isCurrent(session)) return false

        val idToken = authRepo.getIdToken()
        if (idToken.isNullOrEmpty()) {
            if (!authRepo.isCurrent(session)) return false
            // Auth invalidation handled by AuthRepository
            return false
        }
        if (!authRepo.isCurrent(session)) return false

        val now = System.currentTimeMillis()
        val decision: RefreshDecision = try {
            val request = Request.Builder()
                .url("$backendUrl/v1/entitlements")
                .header("Authorization", "Bearer $idToken")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string()
                RefreshClassifier.classifyResponse(response.code, body, now)
            }
        } catch (e: Exception) {
            RefreshClassifier.classifyException(e)
        }

        if (!authRepo.isCurrent(session)) {
            Log.w(TAG, "Ignoring stale refresh result because session changed during request.")
            return false
        }

        return when (decision) {
            is RefreshDecision.Grant -> {
                val valid = authRepo.commitIfCurrent(session) {
                    saveEntitlementMetadata(
                        uid = uid,
                        isVip = true,
                        productId = decision.record.productId,
                        isLifetime = decision.record.isLifetime,
                        expiryTimeMillis = decision.record.expiryTimeMillis,
                        verifiedAt = decision.record.verifiedAt
                    )
                    isCachedVipValid(uid, now)
                } ?: false
                valid
            }
            is RefreshDecision.Deny -> {
                authRepo.commitIfCurrent(session) {
                    revokeEntitlement(uid)
                }
                false
            }
            is RefreshDecision.TransportFallback -> {
                Log.w(TAG, "Transport fallback triggered: ${decision.message}")
                val valid = authRepo.commitIfCurrent(session) {
                    val validCache = isCachedVipValid(uid, now)
                    _isVipActive.value = validCache
                    if (validCache) {
                        val record = getCachedRecord(uid)
                        if (record != null) {
                            scheduleTimerForRecord(uid, session.generation, record, now)
                        }
                    } else {
                        expiryJob?.cancel()
                    }
                    validCache
                } ?: false
                valid
            }
        }
    }

    suspend fun ensureFreshEntitlement(authRepo: AuthRepository): Boolean = withContext(Dispatchers.IO) {
        val session = authRepo.captureSession()
        if (session.uid.isNullOrEmpty()) return@withContext false
        val refreshed = refreshEntitlement(authRepo)
        refreshed && authRepo.isCurrent(session)
    }

    private fun scheduleTimerForRecord(uid: String, generation: Long, record: VipCacheRecord, now: Long) {
        expiryJob?.cancel()
        val ageLimit = record.verifiedAt + VipCachePolicy.DEFAULT_TTL_MILLIS
        val targetTime = if (record.isLifetime || record.expiryTimeMillis == null) {
            ageLimit
        } else {
            minOf(ageLimit, record.expiryTimeMillis)
        }
        val delayMillis = targetTime - now
        val authRepo = AuthRepository.getInstance(context)
        val session = authRepo.captureSession()
        if (session.uid != uid || session.generation != generation) {
            return
        }
        if (delayMillis <= 0L) {
            authRepo.commitIfCurrent(session) {
                _isVipActive.value = false
            }
            return
        }
        expiryJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            delay(delayMillis)
            val currentSession = authRepo.captureSession()
            if (currentSession.generation == generation && currentSession.uid == uid) {
                authRepo.commitIfCurrent(currentSession) {
                    _isVipActive.value = isCachedVipValid(uid)
                    Log.i(TAG, "Scheduled VIP expiration timer flipped active state to ${_isVipActive.value}")
                }
            }
        }
    }

    fun saveEntitlementMetadata(
        uid: String,
        isVip: Boolean,
        productId: String?,
        isLifetime: Boolean,
        expiryTimeMillis: Long?,
        verifiedAt: Long = System.currentTimeMillis(),
        purchaseToken: String? = null,
        orderId: String? = null
    ) {
        val editor = prefs.edit()
            .putBoolean(KEY_IS_VIP_PREFIX + uid, isVip)
            .putString(KEY_PRODUCT_ID_PREFIX + uid, productId)
            .putBoolean(KEY_IS_LIFETIME_PREFIX + uid, isLifetime)
            .putLong(KEY_VERIFIED_AT_PREFIX + uid, verifiedAt)
        if (expiryTimeMillis != null) {
            editor.putLong(KEY_EXPIRY_PREFIX + uid, expiryTimeMillis)
        } else {
            editor.remove(KEY_EXPIRY_PREFIX + uid)
        }
        if (purchaseToken != null) {
            editor.putString(KEY_PURCHASE_TOKEN_PREFIX + uid, purchaseToken)
        }
        if (orderId != null) {
            editor.putString(KEY_ORDER_ID_PREFIX + uid, orderId)
        }
        editor.apply()

        val authRepo = AuthRepository.getInstance(context)
        val currentSession = authRepo.captureSession()
        if (currentSession.uid == uid) {
            authRepo.commitIfCurrent(currentSession) {
                val valid = isCachedVipValid(uid, verifiedAt)
                _isVipActive.value = valid
                if (valid) {
                    val record = getCachedRecord(uid)
                    if (record != null) {
                        scheduleTimerForRecord(uid, currentSession.generation, record, verifiedAt)
                    }
                } else {
                    expiryJob?.cancel()
                }
                Unit
            }
        }
    }

    fun revokeEntitlement(uid: String? = null) {
        val editor = prefs.edit()
        if (uid != null) {
            editor.remove(KEY_IS_VIP_PREFIX + uid)
            editor.remove(KEY_PRODUCT_ID_PREFIX + uid)
            editor.remove(KEY_IS_LIFETIME_PREFIX + uid)
            editor.remove(KEY_EXPIRY_PREFIX + uid)
            editor.remove(KEY_VERIFIED_AT_PREFIX + uid)
            editor.remove(KEY_PURCHASE_TOKEN_PREFIX + uid)
            editor.remove(KEY_ORDER_ID_PREFIX + uid)
        }
        editor.apply()

        val authRepo = AuthRepository.getInstance(context)
        val currentSession = authRepo.captureSession()
        if (uid == null || uid == currentSession.uid) {
            authRepo.commitIfCurrent(currentSession) {
                expiryJob?.cancel()
                _isVipActive.value = false
            }
        }
        Log.i(TAG, "VIP entitlement revoked locally for $uid.")
    }

    fun activateTestVip(uid: String = "test_user", orderId: String = "GPA.TEST-" + System.currentTimeMillis()) {
        if (!BuildConfig.DEBUG) {
            Log.e(TAG, "activateTestVip is strictly forbidden in release builds.")
            return
        }
        val testToken = "test_token_" + java.util.UUID.randomUUID().toString()
        saveEntitlementMetadata(
            uid = uid,
            isVip = true,
            productId = "vip_lifetime",
            isLifetime = true,
            expiryTimeMillis = null,
            verifiedAt = System.currentTimeMillis(),
            purchaseToken = testToken,
            orderId = orderId
        )
        _isVipActive.value = true
        Log.i(TAG, "VIP entitlement activated for testing ($orderId).")
    }

    fun isTestVip(uid: String): Boolean {
        if (!BuildConfig.DEBUG) return false
        val orderId = prefs.getString(KEY_ORDER_ID_PREFIX + uid, null)
        val token = prefs.getString(KEY_PURCHASE_TOKEN_PREFIX + uid, null)
        return orderId?.startsWith("GPA.TEST") == true || token?.startsWith("test_token_") == true
    }

    fun getBackendUrl(): String? {
        val saved = prefs.getString(KEY_BACKEND_URL, null)
        val url = if (!saved.isNullOrEmpty()) {
            saved
        } else if (BuildConfig.BACKEND_VERIFY_URL.isNotBlank()) {
            BuildConfig.BACKEND_VERIFY_URL
        } else {
            try {
                val appInfo = context.packageManager.getApplicationInfo(
                    context.packageName,
                    android.content.pm.PackageManager.GET_META_DATA
                )
                appInfo.metaData?.getString("com.temulator.app.BACKEND_VERIFY_URL")
            } catch (e: Exception) {
                null
            }
        }
        if (url.isNullOrBlank() || url.contains("YOUR_BACKEND") || url.startsWith("YOUR_")) {
            return null
        }
        if (!BuildConfig.DEBUG && !url.startsWith("https://", ignoreCase = true)) {
            Log.e(TAG, "Release build requires HTTPS backend verify URL: $url")
            return null
        }
        return url.trimEnd('/')
    }

    fun setBackendUrl(url: String) {
        prefs.edit().putString(KEY_BACKEND_URL, url).apply()
    }
}
