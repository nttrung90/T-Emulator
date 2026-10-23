package com.temulator.app.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Production centralized session tracker managing user sessions and monotonic generations.
 * Guarantees thread-safe atomic session capture, validation, and transitions.
 */
class AuthSessionTracker(initialUser: UserAccount? = null) {
    private val generationCounter = AtomicLong(1L)
    private val lock = Any()

    private val _sessionState = MutableStateFlow(
        UserSession(initialUser?.uid, generationCounter.get(), initialUser)
    )
    val sessionState: StateFlow<UserSession> = _sessionState.asStateFlow()

    fun captureSession(): UserSession = synchronized(lock) {
        _sessionState.value
    }

    fun isCurrent(session: UserSession): Boolean = synchronized(lock) {
        val current = _sessionState.value
        current.generation == session.generation && current.uid == session.uid
    }

    /**
     * Updates session on login or profile update.
     * Monotonic generation increases if UID changed or transitioning from unauthenticated.
     * Same UID metadata updates preserve current generation to avoid invalidating in-flight requests.
     */
    fun updateUser(user: UserAccount?): UserSession = synchronized(lock) {
        val current = _sessionState.value
        val newGen = if (current.uid != user?.uid) {
            generationCounter.incrementAndGet()
        } else {
            current.generation
        }
        val newSession = UserSession(user?.uid, newGen, user)
        _sessionState.value = newSession
        newSession
    }

    /**
     * Atomically invalidates session on logout or explicit sign-out.
     * Always increments generation so any in-flight requests or callbacks are immediately discarded.
     */
    fun invalidateSession(): UserSession = synchronized(lock) {
        val newGen = generationCounter.incrementAndGet()
        val newSession = UserSession(null, newGen, null)
        _sessionState.value = newSession
        newSession
    }

    /**
     * Executes [block] atomically only if [expectedSession] is still strictly current.
     */
    fun <T> commitIfCurrent(expectedSession: UserSession, block: () -> T): T? = synchronized(lock) {
        if (!isCurrent(expectedSession)) return null
        block()
    }
}
