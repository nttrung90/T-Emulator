package com.temulator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GameRepositoryAntiCollisionTest {

    @Test
    fun testStableDeterministicGameId() {
        val uri1 = "content://com.android.externalstorage.documents/tree/primary%3AROMs/document/primary%3AROMs%2Fmario.nes"
        val id1 = GameRepository.generateGameId(uri1)
        val id2 = GameRepository.generateGameId(uri1)

        // Must be stable across invocations
        assertEquals(id1, id2)
    }

    @Test
    fun testTwoDifferentGamesWithSameFilenameDoNotCollide() {
        val gameUriA = "content://com.android.externalstorage.documents/tree/primary%3AROMs_FolderA/document/game.nes"
        val gameUriB = "content://com.android.externalstorage.documents/tree/primary%3AROMs_FolderB/document/game.nes"

        val idA = GameRepository.generateGameId(gameUriA)
        val idB = GameRepository.generateGameId(gameUriB)

        // Both games share "game.nes" as fileName, but their identities are distinct
        assertNotEquals(idA, idB)
    }

    @Test
    fun testConsoleTypeDetection() {
        assertEquals(0, GameRepository.extensionToConsoleType("sfc"))
        assertEquals(1, GameRepository.extensionToConsoleType("gb"))
        assertEquals(1, GameRepository.extensionToConsoleType("gbc"))
        assertEquals(2, GameRepository.extensionToConsoleType("nes"))
        assertEquals(3, GameRepository.extensionToConsoleType("pce"))
        assertEquals(4, GameRepository.extensionToConsoleType("sms"))
        assertEquals(5, GameRepository.extensionToConsoleType("gba"))
        assertEquals(6, GameRepository.extensionToConsoleType("ws"))

        // Inside zip / 7z
        assertEquals(2, GameRepository.extensionToConsoleType("zip", "Super Mario (NES).zip"))
        assertEquals(5, GameRepository.extensionToConsoleType("zip", "Pokemon Emerald (GBA).zip"))
        assertEquals(0, GameRepository.extensionToConsoleType("7z", "Chrono Trigger.sfc.7z"))
    }
}
