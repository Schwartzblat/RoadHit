package com.roadhit.game

/** One piece of speech and the voice (language) that should say it. */
data class Utterance(val text: String, val lang: Lang)

/** Everything the game says, in one app language. Spoken, never shown: keep it short. */
interface Phrases {
    val lang: Lang
    /** Reuse last time's settings or change them; the settings themselves are not read out. */
    fun startOrChange(): String
    fun askPlayers(): String
    fun askPool(): String
    fun askDecades(): String
    fun askGenre(): String
    fun genreList(): String
    fun askRounds(): String
    /** Said once the setup questions were answered (not after reusing last time's settings). */
    fun letsStart(): String
    fun tooFewSongs(have: Int, need: Int): String
    fun askContinue(): String
    /** Said only when there are two players or more. */
    fun turn(playerName: String): String
    fun defaultPlayerName(n: Int): String
    /** How to answer, said once when a game starts or resumes. */
    fun answerRule(): String
    fun didNotUnderstand(): String
    fun noConnection(): String
    fun againUsed(): String
    fun confirmStop(): String
    fun verdict(m: FieldMatches, points: Int): String
    /** Title in [Song.script]'s voice, then artist and year in [lang]. */
    fun reveal(song: Song): List<Utterance>
    fun scores(names: List<String>, scores: List<Int>): String
    fun ranking(names: List<String>, scores: List<Int>): String
    val startWords: List<String>
    val changeWords: List<String>
    val continueWords: List<String>
    val newGameWords: List<String>
    val yesWords: List<String>
    val noWords: List<String>
}

fun phrasesFor(lang: Lang): Phrases = when (lang) {
    Lang.HE -> HebrewPhrases
    Lang.EN -> EnglishPhrases
}

// Word lists: the same in both languages, since a player may answer in either, and the
// Hebrew recognizer writes English words in Hebrew letters.
private val START_WORDS = listOf("start", "go", "begin", "התחל", "תתחיל", "להתחיל", "מתחילים", "יאללה", "סטארט")
private val CHANGE_WORDS = listOf("change", "שנה", "לשנות", "שינוי", "תשנה", "צ'יינג'")
private val CONTINUE_WORDS = listOf("continue", "resume", "המשך", "להמשיך", "תמשיך", "ממשיכים", "קונטיניו")
private val NEW_GAME_WORDS = listOf("new game", "new", "משחק חדש", "חדש", "ניו גיים")
private val YES_WORDS = listOf("yes", "yeah", "yep", "כן", "בטח", "יס", "נכון")
private val NO_WORDS = listOf("no", "nope", "לא", "נו")

/** Player indices sorted by score, highest first; ties keep input order (stable sort). */
private fun rankOrder(scores: List<Int>): List<Int> = scores.indices.sortedByDescending { scores[it] }

/** How many players share the top score in [order] (from [rankOrder]). */
private fun topCount(order: List<Int>, scores: List<Int>) = order.count { scores[it] == scores[order.first()] }

/** "A, B" + [and] + "C". */
private fun joinNames(names: List<String>, and: String) =
    if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + and + names.last()

// ---- Hebrew helpers ----

/**
 * Digits, except for exactly two: the Hebrew voice reads "2 נקודות" as "שתיים נקודות", so two is
 * written "שתי נקודות". "נקודה 1" reads as "נקודה אחת".
 */
private fun pointsHe(n: Int) = when (n) {
    1 -> "נקודה 1"
    2 -> "שתי נקודות"
    else -> "$n נקודות"
}

private fun genreNameHe(g: Genre) = when (g) {
    Genre.POP -> "פופ"
    Genre.ROCK -> "רוק"
    Genre.HIPHOP -> "היפ הופ"
    Genre.DANCE -> "דאנס ואלקטרוני"
    Genre.RNB -> "אר אנד בי וסול"
    Genre.MIZRAHI -> "מזרחית"
    Genre.OTHER -> "אחר"
}

private val ORDINALS_HE = listOf("הראשון", "השני", "השלישי", "הרביעי", "החמישי", "השישי", "השביעי", "השמיני")
private fun placeHe(i: Int) = ORDINALS_HE.getOrNull(i)?.let { "במקום $it" } ?: "במקום ה-${i + 1}"

