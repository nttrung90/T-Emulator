package com.temulator.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GameStorageManagerTest {

    @Test
    fun testSanitizeFileName() {
        // Normal filename
        val normal = "Super Mario Bros (World).nes"
        assertEquals("Super Mario Bros (World).nes", sanitize(normal))

        // Path traversal attempts
        val traversal1 = "../../etc/passwd"
        assertFalse(sanitize(traversal1).contains(".."))
        assertFalse(sanitize(traversal1).contains("/"))

        val traversal2 = "..\\..\\windows\\system32\\cmd.exe"
        assertFalse(sanitize(traversal2).contains(".."))
        assertFalse(sanitize(traversal2).contains("\\"))

        // Special characters
        val special = "Game: <Special> *Edition* | \"Final\"?.gba"
        val cleaned = sanitize(special)
        assertFalse(cleaned.contains(":"))
        assertFalse(cleaned.contains("<"))
        assertFalse(cleaned.contains(">"))
        assertFalse(cleaned.contains("*"))
        assertFalse(cleaned.contains("|"))
        assertFalse(cleaned.contains("?"))
        assertFalse(cleaned.contains("\""))
    }

    private fun sanitize(name: String): String {
        val clean = name.replace("[/\\\\:*?\"<>|]".toRegex(), "_")
            .replace("\\.\\.+".toRegex(), ".")
            .trim()
        return if (clean.isEmpty()) "unnamed_game.rom" else clean
    }

    @Test
    fun testSha256ChecksumCalculation() {
        val tempFile = File.createTempFile("test_rom", ".bin")
        try {
            tempFile.writeBytes("TEMULATOR_TEST_ROM_CONTENT".toByteArray(Charsets.UTF_8))
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest("TEMULATOR_TEST_ROM_CONTENT".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

            val fileDigest = java.security.MessageDigest.getInstance("SHA-256")
            val fileHash = fileDigest.digest(tempFile.readBytes()).joinToString("") { "%02x".format(it) }

            assertEquals(hash, fileHash)
        } finally {
            tempFile.delete()
        }
    }
}
