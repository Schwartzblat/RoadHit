package com.roadhit.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyMatcherTest {
    @Test fun exactPhraseInsideSentence() =
        assertTrue(containsClose("i think its shape of you by ed sheeran", "shape of you"))

    @Test fun oneEditInLongPhrase() =
        assertTrue(containsClose("shape of yu ed sheeran", "shape of you"))

    @Test fun shortNeedleNeedsExactWord() {
        assertFalse(containsClose("you too are here", "u2"))
        assertTrue(containsClose("u2 one", "u2"))
    }

    @Test fun neverMatchesInsideAWord() {
        assertFalse(containsClose("yesterday", "yes"))
        assertTrue(containsClose("yes owner of a lonely heart", "yes"))
        assertFalse(containsClose("להיות באש", "אש"))
        assertTrue(containsClose("אש של אתניקס", "אש"))
    }

    @Test fun fourLetterNeedleAllowsOneEdit() = assertTrue(containsClose("helps", "help"))

    @Test fun numberTokensMustMatchExactly() {
        assertFalse(containsClose("love 3", "love 2"))
        assertFalse(containsClose("you i", "you 2"))
        assertTrue(containsClose("lov 2", "love 2"))
        assertFalse(containsClose("1998", "1999"))
    }

    @Test fun emptyNeedleIsFalse() = assertFalse(containsClose("anything", ""))

    @Test fun allowedEditsBands() {
        assertEquals(0, MatchThresholds.allowedEdits(3))
        assertEquals(1, MatchThresholds.allowedEdits(4))
        assertEquals(1, MatchThresholds.allowedEdits(5))
        assertEquals(1, MatchThresholds.allowedEdits(6))
        assertEquals(3, MatchThresholds.allowedEdits(12))
    }
}