object HebrewPhrases : Phrases {
    override val lang = Lang.HE

    override fun startOrChange() = "מתחילים, או לשנות?"
    override fun askPlayers() = "כמה שחקנים?"
    override fun askPool() = "להיטים ישראליים, בינלאומיים, או מעורב?"
    override fun askDecades() = "אילו עשורים? למשל הכול, או מהשמונים עד האלפיים."
    override fun askGenre() = "איזה ז'אנר, או הכול?"
    override fun genreList() =
        "הז'אנרים הם: " + Genre.entries.dropLast(1).joinToString(", ") { genreNameHe(it) } +
            ", ו${genreNameHe(Genre.entries.last())}."
    override fun askRounds() = "כמה סיבובים לכל שחקן?"
    override fun letsStart() = "מתחילים!"
    override fun tooFewSongs(have: Int, need: Int) =
        when (have) {
            0 -> "אין שירים מתאימים"
            1 -> "יש רק שיר אחד מתאים"
            else -> "יש רק $have שירים מתאימים"
        } + ", וצריך $need. נסו בחירה רחבה יותר."
    override fun askContinue() = "להמשיך את המשחק, או משחק חדש?"

    override fun turn(playerName: String) = "התור של $playerName."
    override fun defaultPlayerName(n: Int) = "שחקן $n"

    override fun answerRule() = "כשמחליטים, תגידו: התשובה היא."
    override fun didNotUnderstand() = "לא הבנתי."
    override fun noConnection() = "אין חיבור לאינטרנט. המשחק בהמתנה."
    override fun againUsed() = "כבר השמעתי שוב בתור הזה."
    override fun confirmStop() = "לעצור את המשחק? כן או לא?"

    override fun verdict(m: FieldMatches, points: Int): String {
        val p = pointsHe(points)
        return when {
            m.title && m.artist && m.year -> "מושלם! $p!"
            m.title && m.artist -> "השיר והאמן נכונים, $p."
            m.title && m.year -> "השיר והשנה נכונים, $p."
            m.artist && m.year -> "האמן והשנה נכונים, $p."
            m.title -> "השיר נכון, $p."
            m.artist -> "האמן נכון, $p."
            m.year -> "השנה נכונה, $p."
            else -> "לא הפעם."
        }
    }

    override fun reveal(song: Song) = listOf(
        titleUtterance(song),
        Utterance("של ${song.artist.he}, ${song.year}", Lang.HE),
    )

    override fun scores(names: List<String>, scores: List<Int>) =
        "הניקוד: " + names.indices.joinToString(". ") { "${names[it]}, ${pointsHe(scores[it])}" } + "."

    override fun ranking(names: List<String>, scores: List<Int>): String {
        if (names.size == 1) return "סוף המשחק! ${pointsHe(scores[0])}."
        val order = rankOrder(scores)
        val w = order.first()
        val top = topCount(order, scores)
        val winner = if (top == 1) {
            "סוף המשחק! ${placeHe(0)} ${names[w]}, ${pointsHe(scores[w])}."
        } else {
            val tied = joinNames(order.take(top).map { names[it] }, " ו-")
            "סוף המשחק! תיקו ${placeHe(0)}: $tied, ${pointsHe(scores[w])} כל אחד."
        }
        val rest = order.drop(top).mapIndexed { i, p -> "${placeHe(top + i)} ${names[p]}, ${pointsHe(scores[p])}." }
        return (listOf(winner) + rest).joinToString(" ")
    }

    override val startWords = START_WORDS
    override val changeWords = CHANGE_WORDS
    override val continueWords = CONTINUE_WORDS
    override val newGameWords = NEW_GAME_WORDS
    override val yesWords = YES_WORDS
    override val noWords = NO_WORDS
}

// ---- English helpers ----

private fun pointsEn(n: Int) = if (n == 1) "1 point" else "$n points"

