package com.temulator.app.billing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.temulator.app.auth.AuthRepository
import com.temulator.app.auth.AuthSessionTracker
import com.temulator.app.auth.UserAccount
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/** Real repositories; Android preferences and the absent Firebase transport are external fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
class EntitlementRepositoryIntegrationAuditTest {
    private lateinit var auth: AuthRepository
    private lateinit var entitlement: EntitlementRepository
    private val uid = "integration_audit_user"

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        resetSingleton(AuthRepository::class.java)
        resetSingleton(EntitlementRepository::class.java)
        val context = PreferencesContext()
        auth = AuthRepository.getInstance(context)
        val trackerField = AuthRepository::class.java.getDeclaredField("sessionTracker").apply { isAccessible = true }
        (trackerField.get(auth) as AuthSessionTracker).updateUser(UserAccount(uid, "Audit", "audit@example.test", null))
        entitlement = EntitlementRepository.getInstance(context)
        entitlement.saveEntitlementMetadata(uid, true, "vip_lifetime", true, null, System.currentTimeMillis())
    }

    @After fun tearDown() {
        if (::entitlement.isInitialized) entitlement.revokeEntitlement(uid)
        resetSingleton(EntitlementRepository::class.java)
        resetSingleton(AuthRepository::class.java)
        Dispatchers.resetMain()
    }

    @Test fun missingBackendConfigurationMustNotBecomeAValidCachedCloudGrant() = runBlocking {
        assertFalse("ensureFresh must preserve configuration denial rather than re-granting a cached VIP", entitlement.ensureFreshEntitlement(auth))
    }

    @Test fun writingOtherUsersMetadataMustNotGrantCurrentUser() {
        entitlement.revokeEntitlement(uid)
        assertFalse(entitlement.isVipActive.value)
        entitlement.saveEntitlementMetadata("another_user", true, "vip_lifetime", true, null, System.currentTimeMillis())
        assertFalse("Metadata belonging to another UID cannot drive this session's VIP UI", entitlement.isVipActive.value)
    }

    @Test fun revokingOtherUsersCacheMustPreserveCurrentGrant() {
        assertTrue(entitlement.isVipActive.value)
        entitlement.revokeEntitlement("another_user")
        assertTrue("Targeted old-account cleanup must preserve the current user's grant", entitlement.isVipActive.value)
    }

    private fun resetSingleton(type: Class<*>) {
        type.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
    }

    private class PreferencesContext : ContextWrapper(null) {
        private val stores = mutableMapOf<String, SharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.temulator.app"
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = stores.getOrPut(name) {
            val values = mutableMapOf<String, Any?>()
            lateinit var preferences: SharedPreferences
            preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "contains" -> values.containsKey(args!![0])
                    "getBoolean", "getLong", "getString", "getInt", "getFloat", "getStringSet" -> values[args!![0]] ?: args[1]
                    "getAll" -> values.toMap()
                    "edit" -> {
                        val pending = mutableMapOf<String, Any?>()
                        val removed = mutableSetOf<String>()
                        var clear = false
                        lateinit var editor: SharedPreferences.Editor
                        fun commit() { if (clear) values.clear(); removed.forEach { values.remove(it) }; pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value } }
                        editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, editMethod, editArgs ->
                            when (editMethod.name) {
                                "putBoolean", "putLong", "putString", "putInt", "putFloat", "putStringSet" -> { pending[editArgs!![0] as String] = editArgs[1]; editor }
                                "remove" -> { removed.add(editArgs!![0] as String); editor }
                                "clear" -> { clear = true; editor }
                                "apply" -> { commit(); null }
                                "commit" -> { commit(); true }
                                else -> null
                            }
                        } as SharedPreferences.Editor
                        editor
                    }
                    else -> null
                }
            } as SharedPreferences
            preferences
        }
    }
}
