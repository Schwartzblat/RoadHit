package com.roadhit.game

import kotlin.math.abs

/** Decides which fields of a spoken answer match [song]. */
interface AnswerJudge {
    fun judge(song: Song, transcripts: List<String>, yearTolerance: Int): FieldMatches

    /**
     * Whether [transcript] as a whole is one spelling of [song]'s title or artist, nothing more
     * (Ruling R37: then a command phrase gives way to it as an answer).
     */
    fun isWholeName(song: Song, transcript: String): Boolean
}

/**
 * Judges on the phone: a field is right if any recognizer alternative contains, as close
 * whole words, any spelling of it (Hebrew, English, aliases). Each alternative and each spelling
 * is also tried with numbers rewritten (digits to English or Hebrew words, and words to digits),
 * so "7 rings", "seven rings" and "שבע רינגס" all meet.
 */
class LocalAnswerJudge : AnswerJudge {
    override fun judge(song: Song, transcripts: List<String>, yearTolerance: Int): FieldMatches {
        val heard = transcripts.flatMap { numberVariants(normalizeSpoken(it)) }.toSet()
        val year = heardYear(transcripts)
        return FieldMatches(
            title = matchesAny(heard, listOf(song.title.he, song.title.en) + song.titleAliases),
            artist = matchesAny(heard, listOf(song.artist.he, song.artist.en) + song.artistAliases),
            year = year != null && abs(year - song.year) <= yearTolerance,
        )
    }

    /** The name contains the transcript and the transcript contains the name, each as close whole words. */
    override fun isWholeName(song: Song, transcript: String): Boolean {
        val heard = numberVariants(normalizeSpoken(transcript))
        val spellings = listOf(song.title.he, song.title.en, song.artist.he, song.artist.en) + song.titleAliases + song.artistAliases
        return spellings.flatMap { needleVariants(normalize(it)) }.any { (name, allowed) ->
            heard.any { h -> containsClose(h, name, allowed) && containsClose(name, h) }
        }
    }

    private fun matchesAny(heard: Set<String>, spellings: List<String>): Boolean {
        val needles = spellings.flatMap { needleVariants(normalize(it)) }.toSet()
        return heard.any { h -> needles.any { (needle, allowed) -> containsClose(h, needle, allowed) } }
    }
}

/**
 * A spelling and its number variants, each with the edits it may spend: a rewritten variant may
 * spend no more than the original spelling ("seven rings" gets the edits of "7 rings"). Number
 * tokens themselves must match exactly (see [containsClose]), so "you 2" never accepts "you i".
 */
private fun needleVariants(spelling: String): List<Pair<String, Int>> {
    val original = MatchThresholds.allowedEdits(spelling.length)
    return numberVariants(spelling).map { v -> v to minOf(original, MatchThresholds.allowedEdits(v.length)) }
}

// ---- Number rewriting (normalized text in, normalized text out) ----

private val EN_NUMBER_WORDS: Map<Int, String> =
    (EN_UNITS + EN_TEENS + EN_TENS).entries.associate { (w, n) -> n to w }

private val HE_UNIT_WORDS = listOf("", "אחת", "שתיים", "שלוש", "ארבע", "חמש", "שש", "שבע", "שמונה", "תשע")
private val HE_TENS_WORDS: Map<Int, String> = HE_TENS.entries.associate { (w, n) -> n to w }

private fun englishWords(n: Int): String = when {
    n == 0 -> "zero"
    n < 20 || n % 10 == 0 -> EN_NUMBER_WORDS.getValue(n)
    else -> "${EN_NUMBER_WORDS.getValue(n / 10 * 10)} ${EN_NUMBER_WORDS.getValue(n % 10)}"
}

private fun hebrewWords(n: Int): String = when {
    n == 0 -> "אפס"
    n < 10 -> HE_UNIT_WORDS[n]
    n == 10 -> "עשר"
    n == 12 -> "שתים עשרה"
    n < 20 -> "${HE_UNIT_WORDS[n - 10]} עשרה"
    n % 10 == 0 -> HE_TENS_WORDS.getValue(n)
    else -> "${HE_TENS_WORDS.getValue(n / 10 * 10)} $HE_AND${HE_UNIT_WORDS[n % 10]}"
}

private val SMALL_NUMBER = Regex("[0-9]{1,2}")

/** [text] itself, plus versions with 1-2 digit numbers in English and Hebrew words and with number words as digits. */
private fun numberVariants(text: String): Set<String> {
    val tokens = text.split(' ').filter { it.isNotEmpty() }
    fun spellOut(words: (Int) -> String) =
        normalizeSpoken(tokens.joinToString(" ") { if (SMALL_NUMBER.matches(it)) words(it.toInt()) else it })
    return setOf(text, spellOut(::englishWords), spellOut(::hebrewWords), wordsToDigits(tokens))
}

private fun wordsToDigits(tokens: List<String>): String {
    val out = mutableListOf<String>()
    var i = 0
    while (i < tokens.size) {
        val number = (englishNumbers(tokens, i) + hebrewNumbers(tokens, i)).maxByOrNull { it.second }
        if (number == null) {
            out += tokens[i]
            i++
        } else {
            out += number.first.toString()
            i += number.second
        }
    }
    return out.joinToString(" ")
}
