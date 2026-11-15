package com.roadhit.ui

import com.roadhit.game.Genre
import com.roadhit.game.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

class UiStringsTest {

    /** Every string of [s]: each property, and each function called with sample arguments. */
    private fun allStrings(s: UiStrings): Map<String, String> =
        UiStrings::class.java.methods
            .filter { it.returnType == String::class.java }
            .associate { m: Method ->
                val args = m.parameterTypes.map<Class<*>, Any> { t ->
                    when (t) {
                        Int::class.javaPrimitiveType -> 3
                        String::class.java -> "Dana"
                        else -> error("unexpected parameter type $t in ${m.name}")
                    }
                }
                m.name to (m.invoke(s, *args.toTypedArray()) as String)
            }

    private val hebrewLetter = Regex("[א-ת]")

    @Test
    fun `the strings are found`() {
        assertTrue(allStrings(EnglishUi).size >= 20)
        assertEquals(allStrings(EnglishUi).keys, allStrings(HebrewUi).keys)
    }

    @Test
    fun `no string is blank`() {
        for (s in listOf(EnglishUi, HebrewUi)) {
            for ((name, text) in allStrings(s)) assertTrue("$s.$name is blank", text.isNotBlank())
        }
    }

    @Test
    fun `hebrew strings contain hebrew letters`() {
        // The app's name is RoadHit in Latin letters in both languages.
        for ((name, text) in allStrings(HebrewUi)) {
            if (name == "getAppName") continue
            assertTrue("HebrewUi.$name = $text", hebrewLetter.containsMatchIn(text))
        }
        assertEquals("RoadHit", HebrewUi.appName)
    }

    @Test
    fun `english strings contain no hebrew letters`() {
        for ((name, text) in allStrings(EnglishUi)) assertFalse("EnglishUi.$name = $text", hebrewLetter.containsMatchIn(text))
    }

    @Test
    fun `a tolerance of one year is singular`() {
        assertEquals(listOf("±0 years", "±1 year", "±2 years", "±10 years"), listOf(0, 1, 2, 10).map(EnglishUi::years))
        assertEquals(listOf("±0 שנים", "±1 שנה", "±2 שנים", "±10 שנים"), listOf(0, 1, 2, 10).map(HebrewUi::years))
    }

    @Test
    fun `one player is singular`() {
        assertEquals(listOf("1 player", "2 players", "8 players"), listOf(1, 2, 8).map(EnglishUi::players))
        assertEquals(listOf("שחקן אחד", "2 שחקנים", "8 שחקנים"), listOf(1, 2, 8).map(HebrewUi::players))
    }

    @Test
    fun `one round is singular`() {
        assertEquals(listOf("1 round", "5 rounds"), listOf(1, 5).map(EnglishUi::rounds))
        assertEquals(listOf("סיבוב אחד", "5 סיבובים"), listOf(1, 5).map(HebrewUi::rounds))
    }

    @Test
    fun `a decade range is named by its decades`() {
        assertEquals("All decades", EnglishUi.decades(1940, 2020))
        assertEquals("The 80s", EnglishUi.decades(1980, 1980))
        assertEquals("The 80s to the 2000s", EnglishUi.decades(1980, 2000))
        assertEquals("כל העשורים", HebrewUi.decades(1940, 2020))
        assertEquals("שנות ה-80", HebrewUi.decades(1980, 1980))
        assertEquals("שנות ה-80 עד שנות ה-2000", HebrewUi.decades(1980, 2000))
    }

    @Test
    fun `every genre has a name in both languages`() {
        for (s in listOf(EnglishUi, HebrewUi)) {
            val names = Genre.entries.map(s::genreName)
            assertEquals(names.size, names.toSet().size)
            assertTrue(names.none { it == s.genreAll })
        }
    }

    @Test
    fun `clip lengths are plural across their range`() {
        assertEquals("10 seconds", EnglishUi.seconds(10))
        assertEquals("30 seconds", EnglishUi.seconds(30))
        assertEquals("10 שניות", HebrewUi.seconds(10))
        assertEquals("30 שניות", HebrewUi.seconds(30))
    }

    @Test
    fun `strings follow the language`() {
        assertSame(HebrewUi, uiStringsFor(Lang.HE))
        assertSame(EnglishUi, uiStringsFor(Lang.EN))
    }

    @Test
    fun `numbers and names are in the texts`() {
        assertEquals("Round 2 / 5", EnglishUi.round(2, 5))
        assertEquals("±4 years", EnglishUi.years(4))
        assertTrue(HebrewUi.years(4).contains("±4"))
        assertEquals("סיבוב 2 מתוך 5", HebrewUi.round(2, 5))
        assertTrue(HebrewUi.turn("Noa").contains("Noa"))
        assertEquals("Player 3", EnglishUi.defaultPlayer(3))
        assertEquals("שחקן 3", HebrewUi.defaultPlayer(3))
    }
}
