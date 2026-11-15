package com.roadhit.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceParsersTest {
    private val yes = listOf("yes", "כן")
    private val no = listOf("no", "לא")

    @Test fun commands() {
        assertEquals(VoiceCommand.PASS, parseCommand(listOf("דלג")))
        assertEquals(VoiceCommand.AGAIN, parseCommand(listOf("again")))
        assertEquals(VoiceCommand.STOP_GAME, parseCommand(listOf("עצור את המשחק")))
        assertEquals(VoiceCommand.SCORE, parseCommand(listOf("ניקוד")))
        assertEquals(VoiceCommand.AGAIN, parseCommand(listOf("שוב")))
        assertEquals(VoiceCommand.STOP_GAME, parseCommand(listOf("סטופ גיים")))
        assertEquals(VoiceCommand.STOP_GAME, parseCommand(listOf("‏stop game")))
        assertEquals(VoiceCommand.PASS, parseCommand(listOf("next")))
        assertEquals(VoiceCommand.SCORE, parseCommand(listOf("what's the score")))
    }

    @Test fun commandsNeedTheWholeTopTranscript() {
        assertNull(parseCommand(listOf("stop spice girls 1998")))
        assertNull(parseCommand(listOf("שוב של דודו טסה")))
        assertNull(parseCommand(listOf("shape of you", "pass")))
        assertNull(parseCommand(emptyList()))
    }

    @Test fun counts() {
        assertEquals(3, parseCount(listOf("שלושה"), 1..8))
        assertEquals(4, parseCount(listOf("4 players"), 1..8))
        assertNull(parseCount(listOf("twelve"), 1..8))
        assertEquals(4, parseCount(listOf("ארבעה שחקנים"), 1..8))
        assertEquals(2, parseCount(listOf("שתיים"), 1..8))
        assertNull(parseCount(listOf("0"), 1..8))
        assertEquals(5, parseCount(listOf("nine", "5"), 1..8))
        assertEquals(12, parseCount(listOf("שתים עשרה"), 1..20))
        assertEquals(20, parseCount(listOf("עשרים"), 1..20))
        assertEquals(1, parseCount(listOf("אחת"), 1..8))
    }

    @Test fun pools() {
        assertEquals(Pool.ISRAELI, parsePool(listOf("ישראלי")))
        assertEquals(Pool.INTERNATIONAL, parsePool(listOf("לועזי")))
        assertEquals(Pool.INTERNATIONAL, parsePool(listOf("international hits")))
        assertEquals(Pool.MIXED, parsePool(listOf("מעורב")))
        assertEquals(Pool.MIXED, parsePool(listOf("גם וגם")))
        assertEquals(Pool.MIXED, parsePool(listOf("mixed")))
        assertEquals(Pool.INTERNATIONAL, parsePool(listOf("אנגלית")))
        assertNull(parsePool(listOf("blah")))
    }

    @Test fun decades() {
        assertEquals(1940 to 2020, parseDecades(listOf("all")))
        assertEquals(1940 to 2020, parseDecades(listOf("הכל")))
        assertEquals(1980 to 2000, parseDecades(listOf("from the 80s to the 2000s")))
        assertEquals(1980 to 2000, parseDecades(listOf("משנות השמונים עד שנות האלפיים")))
        assertEquals(1990 to 1990, parseDecades(listOf("the nineties")))
        assertEquals(1950 to 1950, parseDecades(listOf("שנות החמישים")))
        assertEquals(1980 to 1980, parseDecades(listOf("שנות ה 80")))
        assertEquals(1980 to 1980, parseDecades(listOf("שנות ה80")))
        assertEquals(1980 to 1980, parseDecades(listOf("1980s")))
        assertEquals(1980 to 2000, parseDecades(listOf("from the 2000s to the 80s")))
        assertEquals(2020 to 2020, parseDecades(listOf("שנות העשרים")))
        assertEquals(2010 to 2010, parseDecades(listOf("שנות העשרה")))
        assertEquals(2010 to 2010, parseDecades(listOf("אלפיים ועשר")))
        assertEquals(2020 to 2020, parseDecades(listOf("אלפיים ועשרים")))
        assertEquals(2000 to 2000, parseDecades(listOf("two thousands")))
        assertEquals(2010 to 2010, parseDecades(listOf("twenty tens")))
        assertEquals(2020 to 2020, parseDecades(listOf("twenty twenties")))
        assertNull(parseDecades(listOf("the thirties")))
        assertNull(parseDecades(listOf("blah")))
    }

    @Test fun genres() {
        assertEquals(GenreAnswer.All, parseGenre(listOf("all")))
        assertEquals(GenreAnswer.WhichOnes, parseGenre(listOf("which")))
        assertEquals(GenreAnswer.WhichOnes, parseGenre(listOf("איזה יש")))
        assertEquals(GenreAnswer.One(Genre.ROCK), parseGenre(listOf("רוק")))
        assertEquals(GenreAnswer.One(Genre.MIZRAHI), parseGenre(listOf("מזרחי")))
        assertEquals(GenreAnswer.One(Genre.HIPHOP), parseGenre(listOf("hip hop")))
        assertEquals(GenreAnswer.One(Genre.HIPHOP), parseGenre(listOf("היפ הופ")))
        assertEquals(GenreAnswer.One(Genre.RNB), parseGenre(listOf("r&b")))
        assertEquals(GenreAnswer.WhichOnes, parseGenre(listOf("מה האפשרויות")))
        assertNull(parseGenre(listOf("blah")))
    }

    @Test fun choices() {
        assertEquals(Choice.FIRST, parseChoice(listOf("תתחיל"), listOf("תתחיל"), listOf("שנה")))
        assertEquals(Choice.SECOND, parseChoice(listOf("no"), yes, no))
        assertNull(parseChoice(listOf("know"), yes, no))
        assertNull(parseChoice(listOf("blah"), yes, no))
        assertEquals(Choice.SECOND, parseChoice(listOf("no", "yes"), yes, no))
        assertEquals(Choice.FIRST, parseChoice(listOf("yes no"), yes, no))
    }

    // ---- Answer phrase ----

    private fun after(vararg t: String) = AnswerCut.After(t.toList(), wordsBefore = 0)

    @Test fun dontKnowIsNotACommandWhileTalking() {
        for (s in listOf("לא יודע", "לא יודעת", "אין לי מושג", "i don't know", "no idea")) {
            assertNull(s, parseCommand(listOf(s)))
            assertTrue(s, isDontKnow(listOf(s)))
        }
        assertFalse(isDontKnow(listOf("לא יודע מה זה")))
        assertFalse(isDontKnow(emptyList()))
    }

    @Test fun answerPhraseCutsWhatFollows() {
        assertEquals(after("כלניות שושנה דמארי 1948"), cutAnswer(listOf("התשובה היא כלניות שושנה דמארי 1948")))
        assertEquals(after("Help The Beatles 1965"), cutAnswer(listOf("The answer is Help The Beatles 1965")))
        assertEquals(after("Help"), cutAnswer(listOf("the answer is: Help")))
        assertEquals(after("Help"), cutAnswer(listOf("My answer is Help")))
        assertEquals(after("Help"), cutAnswer(listOf("my final answer is Help")))
        assertEquals(after("Help"), cutAnswer(listOf("final answer Help")))
        assertEquals(after("יו יה"), cutAnswer(listOf("התשובה שלי היא יו יה")))
        assertEquals(after("יו יה"), cutAnswer(listOf("התשובה שלנו זה יו יה")))
        assertEquals(after("יו יה"), cutAnswer(listOf("התשובה הסופית שלי היא יו יה")))
        assertEquals(after("יו יה"), cutAnswer(listOf("תשובה סופית יו יה")))
        assertEquals(after("שייפ אוף יו"), cutAnswer(listOf("דה אנסר איז שייפ אוף יו")))
        assertEquals(after("Shape of You"), cutAnswer(listOf("‏התשובה היא Shape of You")))
    }

    @Test fun answerPhraseMidSentenceKeepsOnlyTheWordsAfterIt() {
        assertEquals(
            AnswerCut.After(listOf("כלניות"), wordsBefore = 3),
            cutAnswer(listOf("אני חושב שזה התשובה היא כלניות")),
        )
        // The last phrase counts: the players changed their minds.
        assertEquals(
            AnswerCut.After(listOf("Yesterday"), wordsBefore = 6),
            cutAnswer(listOf("the answer is Help no wait the answer is Yesterday")),
        )
    }

    @Test fun answerPhraseInAnyAlternative() {
        assertEquals(
            AnswerCut.After(listOf("help 1965", "help 1966"), wordsBefore = 0),
            cutAnswer(listOf("the answers help", "the answer is help 1965", "the answer is help 1966")),
        )
    }

    @Test fun answerPhraseAlone() {
        assertEquals(AnswerCut.PhraseOnly, cutAnswer(listOf("התשובה היא")))
        assertEquals(AnswerCut.PhraseOnly, cutAnswer(listOf("okay the answer is")))
        assertEquals(AnswerCut.PhraseOnly, cutAnswer(listOf("my final answer is")))
    }

    @Test fun noAnswerPhrase() {
        for (s in listOf("אני חושב שזה כלניות", "מה התשובה", "Help The Beatles 1965", "answer", "תשובה")) {
            assertEquals(s, AnswerCut.None, cutAnswer(listOf(s)))
        }
        assertEquals(AnswerCut.None, cutAnswer(emptyList()))
    }
}
