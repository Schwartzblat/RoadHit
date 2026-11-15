package com.roadhit.game

// Turns speech-recognizer transcripts into commands and setup answers.
// The Hebrew recognizer writes English words in Hebrew letters, so every keyword also has that spelling.

enum class VoiceCommand { PASS, AGAIN, STOP_GAME, SCORE }

sealed interface GenreAnswer {
    object All : GenreAnswer
    object WhichOnes : GenreAnswer
    data class One(val genre: Genre) : GenreAnswer
}

enum class Choice { FIRST, SECOND }

// ---- Phrase tables (compared in normalized form) ----

private fun phrases(vararg p: String) = p.map(::normalize).toSet()

private val COMMAND_PHRASES: Map<VoiceCommand, Set<String>> = mapOf(
    VoiceCommand.PASS to phrases(
        "pass", "skip", "דלג", "פס", "לדלג", "פאס", "סקיפ", "next", "נקסט", "הבא", "הלאה",
    ),
    VoiceCommand.AGAIN to phrases(
        "again", "repeat", "שוב", "עוד פעם", "replay", "one more time", "אגיין", "ריפיט",
        "תנגן שוב", "עוד הפעם",
    ),
    VoiceCommand.STOP_GAME to phrases(
        "stop game", "stop the game", "עצור משחק", "עצור את המשחק", "סיים משחק", "סטופ גיים",
        "end game", "quit game", "תפסיק את המשחק", "לסיים משחק", "סוף משחק",
    ),
    VoiceCommand.SCORE to phrases(
        "score", "scores", "ניקוד", "תוצאות", "סקור", "מה הניקוד", "מה התוצאה",
        "what's the score", "what is the score",
    ),
)

private val POOL_PHRASES: Map<Pool, Set<String>> = mapOf(
    Pool.ISRAELI to phrases("ישראלי", "ישראלים", "ישראליים", "עברית", "ישראל", "hebrew", "israeli"),
    Pool.INTERNATIONAL to phrases(
        "לועזי", "לועזיים", "international", "בינלאומי", "בינלאומיים", "חו\"ל", "אנגלית",
        "אינטרנשיונל", "foreign", "english",
    ),
    Pool.MIXED to phrases(
        "מעורב", "גם וגם", "mixed", "מיקס", "מיקסד", "הכל", "both", "everything", "all",
    ),
)

private val GENRE_PHRASES: Map<Genre, Set<String>> = mapOf(
    Genre.POP to phrases("pop", "פופ"),
    Genre.ROCK to phrases("rock", "רוק"),
    Genre.HIPHOP to phrases("hip hop", "hiphop", "rap", "היפ הופ", "היפהופ", "ראפ"),
    Genre.DANCE to phrases("dance", "electronic", "דאנס", "אלקטרוני", "אלקטרונית", "מסיבות"),
    Genre.RNB to phrases("r&b", "rnb", "r and b", "soul", "אר אנד בי", "סול"),
    Genre.MIZRAHI to phrases("mizrahi", "מזרחי", "מזרחית", "ים תיכוני"),
    Genre.OTHER to phrases("other", "אחר"),
)
private val GENRE_ALL = phrases("all", "any", "everything", "הכל", "כולם", "לא משנה", "אול")
private val GENRE_WHICH = phrases(
    "which", "which ones", "what are the options", "איזה", "איזה יש", "מה יש", "מה האפשרויות",
)

private val DECADES_ALL = phrases("all", "הכל", "כל השנים", "everything", "any")

private const val FIRST_DECADE = 1940
private const val LAST_DECADE = 2020

private val EN_DECADES = mapOf(
    "forties" to 1940, "fifties" to 1950, "sixties" to 1960, "seventies" to 1970,
    "eighties" to 1980, "nineties" to 1990, "noughties" to 2000,
)
private val HE_DECADES = mapOf(
    "ארבעימ" to 1940, "חמישימ" to 1950, "שישימ" to 1960, "שבעימ" to 1970,
    "שמונימ" to 1980, "תשעימ" to 1990, "אלפיימ" to 2000, "עשרה" to 2010, "עשרימ" to 2020,
)
private const val HE_TWO_THOUSAND_WORD = "אלפיימ"
private val HE_DECADE_PREFIXES = listOf("מה", "לה", "ה", "מ", "ל", "ו")
private val DIGIT_DECADE = Regex("(\\d{2}|\\d{4})s?")

