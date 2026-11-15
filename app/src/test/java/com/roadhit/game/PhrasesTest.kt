package com.roadhit.game

import com.roadhit.catalog.Catalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PhrasesTest {
    private val catalog by lazy {
        Catalog.parse(javaClass.classLoader!!.getResource("seed-catalog.json")!!.readText())
    }
    private val shapeOfYou by lazy { catalog.songs.first { it.id == "ed-sheeran-shape-of-you" } }
    private val kalaniyot by lazy { catalog.songs.first { it.id == "shoshana-damari-kalaniyot" } }

    private val both = listOf(HebrewPhrases, EnglishPhrases)
    private val hebrewLetter = Regex("[\\u05D0-\\u05EA]")

    private val allCombos = listOf(false, true).flatMap { t ->
        listOf(false, true).flatMap { a -> listOf(false, true).map { y -> FieldMatches(t, a, y) } }
    }

    /** Every String-returning method, with sample inputs. Player names are Latin so they never add Hebrew. */
    private fun texts(p: Phrases): Map<String, String> = mapOf(
        "startOrChange" to p.startOrChange(),
        "askPlayers" to p.askPlayers(),
        "askPool" to p.askPool(),
        "askDecades" to p.askDecades(),
        "askGenre" to p.askGenre(),
        "genreList" to p.genreList(),
        "askRounds" to p.askRounds(),
        "letsStart" to p.letsStart(),
        "tooFewSongs" to p.tooFewSongs(3, 10),
        "askContinue" to p.askContinue(),
        "turn" to p.turn(p.defaultPlayerName(2)),
        "defaultPlayerName" to p.defaultPlayerName(2),
        "answerRule" to p.answerRule(),
        "didNotUnderstand" to p.didNotUnderstand(),
        "noConnection" to p.noConnection(),
        "againUsed" to p.againUsed(),
        "confirmStop" to p.confirmStop(),
        "verdict" to p.verdict(FieldMatches(true, false, true), 3),
        "scores" to p.scores(listOf("A", "B"), listOf(3, 9)),
        "ranking" to p.ranking(listOf("A", "B", "C"), listOf(3, 9, 5)),
        "rankingSingle" to p.ranking(listOf("A"), listOf(4)),
        "rankingTie" to p.ranking(listOf("A", "B", "C"), listOf(9, 5, 9)),
    ) + allCombos.associate { "verdict$it" to p.verdict(it, 2) }

    @Test fun noMethodReturnsBlank() {
        for (p in both) {
            texts(p).forEach { (k, v) -> assertTrue("${p.lang} $k blank", v.isNotBlank()) }
            (p.reveal(shapeOfYou) + p.reveal(kalaniyot)).forEach { assertTrue(it.text.isNotBlank()) }
        }
    }

    @Test fun hebrewTextHasHebrewLetters() {
        texts(HebrewPhrases).forEach { (k, v) -> assertTrue("$k: $v", hebrewLetter.containsMatchIn(v)) }
    }

    @Test fun englishTextHasNoHebrewLetters() {
        texts(EnglishPhrases).forEach { (k, v) -> assertFalse("$k: $v", hebrewLetter.containsMatchIn(v)) }
        EnglishPhrases.reveal(shapeOfYou).forEach { assertFalse(it.text, hebrewLetter.containsMatchIn(it.text)) }
        EnglishPhrases.reveal(kalaniyot).drop(1).forEach { assertFalse(it.text, hebrewLetter.containsMatchIn(it.text)) }
    }

    @Test fun revealSpeaksTitleInItsOwnScript() {
        for (p in both) {
            val en = p.reveal(shapeOfYou)
            assertEquals(Utterance("Shape of You", Lang.EN), en.first())
            assertTrue(en.size >= 2)
            en.drop(1).forEach { assertEquals(p.lang, it.lang) }
            assertTrue(en.drop(1).joinToString(" ") { it.text }.contains("2017"))

            val he = p.reveal(kalaniyot)
            assertEquals(Utterance(kalaniyot.title.he, Lang.HE), he.first())
            he.drop(1).forEach { assertEquals(p.lang, it.lang) }
        }
        assertTrue(HebrewPhrases.reveal(shapeOfYou)[1].text.contains(shapeOfYou.artist.he))
        assertTrue(EnglishPhrases.reveal(shapeOfYou)[1].text.contains(shapeOfYou.artist.en))
    }

    @Test fun rankingPutsHigherScoreFirst() {
        for (p in both) {
            val r = p.ranking(listOf("A", "B"), listOf(3, 9))
            assertTrue(r, r.indexOf("B") in 0 until r.indexOf("A"))
        }
    }

    @Test fun rankingTiesKeepInputOrder() {
        for (p in both) {
            val r = p.ranking(listOf("A", "B", "C"), listOf(5, 9, 5))
            assertTrue(r, r.indexOf("B") < r.indexOf("A") && r.indexOf("A") < r.indexOf("C"))
        }
    }

    @Test fun tieForFirstIsAnnouncedAsATieNamingEveryTopScorer() {
        assertEquals(
            "Game over! A tie: A and C, 9 points each. Third, B, 5 points.",
            EnglishPhrases.ranking(listOf("A", "B", "C"), listOf(9, 5, 9)),
        )
        assertEquals(
            "סוף המשחק! תיקו במקום הראשון: A ו-C, 9 נקודות כל אחד. במקום השלישי B, 5 נקודות.",
            HebrewPhrases.ranking(listOf("A", "B", "C"), listOf(9, 5, 9)),
        )
    }

    @Test fun threeWayTieAndEveryoneTied() {
        assertEquals(
            "Game over! A tie: A, B and C, 4 points each.",
            EnglishPhrases.ranking(listOf("A", "B", "C"), listOf(4, 4, 4)),
        )
        assertEquals(
            "סוף המשחק! תיקו במקום הראשון: A, B ו-C, 4 נקודות כל אחד.",
            HebrewPhrases.ranking(listOf("A", "B", "C"), listOf(4, 4, 4)),
        )
    }

    @Test fun singleWinnerWording() {
        assertEquals(
            "Game over! B wins with 9 points. Second, C, 5 points. Third, A, 3 points.",
            EnglishPhrases.ranking(listOf("A", "B", "C"), listOf(3, 9, 5)),
        )
        assertEquals(
            "סוף המשחק! במקום הראשון B, 9 נקודות. במקום השני C, 5 נקודות. במקום השלישי A, 3 נקודות.",
            HebrewPhrases.ranking(listOf("A", "B", "C"), listOf(3, 9, 5)),
        )
    }

    @Test fun rankingSinglePlayerIsJustTheScore() {
        assertEquals("סוף המשחק! 14 נקודות.", HebrewPhrases.ranking(listOf("A"), listOf(14)))
        assertEquals("Game over! 14 points.", EnglishPhrases.ranking(listOf("A"), listOf(14)))
    }

    @Test fun scoresInInputOrder() {
        for (p in both) {
            val s = p.scores(listOf("A", "B"), listOf(3, 9))
            assertTrue(s, s.indexOf("A") < s.indexOf("B") && s.contains("3") && s.contains("9"))
        }
    }

    @Test fun verdictContainsPointsUnlessNothingWasRight() {
        for (p in both) {
            assertTrue(p.verdict(FieldMatches(true, true, true), 5).contains("5"))
            allCombos.filter { it != FieldMatches(false, false, false) }.forEach { m ->
                val v = p.verdict(m, 7)
                assertTrue("$m: $v", v.isNotBlank() && v.contains("7"))
            }
            assertFalse(p.verdict(FieldMatches(false, false, false), 0).contains("0"))
        }
    }

    @Test fun shortWording() {
        assertEquals("מושלם! 5 נקודות!", HebrewPhrases.verdict(FieldMatches(true, true, true), 5))
        assertEquals("השיר והאמן נכונים, שתי נקודות.", HebrewPhrases.verdict(FieldMatches(true, true, false), 2))
        assertEquals("השנה נכונה, נקודה 1.", HebrewPhrases.verdict(FieldMatches(false, false, true), 1))
        assertEquals("לא הפעם.", HebrewPhrases.verdict(FieldMatches(false, false, false), 0))
        assertEquals("Perfect! 5 points!", EnglishPhrases.verdict(FieldMatches(true, true, true), 5))
        assertEquals("Song and artist correct, 2 points.", EnglishPhrases.verdict(FieldMatches(true, true, false), 2))
        assertEquals("Not this time.", EnglishPhrases.verdict(FieldMatches(false, false, false), 0))

        assertEquals("מתחילים, או לשנות?", HebrewPhrases.startOrChange())
        assertEquals("מתחילים!", HebrewPhrases.letsStart())
        assertEquals("להמשיך את המשחק, או משחק חדש?", HebrewPhrases.askContinue())
        assertEquals("כשמחליטים, תגידו: התשובה היא.", HebrewPhrases.answerRule())
        assertEquals("כמה שחקנים?", HebrewPhrases.askPlayers())
        assertEquals("כמה סיבובים לכל שחקן?", HebrewPhrases.askRounds())
        assertEquals("איזה ז'אנר, או הכול?", HebrewPhrases.askGenre())
        assertEquals("לא הבנתי.", HebrewPhrases.didNotUnderstand())

        assertEquals("של ${kalaniyot.artist.he}, ${kalaniyot.year}", HebrewPhrases.reveal(kalaniyot)[1].text)
        assertEquals("by ${shapeOfYou.artist.en}, ${shapeOfYou.year}", EnglishPhrases.reveal(shapeOfYou)[1].text)
    }

    @Test fun settingsAreNeverSpoken() {
        for (p in both) {
            for (t in listOf(p.startOrChange(), p.letsStart(), p.askContinue())) {
                assertFalse(t, t.any { it.isDigit() })
            }
        }
    }

    @Test fun hebrewTwoPointsAreSaidInWordsAndOtherNumbersKeepTheirDigits() {
        // The Hebrew voice reads "2 נקודות" as "שתיים נקודות"; exactly two is "שתי נקודות".
        assertEquals("השיר והשנה נכונים, שתי נקודות.", HebrewPhrases.verdict(FieldMatches(true, false, true), 2))
        assertEquals("סוף המשחק! שתי נקודות.", HebrewPhrases.ranking(listOf("A"), listOf(2)))
        assertTrue(HebrewPhrases.scores(listOf("A", "B"), listOf(2, 3)).contains("A, שתי נקודות. B, 3 נקודות"))
        assertFalse(HebrewPhrases.ranking(listOf("A"), listOf(12)).contains("שתי"))
        assertEquals("סוף המשחק! 12 נקודות.", HebrewPhrases.ranking(listOf("A"), listOf(12)))
        assertEquals("סוף המשחק! נקודה 1.", HebrewPhrases.ranking(listOf("A"), listOf(1)))
        assertEquals("סוף המשחק! 0 נקודות.", HebrewPhrases.ranking(listOf("A"), listOf(0)))
        assertTrue(EnglishPhrases.ranking(listOf("A"), listOf(2)).contains("2")) // English keeps the digit
    }

    @Test fun verdictWordingDiffersPerCombination() {
        for (p in both) assertEquals(8, allCombos.map { p.verdict(it, 1) }.toSet().size)
    }

    @Test fun phrasesForReturnsObjects() {
        assertSame(HebrewPhrases, phrasesFor(Lang.HE))
        assertSame(EnglishPhrases, phrasesFor(Lang.EN))
        assertEquals(Lang.HE, HebrewPhrases.lang)
        assertEquals(Lang.EN, EnglishPhrases.lang)
    }

    @Test fun defaultPlayerNames() {
        assertEquals("שחקן 2", HebrewPhrases.defaultPlayerName(2))
        assertEquals("Player 2", EnglishPhrases.defaultPlayerName(2))
    }

    @Test fun wordListsNonEmptyAndShared() {
        fun lists(p: Phrases) = listOf(p.startWords, p.changeWords, p.continueWords, p.newGameWords, p.yesWords, p.noWords)
        lists(HebrewPhrases).forEach { assertTrue(it.isNotEmpty()) }
        assertEquals(lists(HebrewPhrases), lists(EnglishPhrases))
        assertTrue(HebrewPhrases.startWords.containsAll(listOf("start", "התחל", "סטארט")))
    }
}
