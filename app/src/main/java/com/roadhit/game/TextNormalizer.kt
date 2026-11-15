package com.roadhit.game

import java.text.Normalizer

private val DIRECTIONAL_MARKS = Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069\uFEFF]")
private val BRACKET_GROUPS = Regex("\\([^)]*\\)|\\[[^\\]]*]")
private val DASH_SUFFIX = Regex("\\s+[-\u2013\u2014]\\s+(.*)$")
private val SUFFIX_MARKER = Regex(
    "\\b(remaster|remastered|version|edit|mono|stereo|live|single|feat|with)\\b|\\b\\d{4}\\b"
)
private val COMBINING_MARKS = Regex("\\p{Mn}") // accents, niqqud, cantillation (maqaf is not Mn)
private val REMOVED_QUOTES = Regex("['\u2019\u05F3\"\u05F4]")
private val SEPARATORS = Regex("[-\u05BE]")
private val WHITESPACE = Regex("\\s+")

/** A leading "the" — English, the Hebrew article on its own, or "the" in Hebrew letters — is optional. */
private val LEADING_ARTICLES = setOf("the", "ה", "דה")

private val FINAL_LETTERS = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')

/**
 * Reduces a catalog title/artist spelling to a comparable form: lowercase, no marks or punctuation,
 * and no bracketed groups or " - remaster/year" suffixes.
 */
fun normalize(s: String): String = normalize(s, catalogSpelling = true)

/** Like [normalize] but for speech transcripts: brackets and dash suffixes are kept (they may hold a year). */
fun normalizeSpoken(s: String): String = normalize(s, catalogSpelling = false)

private fun normalize(s: String, catalogSpelling: Boolean): String {
    var t = s.replace(DIRECTIONAL_MARKS, "").lowercase()
    if (catalogSpelling) {
        t = t.replace(BRACKET_GROUPS, " ")
        t = t.replace(DASH_SUFFIX) { if (SUFFIX_MARKER.containsMatchIn(it.groupValues[1])) "" else it.value }
    }
    t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")
    t = t.replace(REMOVED_QUOTES, "")
    t = t.replace(SEPARATORS, " ")
    t = buildString {
        for (c in t) append(if (c.isLetterOrDigit() || c == ' ') FINAL_LETTERS[c] ?: c else ' ')
    }
    val words = t.split(WHITESPACE).filter { it.isNotEmpty() }
    val dropFirst = words.size > 1 && words[0] in LEADING_ARTICLES
    return (if (dropFirst) words.drop(1) else words).joinToString(" ")
}