private fun genreNameEn(g: Genre) = when (g) {
    Genre.POP -> "pop"
    Genre.ROCK -> "rock"
    Genre.HIPHOP -> "hip-hop"
    Genre.DANCE -> "dance and electronic"
    Genre.RNB -> "R&B and soul"
    Genre.MIZRAHI -> "Mizrahi"
    Genre.OTHER -> "other"
}

private val ORDINALS_EN = listOf("First", "Second", "Third", "Fourth", "Fifth", "Sixth", "Seventh", "Eighth")
private fun placeEn(i: Int) = ORDINALS_EN.getOrNull(i) ?: "Number ${i + 1}"

object EnglishPhrases : Phrases {
    override val lang = Lang.EN

    override fun startOrChange() = "Start, or change?"
    override fun askPlayers() = "How many players?"
    override fun askPool() = "Israeli hits, international hits, or mixed?"
    override fun askDecades() = "Which decades? For example all, or the 80s to the 2000s."
    override fun askGenre() = "Which genre, or all?"
    override fun genreList() =
        "The genres are: " + Genre.entries.dropLast(1).joinToString(", ") { genreNameEn(it) } +
            ", and ${genreNameEn(Genre.entries.last())}."
    override fun askRounds() = "How many rounds each?"
    override fun letsStart() = "Let's start!"
    override fun tooFewSongs(have: Int, need: Int) =
        when (have) {
            0 -> "No songs match"
            1 -> "Only 1 song matches"
            else -> "Only $have songs match"
        } + ", and $need are needed. Try a wider choice."
    override fun askContinue() = "Continue the game, or a new game?"

    override fun turn(playerName: String) = "$playerName, your turn."
    override fun defaultPlayerName(n: Int) = "Player $n"

    override fun answerRule() = "When you decide, say: the answer is."
    override fun didNotUnderstand() = "I didn't get that."
    override fun noConnection() = "No internet connection. The game is paused."
    override fun againUsed() = "The replay was already used this turn."
    override fun confirmStop() = "Stop the game? Yes or no?"

    override fun verdict(m: FieldMatches, points: Int): String {
        val p = pointsEn(points)
        return when {
            m.title && m.artist && m.year -> "Perfect! $p!"
            m.title && m.artist -> "Song and artist correct, $p."
            m.title && m.year -> "Song and year correct, $p."
            m.artist && m.year -> "Artist and year correct, $p."
            m.title -> "Song correct, $p."
            m.artist -> "Artist correct, $p."
            m.year -> "Year correct, $p."
            else -> "Not this time."
        }
    }

    override fun reveal(song: Song) = listOf(
        titleUtterance(song),
        Utterance("by ${song.artist.en}, ${song.year}", Lang.EN),
    )

    override fun scores(names: List<String>, scores: List<Int>) =
        "Scores: " + names.indices.joinToString(". ") { "${names[it]}, ${pointsEn(scores[it])}" } + "."

    override fun ranking(names: List<String>, scores: List<Int>): String {
        if (names.size == 1) return "Game over! ${pointsEn(scores[0])}."
        val order = rankOrder(scores)
        val w = order.first()
        val top = topCount(order, scores)
        val winner = if (top == 1) {
            "Game over! ${names[w]} wins with ${pointsEn(scores[w])}."
        } else {
            val tied = joinNames(order.take(top).map { names[it] }, " and ")
            "Game over! A tie: $tied, ${pointsEn(scores[w])} each."
        }
        val rest = order.drop(top).mapIndexed { i, p -> "${placeEn(top + i)}, ${names[p]}, ${pointsEn(scores[p])}." }
        return (listOf(winner) + rest).joinToString(" ")
    }

    override val startWords = START_WORDS
    override val changeWords = CHANGE_WORDS
    override val continueWords = CONTINUE_WORDS
    override val newGameWords = NEW_GAME_WORDS
    override val yesWords = YES_WORDS
    override val noWords = NO_WORDS
}

/** The title alone, in its original script, for the voice of that script's language. */
private fun titleUtterance(song: Song) = when (song.script) {
    Lang.HE -> Utterance(song.title.he, Lang.HE)
    Lang.EN -> Utterance(song.title.en, Lang.EN)
}
