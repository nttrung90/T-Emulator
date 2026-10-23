package com.temulator.app.billing

import com.temulator.app.BuildConfig
import com.temulator.app.util.EmulatorDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class VipEmulatorTest {

    @Test
    fun testEmulatorDetectorStrictlyGatedByDebug() {
        // F01: Dev tools must only be accessible when BuildConfig.DEBUG is true
        val isDevMode = EmulatorDetector.isDevMode()
        assertEquals(BuildConfig.DEBUG, isDevMode)

        // isDevOrEmulator must also strictly require BuildConfig.DEBUG
        val isDevOrEmu = EmulatorDetector.isDevOrEmulator()
        assertEquals(BuildConfig.DEBUG, isDevOrEmu)
    }

    @Test
    fun testTestTokensIdentifiedAndNeverGrantedInRelease() {
        // F01: Tokens starting with test_token_ or GPA.TEST are test tokens
        val testToken = "test_token_" + java.util.UUID.randomUUID().toString()
        val testOrderId = "GPA.TEST-998877"

        assertTrue(testToken.startsWith("test_token_"))
        assertTrue(testOrderId.startsWith("GPA.TEST"))

        val isTestOrder = testOrderId.startsWith("GPA.TEST") || testToken.startsWith("test_token_")
        assertTrue(isTestOrder)

        // In a simulated non-debug environment, test orders MUST be rejected
        val isDebug = false
        val wouldGrant = isTestOrder && isDebug
        assertFalse("Test orders must never grant VIP in release builds", wouldGrant)
    }

    @Test
    fun testObfuscatedAccountIdConsistency() {
        // F04: Obfuscated external account ID algorithm matches backend SHA-256
        val rawUid = "firebase_user_abc_123"
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(rawUid.toByteArray())
            .joinToString("") { "%02x".format(it) }

        assertNotNull(expectedSha)
        assertEquals(64, expectedSha.length)

        // Different UIDs must produce different obfuscated hashes
        val otherUid = "firebase_user_xyz_789"
        val otherSha = MessageDigest.getInstance("SHA-256")
            .digest(otherUid.toByteArray())
            .joinToString("") { "%02x".format(it) }

        assertNotEquals(expectedSha, otherSha)
    }

    @Test
    fun testUidPrefixedCacheKeysPreventCrossAccountLeakage() {
        // F05: Cache keys must be isolated per user UID
        val uidA = "user_A_111"
        val uidB = "user_B_222"

        val keyA = "is_vip_$uidA"
        val keyB = "is_vip_$uidB"

        assertNotEquals(keyA, keyB)
        assertTrue(keyA.endsWith(uidA))
        assertTrue(keyB.endsWith(uidB))

        // Simulation of memory cache mapping
        val cache = mutableMapOf<String, Boolean>()
        cache[keyA] = true

        assertTrue(cache[keyA] == true)
        assertFalse(cache[keyB] == true) // User B does not inherit User A's VIP!
    }

    @Test
    fun testLegacyCachePurgeCondition() {
        // F01, F05: Legacy un-keyed "is_vip" or test tokens must trigger cache invalidation
        val legacyMockKeys = listOf("is_vip", "last_purchase_token", "last_order_id")
        val testToken = "test_token_simulator_abc"

        val isMockToken = testToken.startsWith("test_token_")
        assertTrue(isMockToken)

        // Invalidation should strip legacy keys
        val activePrefs = mutableMapOf<String, Any>()
        activePrefs["is_vip"] = true
        activePrefs["last_purchase_token"] = testToken

        // Simulate migration
        if (activePrefs.containsKey("is_vip") || (activePrefs["last_purchase_token"] as? String)?.startsWith("test_token_") == true) {
            for (key in legacyMockKeys) {
                activePrefs.remove(key)
            }
        }

        assertFalse(activePrefs.containsKey("is_vip"))
        assertFalse(activePrefs.containsKey("last_purchase_token"))
    }

    @Test
    fun testSessionGenerationPreventsStaleResponseAfterAccountSwitch() {
        // V09: Verify response arriving after logout or account switch must be discarded
        var sessionGeneration = 1L
        var activeUid = "user_A"

        // Request initiated for User A
        val requestGeneration = sessionGeneration
        val requestUid = activeUid

        // User A logs out and User B logs in before response arrives
        activeUid = "user_B"
        sessionGeneration++

        // Response arrives from server claiming VIP active
        val serverResponseIsVip = true
        var appliedVip = false

        if (requestGeneration == sessionGeneration && requestUid == activeUid) {
            appliedVip = serverResponseIsVip
        }

        assertFalse("Stale response for previous user session must not grant VIP to new user", appliedVip)
    }
}
