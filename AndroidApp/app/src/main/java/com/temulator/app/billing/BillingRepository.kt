package com.temulator.app.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.temulator.app.BuildConfig
import com.temulator.app.auth.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

sealed interface BillingUiState {
    object Disconnected : BillingUiState
    object Connecting : BillingUiState
    data class Ready(val productDetails: ProductDetails?) : BillingUiState
    data class PurchasePending(val purchaseToken: String) : BillingUiState
    data class Error(val message: String) : BillingUiState
}

class BillingRepository private constructor(
    private val context: Context,
    private val entitlementRepository: EntitlementRepository,
    private val authRepository: AuthRepository = AuthRepository.getInstance(context)
) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingRepository"
        const val VIP_PRODUCT_ID = "vip_lifetime"
        const val VIP_PRODUCT_ID_LEGACY = "vip_yearly"
        val VIP_PRODUCT_IDS = setOf(VIP_PRODUCT_ID, VIP_PRODUCT_ID_LEGACY)

        @Volatile
        private var INSTANCE: BillingRepository? = null

        fun getInstance(
            context: Context,
            entitlementRepository: EntitlementRepository,
            authRepository: AuthRepository = AuthRepository.getInstance(context)
        ): BillingRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BillingRepository(context.applicationContext, entitlementRepository, authRepository).also {
                    INSTANCE = it
                }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val pendingPurchasesParams = PendingPurchasesParams.newBuilder()
        .enableOneTimeProducts()
        .build()

    private var billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(pendingPurchasesParams)
        .build()

    private val _billingState = MutableStateFlow<BillingUiState>(BillingUiState.Disconnected)
    val billingState: StateFlow<BillingUiState> = _billingState.asStateFlow()

    private var vipProductDetails: ProductDetails? = null
    @Volatile
    private var pendingPurchaseAction: PendingPurchaseAction? = null

    fun clearPendingPurchase() {
        pendingPurchaseAction = null
    }

    init {
        startConnection()
    }

    fun checkBillingPreflight(): BillingPreflightResult {
        val backendUrl = entitlementRepository.getBackendUrl()
        val currentUser = authRepository.getCurrentUser()
        return BillingQueryReducer.checkPreflight(
            backendUrl = backendUrl,
            userIsSignedIn = currentUser != null
        )
    }

    fun startConnection(onReady: (() -> Unit)? = null, onError: ((String) -> Unit)? = null) {
        if (billingClient.isReady) {
            _billingState.value = BillingUiState.Ready(vipProductDetails)
            onReady?.invoke()
            return
        }

        _billingState.value = BillingUiState.Connecting
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "Billing client connected successfully.")
                    queryVipProductDetails {
                        _billingState.value = BillingUiState.Ready(vipProductDetails)
                        onReady?.invoke()
                    }
                    queryPurchases()
                } else {
                    val msg = "Billing setup failed: ${billingResult.debugMessage}"
                    Log.e(TAG, msg)
                    vipProductDetails = null
                    _billingState.value = BillingUiState.Error(msg)
                    onError?.invoke(msg)
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected.")
                vipProductDetails = null
                _billingState.value = BillingUiState.Disconnected
            }
        })
    }

    fun queryVipProductDetails(onComplete: (() -> Unit)? = null) {
        val inAppList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(VIP_PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        val inAppParams = QueryProductDetailsParams.newBuilder()
            .setProductList(inAppList)
            .build()

        billingClient.queryProductDetailsAsync(inAppParams) { inAppResult, inAppDetailsList ->
            if (inAppResult.responseCode == BillingClient.BillingResponseCode.OK && inAppDetailsList.productDetailsList.isNotEmpty()) {
                vipProductDetails = inAppDetailsList.productDetailsList.firstOrNull { it.productId == VIP_PRODUCT_ID }
                Log.i(TAG, "Found VIP in-app one-time product details: ${vipProductDetails?.productId}")
            } else {
                Log.w(TAG, "queryProductDetailsAsync for INAPP vip_lifetime failed: ${inAppResult.debugMessage}")
                vipProductDetails = null
            }
            onComplete?.invoke()
        }
    }

    fun launchVipPurchase(activity: Activity, rawAccountId: String, onSuccess: (() -> Unit)? = null): Boolean {
        val currentSession = authRepository.captureSession()
        if (onSuccess != null) {
            pendingPurchaseAction = PendingPurchaseAction(
                userUid = currentSession.uid,
                userGeneration = currentSession.generation,
                onSuccess = onSuccess
            )
        } else {
            pendingPurchaseAction = null
        }

        if (!BuildConfig.DEBUG) {
            val preflight = checkBillingPreflight()
            if (preflight is BillingPreflightResult.Unconfigured) {
                _billingState.value = BillingUiState.Error(preflight.reason)
                Log.e(TAG, "Preflight check failed: ${preflight.reason}")
                pendingPurchaseAction = null
                return false
            }
        }

        val details = vipProductDetails
        if (details != null && billingClient.isReady) {
            // Compute consistent SHA-256 obfuscated account identifier (F04)
            val obfuscatedId = MessageDigest.getInstance("SHA-256")
                .digest(rawAccountId.toByteArray())
                .joinToString("") { "%02x".format(it) }

            val launched = launchBillingFlow(activity, details, obfuscatedId)
            if (!launched) {
                pendingPurchaseAction = null
            }
            return launched
        }

        // F01: Only in DEBUG builds can emulator purchase simulator be opened
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "Debug build: Play Billing not ready, opening debug simulator.")
            showEmulatorPurchaseSimulator(activity, rawAccountId, onSuccess)
            return true
        }

        pendingPurchaseAction = null
        Log.e(TAG, "Play Billing is unavailable and simulator is prohibited in release builds.")
        _billingState.value = BillingUiState.Error("Google Play Store billing service is currently unavailable.")
        return false
    }

    fun showEmulatorPurchaseSimulator(
        activity: Activity,
        obfuscatedAccountId: String,
        onSuccess: (() -> Unit)? = null
    ) {
        if (!BuildConfig.DEBUG) return

        val options = arrayOf(
            activity.getString(com.temulator.app.R.string.sim_btn_success),
            activity.getString(com.temulator.app.R.string.sim_btn_pending),
            activity.getString(com.temulator.app.R.string.sim_btn_error),
            activity.getString(com.temulator.app.R.string.sim_btn_canceled)
        )

        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(com.temulator.app.R.string.sim_dialog_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // Simulated Success
                        simulateTestPurchase(obfuscatedAccountId, 0)
                        android.widget.Toast.makeText(activity, com.temulator.app.R.string.account_vip_status_active, android.widget.Toast.LENGTH_LONG).show()
                    }
                    1 -> { // Simulated Pending
                        clearPendingPurchase()
                        val fakeToken = "test_token_pending_" + System.currentTimeMillis()
                        _billingState.value = BillingUiState.PurchasePending(fakeToken)
                        android.widget.Toast.makeText(activity, "Test Billing: Purchase is in PENDING state", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    2 -> { // Simulated Error
                        clearPendingPurchase()
                        val errorMsg = "Simulated billing error: Payment declined by test card."
                        _billingState.value = BillingUiState.Error(errorMsg)
                        android.widget.Toast.makeText(activity, errorMsg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    3 -> { // Simulated Canceled
                        clearPendingPurchase()
                        Log.i(TAG, "Simulated user cancel.")
                        android.widget.Toast.makeText(activity, "Simulated purchase canceled", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(com.temulator.app.R.string.action_cancel) { _, _ ->
                clearPendingPurchase()
            }
            .setOnCancelListener {
                clearPendingPurchase()
            }
            .show()
    }

    fun simulateTestPurchase(obfuscatedAccountId: String, purchaseState: Int = 0) {
        if (!BuildConfig.DEBUG) return

        val now = System.currentTimeMillis()
        val orderId = "GPA.TEST-" + (now % 10000000)
        val purchaseToken = "test_token_" + java.util.UUID.randomUUID().toString()
        val json = org.json.JSONObject().apply {
            put("orderId", orderId)
            put("packageName", context.packageName)
            put("productId", VIP_PRODUCT_ID)
            put("purchaseTime", now)
            put("purchaseState", purchaseState)
            put("purchaseToken", purchaseToken)
            put("acknowledged", false)
            put("obfuscatedAccountId", obfuscatedAccountId)
        }.toString()

        try {
            val testPurchase = Purchase(json, "TEST_SIGNATURE")
            handlePurchase(testPurchase)
        } catch (e: Exception) {
            Log.w(TAG, "Direct test entitlement fallback: ${e.message}")
            val currentSession = authRepository.captureSession()
            val action = pendingPurchaseAction
            if (action != null && action.consumeIfMatches(currentSession.uid, currentSession.generation, beforeSuccess = {
                entitlementRepository.activateTestVip(currentSession.uid ?: "test_user", orderId)
            })) {
                pendingPurchaseAction = null
            }
        }
    }

    private fun launchBillingFlow(activity: Activity, details: ProductDetails, obfuscatedAccountId: String): Boolean {
        val productDetailsParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)

        // For subscription products, Google Play Billing requires offerToken
        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (offerToken != null) {
            productDetailsParamsBuilder.setOfferToken(offerToken)
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParamsBuilder.build()))
            .setObfuscatedAccountId(obfuscatedAccountId)
            .setObfuscatedProfileId(obfuscatedAccountId)
            .build()

        val response = billingClient.launchBillingFlow(activity, flowParams)
        return response.responseCode == BillingClient.BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                handlePurchase(purchase)
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.i(TAG, "User canceled billing purchase.")
            clearPendingPurchase()
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            Log.i(TAG, "Item already owned on Play Store. Querying purchases to verify and restore.")
            queryPurchases { result ->
                if (result is BillingQueryResult.Verified) {
                    val action = pendingPurchaseAction
                    val currentSession = authRepository.captureSession()
                    scope.launch(Dispatchers.Main) {
                        if (action != null && action.consumeIfMatches(currentSession.uid, currentSession.generation)) {
                            pendingPurchaseAction = null
                        }
                    }
                }
            }
        } else {
            Log.w(TAG, "onPurchasesUpdated error: ${billingResult.responseCode} - ${billingResult.debugMessage}")
            _billingState.value = BillingUiState.Error("Billing purchase error (${billingResult.responseCode}): ${billingResult.debugMessage}")
            clearPendingPurchase()
        }
    }

    private fun handlePurchase(purchase: Purchase, onVerified: ((Boolean) -> Unit)? = null) {
        if (purchase.products.any { it in VIP_PRODUCT_IDS }) {
            when (purchase.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> {
                    scope.launch {
                        // Send purchase token to EntitlementRepository for backend verification (F07, F08)
                        val verified = entitlementRepository.verifyAndAcknowledge(purchase, authRepository)
                        if (verified && !purchase.isAcknowledged) {
                            acknowledgePurchase(purchase.purchaseToken)
                        }
                        if (verified) {
                            withContext(Dispatchers.Main) {
                                val action = pendingPurchaseAction
                                val currentSession = authRepository.captureSession()
                                if (action != null && action.consumeIfMatches(currentSession.uid, currentSession.generation)) {
                                    pendingPurchaseAction = null
                                }
                            }
                        }
                        onVerified?.invoke(verified)
                    }
                }
                Purchase.PurchaseState.PENDING -> {
                    _billingState.value = BillingUiState.PurchasePending(purchase.purchaseToken)
                    onVerified?.invoke(false)
                }
                else -> {
                    Log.w(TAG, "Unhandled purchase state: ${purchase.purchaseState}")
                    onVerified?.invoke(false)
                }
            }
        } else {
            onVerified?.invoke(false)
        }
    }

    private fun acknowledgePurchase(purchaseToken: String) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.i(TAG, "Purchase acknowledged on Play Billing client.")
            } else {
                Log.e(TAG, "Failed to acknowledge purchase on client: ${result.debugMessage}")
            }
        }
    }

    /**
     * Queries purchases and verifies them with the backend server before reporting success (F07).
     */
    fun queryPurchases(onFinished: ((BillingQueryResult) -> Unit)? = null) {
        if (!billingClient.isReady) {
            startConnection(
                onReady = { queryPurchases(onFinished) },
                onError = { onFinished?.invoke(BillingQueryResult.Error(it)) }
            )
            return
        }

        val subsParams = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(subsParams) { subResult, subPurchases ->
            val inAppParams = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()

            billingClient.queryPurchasesAsync(inAppParams) inAppBlock@{ inAppResult, inAppPurchases ->
                val reduced = BillingQueryReducer.reduce(
                    subsResponseCode = subResult.responseCode,
                    subsPurchases = subPurchases,
                    inAppResponseCode = inAppResult.responseCode,
                    inAppPurchases = inAppPurchases
                )

                when (reduced) {
                    is BillingQueryResult.Error -> {
                        onFinished?.invoke(reduced)
                    }
                    is BillingQueryResult.NotOwned -> {
                        onFinished?.invoke(reduced)
                    }
                    is BillingQueryResult.Pending -> {
                        onFinished?.invoke(reduced)
                    }
                    is BillingQueryResult.Verified -> {
                        val allPurchases = (subPurchases.orEmpty() + inAppPurchases.orEmpty()).distinctBy { it.purchaseToken }
                        val vipPurchases = allPurchases.filter { purchase ->
                            purchase.products.any { it in VIP_PRODUCT_IDS } &&
                                purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                        }
                        if (vipPurchases.isEmpty()) {
                            onFinished?.invoke(BillingQueryResult.NotOwned)
                            return@inAppBlock
                        }

                        var verifiedCount = 0
                        var anySuccess = false
                        for (p in vipPurchases) {
                            handlePurchase(p) { success ->
                                synchronized(this@BillingRepository) {
                                    verifiedCount++
                                    if (success) anySuccess = true
                                    if (verifiedCount == vipPurchases.size) {
                                        val finalResult = if (anySuccess) BillingQueryResult.Verified() else BillingQueryResult.NotOwned
                                        onFinished?.invoke(finalResult)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun getFormattedPrice(): String? {
        val subPrice = vipProductDetails?.subscriptionOfferDetails
            ?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
        if (subPrice != null) return subPrice
        val offerDetails = vipProductDetails?.oneTimePurchaseOfferDetails
        return offerDetails?.formattedPrice
    }
}