// ---- Helpers ----

private fun tokens(s: String) = normalize(s).split(' ').filter { it.isNotEmpty() }

/** True when [phrase] (normalized) occurs in [text] (normalized) as whole words. */
private fun hasPhrase(text: String, phrase: String) = " $text ".contains(" $phrase ")

private fun hasAny(text: String, set: Set<String>) = set.any { hasPhrase(text, it) }

// ---- Parsers ----

/** Only the top alternative counts, and only when it is exactly a command phrase. */
fun parseCommand(transcripts: List<String>): VoiceCommand? {
    val heard = normalize(transcripts.firstOrNull() ?: return null)
    return COMMAND_PHRASES.entries.firstOrNull { heard in it.value }?.key
}

/**
 * Players saying they do not know. People say this while discussing, so it is not a command: it
 * passes the turn only as an answer ("התשובה היא לא יודע"). Only the whole top alternative counts.
 */
private val DONT_KNOW = phrases("לא יודע", "לא יודעת", "אין לי מושג", "i don't know", "no idea", "איי דונט נואו")

fun isDontKnow(transcripts: List<String>): Boolean =
    transcripts.firstOrNull()?.let { normalize(it) in DONT_KNOW } == true

/** What a sentence says about the answer phrase ("התשובה היא…", "The answer is…"). */
sealed interface AnswerCut {
    /** No answer phrase: players are talking among themselves. */
    object None : AnswerCut

    /** The phrase with nothing after it: the next sentence is the answer. */
    object PhraseOnly : AnswerCut

    /**
     * The words after the last answer phrase, from each alternative that has the phrase and
     * something after it; [wordsBefore] is how many words came before the phrase in the first one.
     */
    data class After(val transcripts: List<String>, val wordsBefore: Int) : AnswerCut
}

private fun spokenTokens(s: String) = normalizeSpoken(s).split(' ').filter { it.isNotEmpty() }

/** Without a leading "the": the words before a phrase in [LEAD_INS] belong to it. */
private val ANSWER_PHRASES: List<List<String>> = listOf(
    "התשובה היא", "תשובה היא", "התשובה זה", "התשובה שלי היא", "התשובה שלי זה", "התשובה שלנו היא",
    "התשובה שלנו זה", "התשובה הסופית", "תשובה סופית", "answer is", "final answer", "אנסר איז", "פיינל אנסר",
).map(::spokenTokens)

/** Words that may come just before a phrase ("the / my / our / final answer is"). */
private val LEAD_INS = setOf("the", "my", "our", "final", "דה", "מיי", "אוור", "פיינל")

/** Words that may come just after a phrase and are not the answer ("final answer is", "התשובה הסופית שלי היא"). */
private val FILLERS = setOf("is", "איז", "היא", "הוא", "זה", "שלי", "שלנו")

private class PhraseCut(val after: String, val wordsBefore: Int)

private fun cutOne(transcript: String): PhraseCut? {
    val words = transcript.split(Regex("\\s+")).filter { it.isNotEmpty() }
    // Each normalized token with the index of the original word it came from.
    val toks = words.flatMapIndexed { i, w -> spokenTokens(w).map { it to i } }
    var end = -1
    var start = -1
    for (p in ANSWER_PHRASES) {
        for (s in 0..toks.size - p.size) {
            if (p.indices.all { toks[s + it].first == p[it] } && s + p.size > end) {
                end = s + p.size
                start = s
            }
        }
    }
    if (end < 0) return null
    while (start > 0 && toks[start - 1].first in LEAD_INS) start--
    var next = toks[end - 1].second + 1
    while (next < words.size && spokenTokens(words[next]).singleOrNull() in FILLERS) next++
    return PhraseCut(words.drop(next).joinToString(" "), toks[start].second)
}

/** Finds the answer phrase in any alternative; the last phrase in a sentence counts. */
fun cutAnswer(transcripts: List<String>): AnswerCut {
    val cuts = transcripts.mapNotNull(::cutOne)
    if (cuts.isEmpty()) return AnswerCut.None
    val answered = cuts.filter { it.after.isNotBlank() }
    if (answered.isEmpty()) return AnswerCut.PhraseOnly
    return AnswerCut.After(answered.map { it.after }, answered.first().wordsBefore)
}

