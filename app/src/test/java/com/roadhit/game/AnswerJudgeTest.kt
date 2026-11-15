package com.roadhit.game

import com.roadhit.catalog.Catalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerJudgeTest {
    private val catalog by lazy {
        Catalog.parse(javaClass.classLoader!!.getResource("seed-catalog.json")!!.readText())
    }
    private val judge = LocalAnswerJudge()

    private fun song(id: String) = catalog.byId(id)!!
    private fun judge(id: String, vararg transcripts: String, tolerance: Int = 5) =
        judge.judge(song(id), transcripts.toList(), tolerance)

    private fun m(title: Boolean, artist: Boolean, year: Boolean) = FieldMatches(title, artist, year)

    @Test fun englishSentence() {
        assertEquals(m(true, true, true), judge("ed-sheeran-shape-of-you", "Shape of You Ed Sheeran 2015"))
        assertEquals(m(true, true, false), judge("ed-sheeran-shape-of-you", "Shape of You Ed Sheeran 2015", tolerance = 0))
    }

    @Test fun hebrewPhoneticSpellings() =
        assertEquals(m(true, true, true), judge("ed-sheeran-shape-of-you", "שייפ אוף יו של אד שירן 2017"))

    @Test fun hebrewSongInBothScripts() {
        assertEquals(m(true, true, false), judge("shoshana-damari-kalaniyot", "כלניות שושנה דמארי"))
        assertEquals(m(true, true, false), judge("shoshana-damari-kalaniyot", "kalaniyot shoshana damari"))
    }

    @Test fun digitsAndNumberWordsAreInterchangeable() {
        assertEquals(m(true, true, true), judge("ariana-grande-7-rings", "seven rings ariana grande 2019"))
        assertEquals(m(true, true, true), judge("ariana-grande-7-rings", "7 rings ariana grande 2019"))
    }

    @Test fun luftballonsWithoutYear() {
        val r = judge("nena-99-luftballons", "99 luftballons nena")
        assertTrue(r.title)
        assertTrue(r.artist)
    }

    @Test fun luftballonsWithYear() =
        assertEquals(m(true, true, true), judge("nena-99-luftballons", "99 luftballons nena 1983"))

    @Test fun wrongTitleRightArtist() =
        assertEquals(m(false, true, true), judge("the-beatles-help", "yesterday the beatles 1965"))

    @Test fun emptyTranscript() = assertEquals(m(false, false, false), judge("the-beatles-help", ""))

    // ---- False-positive guards ----

    @Test fun otherSongEntirely() =
        assertEquals(m(false, false, false), judge("the-beatles-help", "shape of you ed sheeran 2017"))

    @Test fun titleAloneGivesNoArtist() {
        val r = judge("spice-girls-stop", "stop")
        assertTrue(r.title)
        assertFalse(r.artist)
    }

    @Test fun youtubeIsNotU2() {
        assertFalse(song("u2-with-or-without-you").artistAliases.any { normalize(it) == "youtube" })
        assertFalse(judge("u2-with-or-without-you", "with or without you by youtube 1987").artist)
    }

    @Test fun u2SpokenForms() {
        for (t in listOf("by you too", "by you to", "by you two", "by u2")) {
            assertTrue(t, judge("u2-with-or-without-you", "with or without you $t 1987").artist)
        }
    }

    @Test fun u2NotCreditedForNearbyWords() {
        for (t in listOf("i think", "a classic", "do you remember")) {
            assertFalse(t, judge("u2-with-or-without-you", "with or without you $t 1987").artist)
        }
    }

    @Test fun numberVariantsAddNoFuzz() {
        fun made(title: String) = Song(
            id = "x", title = Names("כותרת", title), artist = Names("אמן", "Artist"),
            script = Lang.EN, year = 2000, origin = Origin.INTL, genre = Genre.POP, deezerId = 0, rank = 0,
        )
        fun title(title: String, heard: String) = judge.judge(made(title), listOf(heard), 5).title
        assertFalse(title("One Two", "one i"))
        assertTrue(title("One Two", "1 2"))
        assertFalse(title("Love Two", "love i"))
        assertFalse(title("Love Two", "love three"))
        assertTrue(title("Love Two", "love 2"))
        assertTrue(title("Love Two", "lov two"))
    }

    @Test fun leadingHebrewLetterTheIsOptional() {
        val twist = Song(
            id = "chubby-checker-the-twist", title = Names("דה טוויסט", "The Twist"),
            artist = Names("צ'אבי צ'קר", "Chubby Checker"), script = Lang.EN, year = 1960,
            origin = Origin.INTL, genre = Genre.ROCK, deezerId = 0, rank = 0,
        )
        fun title(heard: String) = judge.judge(twist, listOf(heard), 5).title
        assertTrue(title("טוויסט של צ'אבי צ'קר 1960"))
        assertTrue(title("דה טוויסט של צ'אבי צ'קר 1960"))
        assertFalse(title("דה לוקומושן 1962"))
    }

    @Test fun englishPassGarbageMatchesNoSong() {
        // English-pass alternatives for recording 5 (a Hebrew sentence).
        val garbage = listOf(
            "I fell off the beginning so I'll go out of Champions time",
            "I fell off the beginning so I'll go out of Champions",
            "I fell off the beginning so I'll go out of Channel which time",
            "I fell off the beginning so I'll go out of Champions tank",
            "I fell off the beginning zorrogo elf Channel",
        )
        for (s in catalog.songs) {
            assertEquals(s.id, m(false, false, false), judge.judge(s, garbage, 5))
        }
    }
}
