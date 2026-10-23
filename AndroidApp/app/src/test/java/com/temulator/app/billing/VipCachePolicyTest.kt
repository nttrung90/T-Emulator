package com.temulator.app.billing

import com.temulator.app.auth.AuthSessionTracker
import com.temulator.app.auth.UserAccount
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class VipCachePolicyTest {

    private val now = 1_700_000_000_000L

    // --- VipCachePolicy Pure Rules ---

    @Test
    fun testYearlyValidWithinTtlAndExpiry() {
        val record = VipCacheRecord(
            isVip = true,
            productId = "vip_yearly",
            isLifetime = false,
            expiryTimeMillis = now + 86_400_000L,
            verifiedAt = now - 3_600_000L
        )
        assertTrue("Yearly within both TTL and expiry must be valid", VipCachePolicy.isValid(record, now))
    }

    @Test
    fun testYearlyExpiredDuringTtlIsDenied() {
        val record = VipCacheRecord(
            isVip = true,
            productId = "vip_yearly",
            isLifetime = false,
            expiryTimeMillis = now - 1_000L,
            verifiedAt = now - 3_600_000L
        )
        assertFalse("Yearly expired subscription must be denied even if verifiedAt is within TTL", VipCachePolicy.isValid(record, now))
    }

    @Test
    fun testLifetimeValidWithinTtl() {
        val record = VipCacheRecord(
            isVip = true,
            productId = "vip_lifetime",
            isLifetime = true,
            expiryTimeMillis = null,
            verifiedAt = now - 3_600_000L
        )
        assertTrue("Lifetime within TTL must be valid", VipCachePolicy.isValid(record, now))
    }

    @Test
    fun testLifetimeTtlExpiredIsDenied() {
        val record = VipCacheRecord(
            isVip = true,
            productId = "vip_lifetime",
            isLifetime = true,
            expiryTimeMillis = null,
            verifiedAt = now - (VipCachePolicy.DEFAULT_TTL_MILLIS + 10_000L)
        )
        assertFalse("Lifetime record exceeding TTL must be denied until refreshed", VipCachePolicy.isValid(record, now))
    }

    @Test
    fun testClockRollbackDenied() {
        val record = VipCacheRecord(
            isVip = true,
            productId = "vip_lifetime",
            isLifetime = true,
            expiryTimeMillis = null,
            verifiedAt = now + 60_000L
        )
        assertFalse("Clock rollback where verifiedAt > now must be denied", VipCachePolicy.isValid(record, now))
    }

    @Test
    fun testLegacyMissingMetadataDenied() {
        val legacyYearlyMissingExpiry = VipCacheRecord(
            isVip = true,
            productId = "vip_yearly",
            isLifetime = false,
            expiryTimeMillis = null,
            verifiedAt = now - 1_000L
        )
        assertFalse("Legacy yearly with null expiry must be denied", VipCachePolicy.isValid(legacyYearlyMissingExpiry, now))

        val legacyZeroVerifiedAt = VipCacheRecord(
            isVip = true,
            productId = "vip_lifetime",
            isLifetime = true,
            expiryTimeMillis = null,
            verifiedAt = 0L
        )
        assertFalse("Legacy missing verifiedAt must be denied", VipCachePolicy.isValid(legacyZeroVerifiedAt, now))
    }

    // --- Production AuthSessionTracker Tests ---

    @Test
    fun testAuthSessionTrackerMonotonicAcrossLogoutAndLogin() {
        val userA = UserAccount("user_A", "User A", "a@example.com", null)
        val tracker = AuthSessionTracker(null)

        val s0 = tracker.captureSession()
        assertNull(s0.uid)

        // Login User A
        val s1 = tracker.updateUser(userA)
        assertEquals("user_A", s1.uid)
        assertTrue(s1.generation > s0.generation)

        // Logout
        val s2 = tracker.invalidateSession()
        assertNull(s2.uid)
        assertTrue(s2.generation > s1.generation)

        // Login User A again (A -> logout -> A)
        val s3 = tracker.updateUser(userA)
        assertEquals("user_A", s3.uid)
        assertTrue(s3.generation > s2.generation)

        // Generations are strictly monotonic and distinct
        assertNotEquals(s1.generation, s2.generation)
        assertNotEquals(s2.generation, s3.generation)
        assertNotEquals(s1.generation, s3.generation)
    }

    @Test
    fun testAuthSessionTrackerSameUidProfileUpdatePreservesGeneration() {
        val userA1 = UserAccount("user_A", "Name 1", "a@example.com", null)
        val userA2 = UserAccount("user_A", "Name 2", "a@example.com", "https://photo.url")
        val tracker = AuthSessionTracker(userA1)

        val s1 = tracker.captureSession()
        val s2 = tracker.updateUser(userA2)

        assertEquals("Same UID must preserve generation to avoid invalidating requests", s1.generation, s2.generation)
        assertEquals("Name 2", s2.user?.displayName)
    }

    @Test
    fun testAuthSessionTrackerUserSwitchIncrementsGeneration() {
        val userA = UserAccount("user_A", "User A", "a@example.com", null)
        val userB = UserAccount("user_B", "User B", "b@example.com", null)
        val tracker = AuthSessionTracker(userA)

        val sA = tracker.captureSession()
        val sB = tracker.updateUser(userB)

        assertEquals("user_B", sB.uid)
        assertTrue(sB.generation > sA.generation)
        assertFalse(tracker.isCurrent(sA))
        assertTrue(tracker.isCurrent(sB))
    }

    @Test
    fun testAuthSessionTrackerCommitIfCurrentGuardsAtomicApplication() {
        val userA = UserAccount("user_A", "User A", "a@example.com", null)
        val userB = UserAccount("user_B", "User B", "b@example.com", null)
        val tracker = AuthSessionTracker(userA)

        val capturedSessionA = tracker.captureSession()

        // User switches to B while in-flight request was pending
        tracker.updateUser(userB)

        var committed = false
        val result = tracker.commitIfCurrent(capturedSessionA) {
            committed = true
            "applied"
        }

        assertNull("Stale session must not commit", result)
        assertFalse("Stale action must not execute", committed)

        val currentSessionB = tracker.captureSession()
        val resultB = tracker.commitIfCurrent(currentSessionB) {
            "applied_for_b"
        }
        assertEquals("Current session must commit", "applied_for_b", resultB)
    }

    // --- Production VipMetadataDecoder Tests ---

    @Test
    fun testVipMetadataDecoderYearlyExplicit() {
        val futureExpiry = now + 86_400_000L
        val json = JSONObject().apply {
            put("isVip", true)
            put("productId", "vip_yearly")
            put("isLifetime", false)
            put("expiryTimeMillis", futureExpiry)
        }
        val result = VipMetadataDecoder.decode(json, now)
        assertTrue("Explicit yearly must decode to Success", result is VipMetadataDecodeResult.Success)
        val record = (result as VipMetadataDecodeResult.Success).record
        assertEquals("vip_yearly", record.productId)
        assertFalse(record.isLifetime)
        assertEquals(futureExpiry, record.expiryTimeMillis)
    }

    @Test
    fun testVipMetadataDecoderLifetimeExplicit() {
        val json = JSONObject().apply {
            put("isVip", true)
            put("productId", "vip_lifetime")
            put("isLifetime", true)
        }
        val result = VipMetadataDecoder.decode(json, now)
        assertTrue("Explicit lifetime must decode to Success", result is VipMetadataDecodeResult.Success)
        val record = (result as VipMetadataDecodeResult.Success).record
        assertEquals("vip_lifetime", record.productId)
        assertTrue(record.isLifetime)
        assertNull(record.expiryTimeMillis)
    }

    @Test
    fun testVipMetadataDecoderMissingMetadataDoesNotAssumeLifetime() {
        // Old or malformed JSON with isVip=true but no metadata: MUST NOT assume lifetime
        val json = JSONObject().apply {
            put("isVip", true)
        }
        val result = VipMetadataDecoder.decode(json, now)
        assertTrue("Missing metadata must not assume lifetime and must fail closed as Invalid", result is VipMetadataDecodeResult.Invalid)

        val yearlyMissingExpiry = JSONObject().apply {
            put("isVip", true)
            put("productId", "vip_yearly")
            put("isLifetime", false)
        }
        val result2 = VipMetadataDecoder.decode(yearlyMissingExpiry, now)
        assertTrue("Yearly missing expiry must fail closed as Invalid", result2 is VipMetadataDecodeResult.Invalid)
    }

    @Test
    fun testVipMetadataDecoderServerFalse() {
        val json = JSONObject().apply {
            put("isVip", false)
        }
        val result = VipMetadataDecoder.decode(json, now)
        assertTrue("isVip=false must decode to Inactive", result is VipMetadataDecodeResult.Inactive)
    }

    // --- Production RefreshClassifier Tests ---

    @Test
    fun testRefreshClassifierClassifiesResponsesProperly() {
        val futureExpiry = now + 86_400_000L
        val successBody = JSONObject().apply {
            put("isVip", true)
            put("productId", "vip_yearly")
            put("isLifetime", false)
            put("expiryTimeMillis", futureExpiry)
        }.toString()

        val grantDecision = RefreshClassifier.classifyResponse(200, successBody, now)
        assertTrue("Valid 200 must be Grant", grantDecision is RefreshDecision.Grant)

        val deny401 = RefreshClassifier.classifyResponse(401, null, now)
        assertTrue("401 must be Deny", deny401 is RefreshDecision.Deny)

        val deny403 = RefreshClassifier.classifyResponse(403, null, now)
        assertTrue("403 must be Deny", deny403 is RefreshDecision.Deny)

        val deny404 = RefreshClassifier.classifyResponse(404, null, now)
        assertTrue("404 must be Deny", deny404 is RefreshDecision.Deny)

        val fallback500 = RefreshClassifier.classifyResponse(500, null, now)
        assertTrue("500 must be TransportFallback", fallback500 is RefreshDecision.TransportFallback)

        val fallback503 = RefreshClassifier.classifyResponse(503, null, now)
        assertTrue("503 must be TransportFallback", fallback503 is RefreshDecision.TransportFallback)

        val parseErrorBody = "NOT_A_JSON_STRING"
        val denyParseError = RefreshClassifier.classifyResponse(200, parseErrorBody, now)
        assertTrue("JSON parse error must be Deny, never fallback", denyParseError is RefreshDecision.Deny)
    }

    @Test
    fun testRefreshClassifierClassifiesExceptionsProperly() {
        val ioEx = IOException("Connection reset by peer")
        val ioDecision = RefreshClassifier.classifyException(ioEx)
        assertTrue("IOException must be TransportFallback", ioDecision is RefreshDecision.TransportFallback)

        val illegalArg = IllegalArgumentException("Malformed URL")
        val argDecision = RefreshClassifier.classifyException(illegalArg)
        assertTrue("Non-IO exception must be Deny, not transport fallback", argDecision is RefreshDecision.Deny)
    }
}