/** The first number (digits or English/Hebrew words) inside [range], looking through every alternative. */
fun parseCount(transcripts: List<String>, range: IntRange): Int? {
    for (transcript in transcripts) {
        val toks = tokens(transcript)
        for (i in toks.indices) {
            val digits = toks[i].toIntOrNull()
            val n = if (digits != null) digits else numbers(toks, i).filter { it.first in range }.maxByOrNull { it.second }?.first
            if (n != null && n in range) return n
        }
    }
    return null
}

private fun numbers(toks: List<String>, i: Int) = englishNumbers(toks, i) + hebrewNumbers(toks, i)

fun parsePool(transcripts: List<String>): Pool? {
    for (transcript in transcripts) {
        val heard = normalize(transcript)
        val found = POOL_PHRASES.filterValues { hasAny(heard, it) }.keys
        if (found.size > 1) return Pool.MIXED // e.g. "ישראלי ולועזי"
        found.firstOrNull()?.let { return it }
    }
    return null
}

/** The decade (first year) named by the token at [i], and how many tokens it used. */
private fun decadeAt(toks: List<String>, i: Int): Pair<Int, Int>? {
    val w = toks[i]
    val variants = listOf(w) + HE_DECADE_PREFIXES.filter { w.startsWith(it) && w.length > it.length }.map { w.drop(it.length) }
    for (v in variants) {
        DIGIT_DECADE.matchEntire(v)?.let { m ->
            val n = m.groupValues[1].toInt()
            val year = if (m.groupValues[1].length == 4) n / 10 * 10 else if (n >= 40) 1900 + n else 2000 + n
            if (year % 10 == 0 || m.groupValues[1].length == 4) return year to 1
        }
        EN_DECADES[v]?.let { return it to 1 }
        if (v == HE_TWO_THOUSAND_WORD) {
            return when (heWord(toks, i + 1)) {
                in HE_TEN_WORDS -> 2010 to 2
                "עשרימ" -> 2020 to 2
                else -> 2000 to 1
            }
        }
        HE_DECADES[v]?.let { return it to 1 }
    }
    val next = toks.getOrNull(i + 1)
    if (w == "two" && (next == "thousands" || next == "thousand")) return 2000 to 2
    if (w == "twenty" && next == "tens") return 2010 to 2
    if (w == "twenty" && next == "twenties") return 2020 to 2
    return null
}

/** (fromDecade, toDecade); decades outside 1940-2020 are ignored. */
fun parseDecades(transcripts: List<String>): Pair<Int, Int>? {
    for (transcript in transcripts) {
        val toks = tokens(transcript)
        val found = mutableListOf<Int>()
        var i = 0
        while (i < toks.size) {
            val hit = decadeAt(toks, i)
            if (hit == null) {
                i++
            } else {
                if (hit.first in FIRST_DECADE..LAST_DECADE) found += hit.first
                i += hit.second
            }
        }
        if (found.isNotEmpty()) return found.min() to found.max()
        if (hasAny(normalize(transcript), DECADES_ALL)) return FIRST_DECADE to LAST_DECADE
    }
    return null
}

fun parseGenre(transcripts: List<String>): GenreAnswer? {
    for (transcript in transcripts) {
        val heard = normalize(transcript)
        GENRE_PHRASES.entries.firstOrNull { hasAny(heard, it.value) }?.let { return GenreAnswer.One(it.key) }
        if (hasAny(heard, GENRE_WHICH)) return GenreAnswer.WhichOnes
        if (hasAny(heard, GENRE_ALL)) return GenreAnswer.All
    }
    return null
}

/** The earliest alternative containing a phrase decides; FIRST wins a tie within one alternative. */
fun parseChoice(transcripts: List<String>, first: List<String>, second: List<String>): Choice? {
    val firstSet = first.map(::normalize).toSet()
    val secondSet = second.map(::normalize).toSet()
    for (transcript in transcripts) {
        val heard = normalize(transcript)
        if (hasAny(heard, firstSet)) return Choice.FIRST
        if (hasAny(heard, secondSet)) return Choice.SECOND
    }
    return null
}
