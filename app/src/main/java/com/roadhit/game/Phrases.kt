package com.roadhit.game

/** One piece of speech and the voice (language) that should say it. */
data class Utterance(val text: String, val lang: Lang)

/** Everything the game says, in one app language. Spoken, never shown: keep it short. */
interface Phrases {
    val lang: Lang
    fun lastTime(s: GameSettings): String
    fun askPlayers(): String
    fun askPool(): String
    fun askDecades(): String
    fun askGenre(): String
    fun genreList(): String
    fun askRounds(): String
    fun summary(s: GameSettings): String
    fun tooFewSongs(have: Int, need: Int): String
    fun askContinue(s: GameSettings, round: Int): String
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
    fun total(playerName: String, total: Int): String
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

private const val FIRST_DECADE = 1940
private const val LAST_DECADE = 2020

private fun isAllDecades(s: GameSettings) = s.fromDecade == FIRST_DECADE && s.toDecade == LAST_DECADE

/** Player indices sorted by score, highest first; ties keep input order (stable sort). */
private fun rankOrder(scores: List<Int>): List<Int> = scores.indices.sortedByDescending { scores[it] }

/** How many players share the top score in [order] (from [rankOrder]). */
private fun topCount(order: List<Int>, scores: List<Int>) = order.count { scores[it] == scores[order.first()] }

/** "A, B" + [and] + "C". */
private fun joinNames(names: List<String>, and: String) =
    if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + and + names.last()

// ---- Hebrew helpers ----

private fun playersHe(n: Int) = if (n == 1) "שחקן אחד" else "$n שחקנים"
private fun roundsHe(n: Int) = if (n == 1) "סיבוב אחד" else "$n סיבובים"

/**
 * Digits, except for exactly two: the Hebrew voice reads "2 נקודות" as "שתיים נקודות", so two is
 * written "שתי נקודות". "נקודה 1" reads as "נקודה אחת".
 */
private fun pointsHe(n: Int) = when (n) {
    1 -> "נקודה 1"
    2 -> "שתי נקודות"
    else -> "$n נקודות"
}

private fun poolHe(p: Pool) = when (p) {
    Pool.ISRAELI -> "להיטים ישראליים"
    Pool.INTERNATIONAL -> "להיטים בינלאומיים"
    Pool.MIXED -> "להיטים ישראליים ובינלאומיים"
}

private fun decadeHe(d: Int) = when (d) {
    1940 -> "שנות הארבעים"
    1950 -> "שנות החמישים"
    1960 -> "שנות השישים"
    1970 -> "שנות השבעים"
    1980 -> "שנות השמונים"
    1990 -> "שנות התשעים"
    2000 -> "שנות האלפיים"
    else -> "שנות ה-$d"
}

private fun decadesHe(s: GameSettings) = when {
    s.fromYear != null && s.toYear != null ->
        if (s.fromYear == s.toYear) "רק שנת ${s.fromYear}" else "משנת ${s.fromYear} עד ${s.toYear}"
    isAllDecades(s) -> "כל העשורים"
    s.fromDecade == s.toDecade -> "רק ${decadeHe(s.fromDecade)}"
    else -> "מ${decadeHe(s.fromDecade)} עד ${decadeHe(s.toDecade)}"
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

private fun genreHe(g: Genre?) = when (g) {
    null -> "כל הז'אנרים"
    Genre.OTHER -> "רק ז'אנרים אחרים"
    else -> "רק ${genreNameHe(g)}"
}

private fun settingsHe(s: GameSettings) =
    "${playersHe(s.players)}, ${poolHe(s.pool)}, ${decadesHe(s)}, ${genreHe(s.genre)}, ${roundsHe(s.rounds)}"

private val ORDINALS_HE = listOf("הראשון", "השני", "השלישי", "הרביעי", "החמישי", "השישי", "השביעי", "השמיני")
private fun placeHe(i: Int) = ORDINALS_HE.getOrNull(i)?.let { "במקום $it" } ?: "במקום ה-${i + 1}"

object HebrewPhrases : Phrases {
    override val lang = Lang.HE

    override fun lastTime(s: GameSettings) =
        "בפעם הקודמת: ${settingsHe(s)}. תגידו 'מתחילים', או 'לשנות'."
    override fun askPlayers() = "כמה שחקנים? מאחד עד שמונה."
    override fun askPool() = "להיטים ישראליים, בינלאומיים, או מעורב?"
    override fun askDecades() = "אילו עשורים? תגידו 'הכול', או למשל 'משנות השמונים עד שנות האלפיים'."
    override fun askGenre() = "איזה ז'אנר? תגידו 'הכול', או שם של ז'אנר."
    override fun genreList() =
        "הז'אנרים הם: " + Genre.entries.dropLast(1).joinToString(", ") { genreNameHe(it) } +
            ", ו${genreNameHe(Genre.entries.last())}."
    override fun askRounds() = "כמה סיבובים לכל שחקן? מאחד עד עשרים."
    override fun summary(s: GameSettings) = "${settingsHe(s)}. מתחילים!"
    override fun tooFewSongs(have: Int, need: Int) =
        when (have) {
            0 -> "אין שירים מתאימים"
            1 -> "יש רק שיר אחד מתאים"
            else -> "יש רק $have שירים מתאימים"
        } + ", וצריך $need. נסו בחירה רחבה יותר."
    override fun askContinue(s: GameSettings, round: Int) =
        "להמשיך את המשחק של ${playersHe(s.players)}, סיבוב $round מתוך ${s.rounds}? תגידו 'ממשיכים', או 'משחק חדש'."

    override fun turn(playerName: String) = "התור של $playerName."
    override fun defaultPlayerName(n: Int) = "שחקן $n"

    override fun answerRule() = "אפשר להתייעץ בחופשיות. כשמחליטים, אומרים: התשובה היא, ואז את השיר, המבצע והשנה."
    override fun didNotUnderstand() = "לא הבנתי. אפשר שוב?"
    override fun noConnection() = "אין חיבור לאינטרנט. המשחק בהמתנה."
    override fun againUsed() = "כבר השמעתי שוב בתור הזה."
    override fun confirmStop() = "לעצור את המשחק? כן או לא?"

    override fun verdict(m: FieldMatches, points: Int): String {
        val p = pointsHe(points)
        return when {
            m.title && m.artist && m.year -> "מושלם! השיר, האמן והשנה, הכול נכון. $p!"
            m.title && m.artist -> "השיר והאמן נכונים. $p."
            m.title && m.year -> "השיר והשנה נכונים. $p."
            m.artist && m.year -> "האמן והשנה נכונים. $p."
            m.title -> "השיר נכון. $p."
            m.artist -> "האמן נכון. $p."
            m.year -> "השנה נכונה. $p."
            else -> "לא הפעם. $p."
        }
    }

    override fun reveal(song: Song) = listOf(
        titleUtterance(song),
        Utterance("של ${song.artist.he}, משנת ${song.year}", Lang.HE),
    )

    override fun total(playerName: String, total: Int) = "$playerName, סך הכול ${pointsHe(total)}."

    override fun scores(names: List<String>, scores: List<Int>) =
        "הניקוד: " + names.indices.joinToString(". ") { "${names[it]}, ${pointsHe(scores[it])}" } + "."

    override fun ranking(names: List<String>, scores: List<Int>): String {
        if (names.size == 1) return "סוף המשחק! התוצאה הסופית של ${names[0]}: ${pointsHe(scores[0])}."
        val order = rankOrder(scores)
        val w = order.first()
        val top = topCount(order, scores)
        val winner = if (top == 1) {
            "סוף המשחק! ${placeHe(0)}, ${names[w]}, עם ${pointsHe(scores[w])}. כל הכבוד!"
        } else {
            val tied = joinNames(order.take(top).map { names[it] }, " ו-")
            "סוף המשחק! תיקו ${placeHe(0)}: $tied, עם ${pointsHe(scores[w])} כל אחד. כל הכבוד!"
        }
        val rest = order.drop(top).mapIndexed { i, p -> "${placeHe(top + i)}, ${names[p]}, ${pointsHe(scores[p])}." }
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

private fun playersEn(n: Int) = if (n == 1) "1 player" else "$n players"
private fun roundsEn(n: Int) = if (n == 1) "1 round" else "$n rounds"
private fun pointsEn(n: Int) = if (n == 1) "1 point" else "$n points"

private fun poolEn(p: Pool) = when (p) {
    Pool.ISRAELI -> "Israeli hits"
    Pool.INTERNATIONAL -> "international hits"
    Pool.MIXED -> "Israeli and international hits"
}

private fun decadeEn(d: Int) = if (d < 2000) "the ${d % 100}s" else "the ${d}s"

private fun decadesEn(s: GameSettings) = when {
    s.fromYear != null && s.toYear != null ->
        if (s.fromYear == s.toYear) "just ${s.fromYear}" else "${s.fromYear} to ${s.toYear}"
    isAllDecades(s) -> "all decades"
    s.fromDecade == s.toDecade -> "just ${decadeEn(s.fromDecade)}"
    else -> "${decadeEn(s.fromDecade)} to ${decadeEn(s.toDecade)}"
}

private fun genreNameEn(g: Genre) = when (g) {
    Genre.POP -> "pop"
    Genre.ROCK -> "rock"
    Genre.HIPHOP -> "hip-hop"
    Genre.DANCE -> "dance and electronic"
    Genre.RNB -> "R&B and soul"
    Genre.MIZRAHI -> "Mizrahi"
    Genre.OTHER -> "other"
}

private fun genreEn(g: Genre?) = when (g) {
    null -> "all genres"
    Genre.OTHER -> "just other genres"
    else -> "just ${genreNameEn(g)}"
}

private fun settingsEn(s: GameSettings) =
    "${playersEn(s.players)}, ${poolEn(s.pool)}, ${decadesEn(s)}, ${genreEn(s.genre)}, ${roundsEn(s.rounds)}"

private val ORDINALS_EN = listOf("First", "Second", "Third", "Fourth", "Fifth", "Sixth", "Seventh", "Eighth")
private fun placeEn(i: Int) = ORDINALS_EN.getOrNull(i) ?: "Number ${i + 1}"

object EnglishPhrases : Phrases {
    override val lang = Lang.EN

    override fun lastTime(s: GameSettings) = "Last time: ${settingsEn(s)}. Say 'start', or 'change'."
    override fun askPlayers() = "How many players? One to eight."
    override fun askPool() = "Israeli hits, international hits, or mixed?"
    override fun askDecades() = "Which decades? Say 'all', or for example 'from the 80s to the 2000s'."
    override fun askGenre() = "Any genre? Say 'all', or name one."
    override fun genreList() =
        "The genres are: " + Genre.entries.dropLast(1).joinToString(", ") { genreNameEn(it) } +
            ", and ${genreNameEn(Genre.entries.last())}."
    override fun askRounds() = "How many rounds each? One to twenty."
    override fun summary(s: GameSettings) = "${settingsEn(s)}. Let's start!"
    override fun tooFewSongs(have: Int, need: Int) =
        when (have) {
            0 -> "No songs match"
            1 -> "Only 1 song matches"
            else -> "Only $have songs match"
        } + ", and $need are needed. Try a wider choice."
    override fun askContinue(s: GameSettings, round: Int) =
        "Continue the game with ${playersEn(s.players)}, round $round of ${s.rounds}? Say 'continue', or 'new game'."

    override fun turn(playerName: String) = "$playerName, your turn."
    override fun defaultPlayerName(n: Int) = "Player $n"

    override fun answerRule() = "Talk it over freely. When you decide, say: the answer is, then the song, the artist and the year."
    override fun didNotUnderstand() = "Sorry, I didn't get that. Once more?"
    override fun noConnection() = "No internet connection. The game is paused."
    override fun againUsed() = "The replay was already used this turn."
    override fun confirmStop() = "Stop the game? Yes or no?"

    override fun verdict(m: FieldMatches, points: Int): String {
        val p = pointsEn(points)
        return when {
            m.title && m.artist && m.year -> "Perfect! Song, artist and year, all correct. $p!"
            m.title && m.artist -> "Song and artist correct. $p."
            m.title && m.year -> "Song and year correct. $p."
            m.artist && m.year -> "Artist and year correct. $p."
            m.title -> "Song correct. $p."
            m.artist -> "Artist correct. $p."
            m.year -> "Year correct. $p."
            else -> "Not this time. $p."
        }
    }

    override fun reveal(song: Song) = listOf(
        titleUtterance(song),
        Utterance("by ${song.artist.en}, from ${song.year}", Lang.EN),
    )

    override fun total(playerName: String, total: Int) = "$playerName has ${pointsEn(total)}."

    override fun scores(names: List<String>, scores: List<Int>) =
        "Scores: " + names.indices.joinToString(". ") { "${names[it]}, ${pointsEn(scores[it])}" } + "."

    override fun ranking(names: List<String>, scores: List<Int>): String {
        if (names.size == 1) return "Game over! ${names[0]}, your final score is ${pointsEn(scores[0])}."
        val order = rankOrder(scores)
        val w = order.first()
        val top = topCount(order, scores)
        val winner = if (top == 1) {
            "Game over! The winner is ${names[w]}, with ${pointsEn(scores[w])}. Well done!"
        } else {
            val tied = joinNames(order.take(top).map { names[it] }, " and ")
            "Game over! It's a tie! The winners are $tied, with ${pointsEn(scores[w])} each. Well done!"
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
