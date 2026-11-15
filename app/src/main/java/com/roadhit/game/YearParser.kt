package com.roadhit.game

private const val MIN_YEAR = 1900
private const val MAX_YEAR = 2026

// ---- Word tables (number words are in NumberWords.kt) ----

private val EN_ZERO_WORDS = setOf("oh", "zero")

private const val HE_THOUSAND = "אלפ"
private const val HE_TWO_THOUSAND = "אלפיימ"
private const val HE_NINE = "תשע"
private const val HE_HUNDREDS = "מאות"

// ---- Parsing ----

private val DIGITS_ONLY = Regex("[0-9]+")

/** [full]: four digits or a fully worded year; otherwise a two-digit number read as a year. */
private class Match(val year: Int, val length: Int, val full: Boolean = true)

/** Two-digit rule: 00-26 is 20xx, 27-99 is 19xx. */
private fun twoDigitYear(n: Int) = if (n <= MAX_YEAR - 2000) 2000 + n else 1900 + n

private fun numbers(tokens: List<String>, i: Int) = englishNumbers(tokens, i) + hebrewNumbers(tokens, i)

/** The longest number at [i] whose value is in [range], as (value, tokens used). */
private fun longestNumber(tokens: List<String>, i: Int, range: IntRange, numbersAt: (List<String>, Int) -> List<Pair<Int, Int>>) =
    numbersAt(tokens, i).filter { it.first in range }.maxByOrNull { it.second }

private fun yearMatchesAt(tokens: List<String>, i: Int): List<Match> {
    val matches = mutableListOf<Match>()
    val token = tokens[i]

    if (DIGITS_ONLY.matches(token)) {
        when (token.length) {
            4 -> matches += Match(token.toInt(), 1)
            2 -> matches += Match(twoDigitYear(token.toInt()), 1, full = false)
        }
        return matches.filter { it.year in MIN_YEAR..MAX_YEAR }
    }

    // English: "two thousand [and] [N]"
    if (token == "two" && tokens.getOrNull(i + 1) == "thousand") {
        matches += Match(2000, 2)
        val j = if (tokens.getOrNull(i + 2) == "and") i + 3 else i + 2
        longestNumber(tokens, j, 1..(MAX_YEAR - 2000), ::englishNumbers)?.let { (n, used) ->
            matches += Match(2000 + n, j - i + used)
        }
    }
    // English: "nineteen XX" / "nineteen oh N" and "twenty XX" / "twenty oh N"
    if (token == "nineteen" || token == "twenty") {
        val century = if (token == "nineteen") 1900 else 2000
        val last = if (token == "nineteen") 99 else MAX_YEAR - 2000
        if (tokens.getOrNull(i + 1) in EN_ZERO_WORDS) {
            EN_UNITS[tokens.getOrNull(i + 2)]?.let { matches += Match(century + it, 3) }
        }
        longestNumber(tokens, i + 1, 10..last, ::englishNumbers)?.let { (n, used) ->
            matches += Match(century + n, 1 + used)
        }
    }

    // Hebrew: "אלפיים [ו]N"
    if (heWord(tokens, i) == HE_TWO_THOUSAND) {
        matches += Match(2000, 1)
        longestNumber(tokens, i + 1, 1..(MAX_YEAR - 2000), ::hebrewNumbers)?.let { (n, used) ->
            matches += Match(2000 + n, 1 + used)
        }
    }
    // Hebrew: "אלף תשע מאות [ו]XX"
    if (heWord(tokens, i) == HE_THOUSAND && heWord(tokens, i + 1) == HE_NINE && heWord(tokens, i + 2) == HE_HUNDREDS) {
        matches += Match(1900, 3)
        longestNumber(tokens, i + 3, 1..99, ::hebrewNumbers)?.let { (n, used) ->
            matches += Match(1900 + n, 3 + used)
        }
    }

    // A bare two-digit number in words (units alone are too ambiguous to be a year)
    longestNumber(tokens, i, 10..99, ::numbers)?.let { (n, used) -> matches += Match(twoDigitYear(n), used, full = false) }

    return matches.filter { it.year in MIN_YEAR..MAX_YEAR }
}

/** A year found in a transcript; [full] as in [Match]. */
internal data class SpokenYear(val year: Int, val full: Boolean)

/** Every year found in [transcript], in spoken order, with whether it was said in full. */
internal fun parseSpokenYears(transcript: String): List<SpokenYear> {
    val tokens = normalizeSpoken(transcript).split(' ').filter { it.isNotEmpty() }
    val years = mutableListOf<SpokenYear>()
    var i = 0
    while (i < tokens.size) {
        val best = yearMatchesAt(tokens, i).maxByOrNull { it.length }
        if (best == null) {
            i++
        } else {
            years += SpokenYear(best.year, best.full)
            i += best.length
        }
    }
    return years
}

/** Every year found in [transcript], in spoken order. */
fun parseYears(transcript: String): List<Int> = parseSpokenYears(transcript).map { it.year }

/**
 * The last full year of the first alternative that has one; failing that, the last
 * (two-digit) year of the first alternative that has any. Recognizers sometimes truncate
 * the year in the top alternative ("203") while a later one is complete ("2003").
 */
fun heardYear(transcripts: List<String>): Int? {
    val parsed = transcripts.map(::parseSpokenYears)
    return parsed.firstNotNullOfOrNull { years -> years.lastOrNull { it.full }?.year }
        ?: parsed.firstNotNullOfOrNull { it.lastOrNull()?.year }
}
