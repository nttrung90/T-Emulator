package com.temulator.app.cloud

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

enum class GameLockType {
    EMULATOR_PLAYING,
    SNAPSHOT,
    RESTORE
}

class GameLease(
    val gameId: String,
    val lockType: GameLockType,
    private val releaseBlock: () -> Unit
) : AutoCloseable {
    @Volatile
    private var isReleased = false

    override fun close() {
        release()
    }

    fun release() {
        if (!isReleased) {
            isReleased = true
            releaseBlock()
        }
    }
}

/**
 * Coordinates exclusive access to games between native emulator execution,
 * snapshot generation, and restore commits.
 */
class GameOperationCoordinator private constructor() {
    private val gameLocks = ConcurrentHashMap<String, Semaphore>()
    private val activeOperations = ConcurrentHashMap<String, GameLockType>()
    private val globalRestoreLock = Semaphore(1, true)

    companion object {
        @Volatile
        private var INSTANCE: GameOperationCoordinator? = null

        fun getInstance(): GameOperationCoordinator {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GameOperationCoordinator().also { INSTANCE = it }
            }
        }
    }

    private fun getLockForGame(gameId: String): Semaphore {
        return gameLocks.getOrPut(gameId) { Semaphore(1, true) }
    }

    /**
     * Attempts to acquire exclusive lock for a game operation.
     * Returns a lease if successful, or null if the game is already busy.
     */
    fun tryAcquire(gameId: String, lockType: GameLockType): GameLease? {
        val sem = getLockForGame(gameId)
        if (sem.tryAcquire()) {
            activeOperations[gameId] = lockType
            return GameLease(gameId, lockType) {
                activeOperations.remove(gameId)
                sem.release()
            }
        }
        return null
    }

    fun isGameBusy(gameId: String): Boolean {
        val sem = getLockForGame(gameId)
        return sem.availablePermits() == 0 || activeOperations.containsKey(gameId)
    }

    fun getActiveOperation(gameId: String): GameLockType? {
        return activeOperations[gameId]
    }

    /**
     * Acquire global restore serialization lock to prevent parallel restores
     * from colliding or overwriting each other.
     */
    fun acquireGlobalRestoreLock(): AutoCloseable {
        globalRestoreLock.acquire()
        return AutoCloseable {
            globalRestoreLock.release()
        }
    }
}
