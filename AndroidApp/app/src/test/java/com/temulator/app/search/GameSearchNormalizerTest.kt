package com.temulator.app.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSearchNormalizerTest {

    @Test
    fun testNormalizeBasic() {
        assertEquals("super mario", GameSearchNormalizer.normalize("  Super Mario  "))
        assertEquals("pokemon", GameSearchNormalizer.normalize("POKEMON"))
    }

    @Test
    fun testNormalizeVietnameseDiacritics() {
        assertEquals("chuyen phi luu", GameSearchNormalizer.normalize("Chuyến Phi Lưu"))
        assertEquals("huyen thoai", GameSearchNormalizer.normalize("Huyền Thoại"))
        assertEquals("dung si rong", GameSearchNormalizer.normalize("Dũng Sĩ Rồng"))
    }

    @Test
    fun testNormalizeVietnameseD() {
        assertEquals("dau truong am duong", GameSearchNormalizer.normalize("Đấu Trường Âm Dương"))
        assertEquals("dong mau anh hung", GameSearchNormalizer.normalize("DÒNG MÁU ANH HÙNG"))
    }

    @Test
    fun testMatches() {
        val gameName = "Super Mario World (USA).sfc"
        assertTrue(GameSearchNormalizer.matches(gameName, ""))
        assertTrue(GameSearchNormalizer.matches(gameName, "   "))
        assertTrue(GameSearchNormalizer.matches(gameName, "mario"))
        assertTrue(GameSearchNormalizer.matches(gameName, "SUPER"))
        assertTrue(GameSearchNormalizer.matches(gameName, "world usa"))
        assertTrue(GameSearchNormalizer.matches(gameName, ".sfc"))
        assertFalse(GameSearchNormalizer.matches(gameName, "zelda"))
    }

    @Test
    fun testMatchesVietnamese() {
        val title = "Đấu La Đại Lục - Bản Tiếng Việt.gba"
        assertTrue(GameSearchNormalizer.matches(title, "dau la"))
        assertTrue(GameSearchNormalizer.matches(title, "tieng viet"))
        assertTrue(GameSearchNormalizer.matches(title, "ĐẤU LA"))
        assertTrue(GameSearchNormalizer.matches(title, "đại lục"))
        assertTrue(GameSearchNormalizer.matches(title, ".gba"))
        assertFalse(GameSearchNormalizer.matches(title, "pháp sư"))
    }
}
