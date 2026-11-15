package com.roadhit.game

import org.junit.Assert.assertEquals
import org.junit.Test

class ScoringTest {
    @Test
    fun allThreeRightEarnsBonus() {
        assertEquals(5, score(FieldMatches(title = true, artist = true, year = true)))
    }

    @Test
    fun titleAndArtistOnly() {
        assertEquals(2, score(FieldMatches(title = true, artist = true, year = false)))
    }

    @Test
    fun artistAndYearOnly() {
        assertEquals(2, score(FieldMatches(title = false, artist = true, year = true)))
    }

    @Test
    fun yearOnly() {
        assertEquals(1, score(FieldMatches(title = false, artist = false, year = true)))
    }

    @Test
    fun nothingRight() {
        assertEquals(0, score(FieldMatches(title = false, artist = false, year = false)))
    }
}
