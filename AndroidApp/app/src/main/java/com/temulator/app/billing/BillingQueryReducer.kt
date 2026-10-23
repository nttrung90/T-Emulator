package com.temulator.app.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase

data class VerifiedPurchase(
    val orderId: String? = null,
    val productId: String = "vip_lifetime",
    val purchaseToken: String = "",
    val purchaseTime: Long = 0L,
    val isAcknowledged: Boolean = false,
    val purchaseState: Int = 1 // 1 = PURCHASED, 2 = PENDING
)

sealed interface BillingQueryResult {
    data class Verified(val purchase: VerifiedPurchase = VerifiedPurchase()) : BillingQueryResult
    object NotOwned : BillingQueryResult
    data class Pending(val purchaseToken: String) : BillingQueryResult
    data class Error(val message: String) : BillingQueryResult
}

sealed interface BillingPreflightResult {
    object Ready : BillingPreflightResult
    data class Unconfigured(val reason: String) : BillingPreflightResult
}

data class PendingPurchaseAction(
    val actionId: String = java.util.UUID.randomUUID().toString(),
    val userUid: String?,
    val userGeneration: Long,
    val onSuccess: () -> Unit
) {
    private val consumed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun consumeIfMatches(
        currentUid: String?,
        currentGeneration: Long,
        beforeSuccess: (() -> Unit)? = null
    ): Boolean {
        if (userUid != currentUid || userGeneration != currentGeneration) {
            return false
        }
        if (!consumed.compareAndSet(false, true)) {
            return false
        }
        beforeSuccess?.invoke()
        onSuccess()
        return true
    }
}

object BillingQueryReducer {

    val VIP_PRODUCT_IDS = setOf("vip_lifetime", "vip_yearly")

    fun checkPreflight(
        backendUrl: String?,
        userIsSignedIn: Boolean = true
    ): BillingPreflightResult {
        if (backendUrl.isNullOrBlank() || !backendUrl.startsWith("https://", ignoreCase = true)) {
            return BillingPreflightResult.Unconfigured("Backend verification server URL is unconfigured or not HTTPS.")
        }
        if (!userIsSignedIn) {
            return BillingPreflightResult.Unconfigured("User must be signed in before purchasing.")
        }
        return BillingPreflightResult.Ready
    }

    fun reduce(
        subsPurchases: List<VerifiedPurchase>?,
        inAppPurchases: List<VerifiedPurchase>?,
        subsErrorCode: String? = null,
        inAppErrorCode: String? = null
    ): BillingQueryResult {
        val subsHasError = subsErrorCode != null
        val inAppHasError = inAppErrorCode != null

        if (subsHasError && inAppHasError) {
            return BillingQueryResult.Error("Both SUBS and INAPP purchase queries failed: subs=$subsErrorCode, inapp=$inAppErrorCode")
        }

        val allPurchases = (subsPurchases.orEmpty() + inAppPurchases.orEmpty()).distinctBy { it.purchaseToken }
        val vipPurchases = allPurchases.filter { it.productId in VIP_PRODUCT_IDS }

        val purchased = vipPurchases.firstOrNull { it.purchaseState == 1 /* PURCHASED */ }
        if (purchased != null) {
            return BillingQueryResult.Verified(purchased)
        }

        val pending = vipPurchases.firstOrNull { it.purchaseState == 2 /* PENDING */ }
        if (pending != null) {
            return BillingQueryResult.Pending(pending.purchaseToken)
        }

        if (subsHasError) {
            return BillingQueryResult.Error(subsErrorCode ?: "SUBS purchase query failed")
        }
        if (inAppHasError) {
            return BillingQueryResult.Error(inAppErrorCode ?: "INAPP purchase query failed")
        }

        return BillingQueryResult.NotOwned
    }

    fun reduce(
        subsResponseCode: Int,
        subsPurchases: List<Purchase>?,
        inAppResponseCode: Int,
        inAppPurchases: List<Purchase>?
    ): BillingQueryResult {
        val subsErrorCode = if (subsResponseCode == BillingClient.BillingResponseCode.OK) null else "SUBS error (code $subsResponseCode)"
        val inAppErrorCode = if (inAppResponseCode == BillingClient.BillingResponseCode.OK) null else "INAPP error (code $inAppResponseCode)"

        val subsConverted = subsPurchases?.map { it.toVerifiedPurchase() }
        val inAppConverted = inAppPurchases?.map { it.toVerifiedPurchase() }

        return reduce(
            subsPurchases = subsConverted,
            inAppPurchases = inAppConverted,
            subsErrorCode = subsErrorCode,
            inAppErrorCode = inAppErrorCode
        )
    }
}

fun Purchase.toVerifiedPurchase(): VerifiedPurchase {
    val pid = products.firstOrNull { it in BillingQueryReducer.VIP_PRODUCT_IDS } ?: products.firstOrNull() ?: ""
    return VerifiedPurchase(
        orderId = orderId,
        productId = pid,
        purchaseToken = purchaseToken,
        purchaseTime = purchaseTime,
        isAcknowledged = isAcknowledged,
        purchaseState = purchaseState
    )
}
