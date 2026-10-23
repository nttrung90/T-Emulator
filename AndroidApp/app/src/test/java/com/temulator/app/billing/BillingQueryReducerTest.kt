package com.temulator.app.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingQueryReducerTest {

    @Test
    fun queryBothEmptyMustReduceToNotOwned() {
        val result = BillingQueryReducer.reduce(
            subsPurchases = emptyList(),
            inAppPurchases = emptyList(),
            subsErrorCode = null,
            inAppErrorCode = null
        )
        assertEquals(BillingQueryResult.NotOwned, result)
    }

    @Test
    fun queryOneErrorAndOneEmptyMustReduceToError() {
        val result = BillingQueryReducer.reduce(
            subsPurchases = emptyList(),
            inAppPurchases = emptyList(),
            subsErrorCode = null,
            inAppErrorCode = "Billing unavailable (-1)"
        )
        assertTrue(result is BillingQueryResult.Error)
        assertEquals("Billing unavailable (-1)", (result as BillingQueryResult.Error).message)
    }

    @Test
    fun queryWithVerifiedPurchaseMustReduceToVerified() {
        val purchase = VerifiedPurchase(
            orderId = "GPA.1234-5678",
            productId = "vip_lifetime",
            purchaseToken = "valid-token-123",
            purchaseTime = 1234567890L,
            isAcknowledged = true
        )
        val result = BillingQueryReducer.reduce(
            subsPurchases = emptyList(),
            inAppPurchases = listOf(purchase),
            subsErrorCode = null,
            inAppErrorCode = null
        )
        assertTrue(result is BillingQueryResult.Verified)
        assertEquals("vip_lifetime", (result as BillingQueryResult.Verified).purchase.productId)
    }

    @Test
    fun pendingActionMatchingSessionMustConsumeSuccessfully() {
        var called = false
        val action = PendingPurchaseAction(
            userUid = "user_A",
            userGeneration = 1L,
            onSuccess = { called = true }
        )

        val consumed = action.consumeIfMatches("user_A", 1L)
        assertTrue(consumed)
        assertTrue(called)

        // Exactly once
        val consumedAgain = action.consumeIfMatches("user_A", 1L)
        assertFalse(consumedAgain)
    }

    @Test
    fun pendingActionMismatchedUidMustBeRejected() {
        var called = false
        val action = PendingPurchaseAction(
            userUid = "user_A",
            userGeneration = 1L,
            onSuccess = { called = true }
        )

        val consumed = action.consumeIfMatches("user_B", 1L)
        assertFalse(consumed)
        assertFalse(called)
    }

    @Test
    fun pendingActionMismatchedGenerationMustBeRejected() {
        var called = false
        val action = PendingPurchaseAction(
            userUid = "user_A",
            userGeneration = 1L,
            onSuccess = { called = true }
        )

        // Session ABA: user re-logged in, generation advanced to 2
        val consumed = action.consumeIfMatches("user_A", 2L)
        assertFalse(consumed)
        assertFalse(called)
    }

    @Test
    fun preflightCheckMustFailClosedWhenBackendUrlIsMissing() {
        val result = BillingQueryReducer.checkPreflight(backendUrl = null)
        assertTrue(result is BillingPreflightResult.Unconfigured)
    }

    @Test
    fun preflightCheckMustPassWhenBackendUrlIsHttps() {
        val result = BillingQueryReducer.checkPreflight(backendUrl = "https://backend.example.com")
        assertEquals(BillingPreflightResult.Ready, result)
    }
}
