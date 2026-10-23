package com.temulator.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GameOperationCoordinatorTest {

    private lateinit var coordinator: GameOperationCoordinator

    @Before
    fun setUp() {
        coordinator = GameOperationCoordinator.getInstance()
    }

    @Test
    fun exclusiveLeaseForSameGameMustRejectConcurrentAcquisition() {
        val gameId = "test_game_exclusive"
        val lease1 = coordinator.tryAcquire(gameId, GameLockType.EMULATOR_PLAYING)
        assertNotNull("First lease acquisition must succeed", lease1)
        assertTrue("Game must report busy while lease is active", coordinator.isGameBusy(gameId))
        assertEquals(GameLockType.EMULATOR_PLAYING, coordinator.getActiveOperation(gameId))

        // Second acquisition for same game must fail
        val lease2 = coordinator.tryAcquire(gameId, GameLockType.SNAPSHOT)
        assertNull("Second lease acquisition for same game must fail", lease2)

        // Release first lease
        lease1!!.release()
        assertFalse("Game must no longer be busy after release", coordinator.isGameBusy(gameId))
        assertNull(coordinator.getActiveOperation(gameId))

        // Subsequent acquisition must now succeed
        val lease3 = coordinator.tryAcquire(gameId, GameLockType.RESTORE)
        assertNotNull("Subsequent lease acquisition after release must succeed", lease3)
        lease3!!.release()
    }

    @Test
    fun independentGamesCanAcquireLeasesConcurrently() {
        val gameA = "game_A"
        val gameB = "game_B"

        val leaseA = coordinator.tryAcquire(gameA, GameLockType.EMULATOR_PLAYING)
        val leaseB = coordinator.tryAcquire(gameB, GameLockType.SNAPSHOT)

        assertNotNull("Lease for Game A must succeed", leaseA)
        assertNotNull("Lease for Game B must succeed", leaseB)

        assertTrue(coordinator.isGameBusy(gameA))
        assertTrue(coordinator.isGameBusy(gameB))

        leaseA!!.close()
        assertFalse(coordinator.isGameBusy(gameA))
        assertTrue(coordinator.isGameBusy(gameB))

        leaseB!!.close()
        assertFalse(coordinator.isGameBusy(gameB))
    }

    @Test
    fun globalRestoreLockSerializesCorrectly() {
        val lock1 = coordinator.acquireGlobalRestoreLock()
        assertNotNull(lock1)
        lock1.close()

        val lock2 = coordinator.acquireGlobalRestoreLock()
        assertNotNull(lock2)
        lock2.close()
    }
}
