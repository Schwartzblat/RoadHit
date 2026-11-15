package com.roadhit.game

// Number words shared by the year and voice parsers.
// Hebrew is written in normalized form: final letters mapped to regular.

internal val EN_UNITS = mapOf(
    "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
    "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
)
internal val EN_TEENS = mapOf(
    "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
    "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
)
internal val EN_TENS = mapOf(
    "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
    "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
)
internal val HE_UNITS = mapOf(
    "אחד" to 1, "אחת" to 1,
    "שני" to 2, "שתי" to 2,
    "שתיימ" to 2, "שניימ" to 2, "שתימ" to 2, "שנימ" to 2,
    "שלוש" to 3, "שלושה" to 3,
    "ארבע" to 4, "ארבעה" to 4,
    "חמש" to 5, "חמישה" to 5,
    "שש" to 6, "שישה" to 6,
    "שבע" to 7, "שבעה" to 7,
    "שמונה" to 8,
    "תשע" to 9, "תשעה" to 9,
)
internal val HE_TEN_WORDS = setOf("עשר", "עשרה")
internal val HE_TENS = mapOf(
    "עשרימ" to 20, "שלושימ" to 30, "ארבעימ" to 40, "חמישימ" to 50,
    "שישימ" to 60, "שבעימ" to 70, "שמונימ" to 80, "תשעימ" to 90,
)
internal const val HE_AND = "ו"

/** Hebrew token with a leading conjunction "ו" removed (no table word starts with it). */
internal fun heWord(tokens: List<String>, i: Int): String? =
    tokens.getOrNull(i)?.let { if (it.length > 1 && it.startsWith(HE_AND)) it.drop(1) else it }

/** Every way to read a 1-99 number in English words at [i], as (value, tokens used). */
internal fun englishNumbers(tokens: List<String>, i: Int): List<Pair<Int, Int>> {
    val w = tokens.getOrNull(i) ?: return emptyList()
    EN_TENS[w]?.let { tens ->
        val unit = EN_UNITS[tokens.getOrNull(i + 1)]
        return if (unit != null) listOf(tens to 1, tens + unit to 2) else listOf(tens to 1)
    }
    EN_TEENS[w]?.let { return listOf(it to 1) }
    EN_UNITS[w]?.let { return listOf(it to 1) }
    return emptyList()
}

/** Every way to read a 1-99 number in Hebrew words at [i], as (value, tokens used). */
internal fun hebrewNumbers(tokens: List<String>, i: Int): List<Pair<Int, Int>> {
    val w = heWord(tokens, i) ?: return emptyList()
    HE_TENS[w]?.let { tens ->
        val unit = HE_UNITS[heWord(tokens, i + 1)]
        return if (unit != null) listOf(tens to 1, tens + unit to 2) else listOf(tens to 1)
    }
    HE_UNITS[w]?.let { unit ->
        return if (heWord(tokens, i + 1) in HE_TEN_WORDS) listOf(unit to 1, 10 + unit to 2) else listOf(unit to 1)
    }
    if (w in HE_TEN_WORDS) return listOf(10 to 1)
    return emptyList()
}
