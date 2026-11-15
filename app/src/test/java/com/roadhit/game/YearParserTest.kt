package com.roadhit.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YearParserTest {
    private fun last(s: String) = parseYears(s).lastOrNull()

    @Test fun digitsInSentence() = assertEquals(2015, last("shape of you ed sheeran 2015"))
    @Test fun twoDigitYears() {
        assertEquals(1985, last("85"))
        assertEquals(2017, last("17"))
        assertEquals(2026, last("26"))
        assertEquals(1927, last("27"))
    }
    @Test fun englishWords() {
        assertEquals(1985, last("nineteen eighty five"))
        assertEquals(2017, last("two thousand seventeen"))
        assertEquals(2017, last("twenty seventeen"))
        assertEquals(1985, last("eighty five"))
        assertEquals(1905, last("nineteen oh five"))
        assertEquals(2024, last("twenty twenty four"))
        assertEquals(2005, last("two thousand and five"))
        assertEquals(1990, last("nineteen ninety"))
        assertEquals(2020, last("twenty twenty"))
        assertEquals(2022, last("twenty two"))
    }
    @Test fun hebrewWords() {
        assertEquals(1985, last("אלף תשע מאות שמונים וחמש"))
        assertEquals(1985, last("שמונים וחמש"))
        assertEquals(2017, last("אלפיים ושבע עשרה"))
        assertEquals(2000, last("אלפיים"))
        assertEquals(1999, last("תשעים ותשע"))
        assertEquals(2024, last("אלפיים עשרים וארבע"))
        assertEquals(2010, last("אלפיים ועשר"))
        assertEquals(1990, last("שנת אלף תשע מאות ותשעים"))
        assertEquals(2011, last("אלפיים אחת עשרה"))
    }
    @Test fun longestMatchConsumesWords() {
        assertEquals(listOf(1985), parseYears("אלף תשע מאות שמונים וחמש"))
        assertEquals(listOf(1985), parseYears("nineteen eighty five"))
    }
    @Test fun lastOfSeveral() {
        assertEquals(1982, last("1999 prince 1982"))
        assertEquals(listOf(1999, 1983), parseYears("99 luftballons nena 1983"))
    }
    @Test fun notYears() {
        assertNull(last("hound dog elvis"))
        assertNull(last("שנת 3000"))
        assertNull(last("7 rings ariana grande"))
        assertNull(last("u2 one"))
        assertNull(last("1980s"))
        assertNull(last("123"))
    }
    @Test fun bidiMarkIgnored() = assertEquals(2017, last("‏2017"))
    @Test fun heardYear() {
        assertEquals(2017, heardYear(listOf("shape of you", "shape of you 2017")))
        assertEquals(2001, heardYear(listOf("hound dog", "1999 prince 2001", "2017")))
        assertNull(heardYear(emptyList()))
        assertNull(heardYear(listOf("hound dog", "elvis")))
    }
    @Test fun heardYearPrefersFullYearsAcrossAlternatives() {
        assertEquals(2003, heardYear(listOf("x 203", "x 20", "x 200", "x 2003")))
        assertEquals(1987, heardYear(listOf("85", "1987")))
        assertEquals(1985, heardYear(listOf("85")))
        assertEquals(1983, heardYear(listOf("99 luftballons nena 1983")))
        assertEquals(1982, heardYear(listOf("1999 prince 1982")))
    }
}
