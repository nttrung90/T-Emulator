package com.temulator.app.search

import java.text.Normalizer
import java.util.Locale
import java.util.regex.Pattern

object GameSearchNormalizer {

    private val DIACRITICS_PATTERN: Pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+")

    /**
     * Normalizes text for case-insensitive, diacritic-insensitive search.
     * Supports Vietnamese diacritics removal and specific mappings (đ -> d, Đ -> d).
     */
    fun normalize(input: String?): String {
        if (input.isNullOrBlank()) return ""

        var s = input.trim().lowercase(Locale.ROOT)
        // Specific Vietnamese character mappings
        s = s.replace('đ', 'd')
            .replace('Đ', 'd')

        // Decompose unicode characters into base letters + combining marks
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFD)
        // Strip all combining diacritical marks
        val stripped = DIACRITICS_PATTERN.matcher(decomposed).replaceAll("")

        return stripped
    }

    /**
     * Checks if target string matches query using normalized tokens.
     */
    fun matches(target: String?, query: String?): Boolean {
        val normQuery = normalize(query)
        if (normQuery.isEmpty()) return true
        val normTarget = normalize(target)
        if (normTarget.isEmpty()) return false

        // Split query into tokens to support multi-word search
        val queryTokens = normQuery.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        return queryTokens.all { token -> normTarget.contains(token) }
    }
}
