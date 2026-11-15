package com.roadhit.game

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {
    @Test fun removesRemasterSuffix() =
        assertEquals("hound dog", normalize("Hound Dog - Remastered 2003"))

    @Test fun removesBracketedGroups() {
        assertEquals("shape of you", normalize("Shape of You (feat. X)"))
        assertEquals("song", normalize("Song [Live]"))
    }

    @Test fun keepsDashedPartWithoutMarker() =
        assertEquals("alt j breezeblocks", normalize("Alt-J - Breezeblocks"))

    @Test fun dropsLeadingThe() = assertEquals("beatles", normalize("The Beatles"))

    @Test fun removesNiqqud() = assertEquals("הכל", normalize("הַכֹּל"))

    @Test fun removesPunctuationAndFoldsFinalLetters() =
        assertEquals("שלומ עליכמ", normalize("שלום עליכם!"))

    @Test fun doesNotStripHebrewArticleFromWord() =
        assertEquals("הדג נחש", normalize("הדג נחש"))

    @Test fun dropsStandaloneHebrewArticle() = assertEquals("דג", normalize("ה דג"))

    @Test fun dropsLeadingHebrewLetterThe() = assertEquals(normalize("טוויסט"), normalize("דה טוויסט"))

    @Test fun keepsSingleWordHebrewLetterThe() = assertEquals("דה", normalize("דה"))

    @Test fun removesBidiMarks() =
        assertEquals("shape of you של אד שירנ", normalize("‏Shape of You של אד שירן"))

    @Test fun hyphenBecomesSpace() = assertEquals("rock n roll", normalize("Rock-n-Roll"))

    @Test fun maqafBecomesSpace() = assertEquals("אל תלכ", normalize("אל־תלך"))

    @Test fun gereshRemoved() = assertEquals("גירפות", normalize("ג'ירפות"))

    @Test fun apostropheRemoved() = assertEquals("dont stop", normalize("Don't Stop"))

    @Test fun collapsesWhitespaceAndKeepsDigits() =
        assertEquals("u2 one", normalize("  U2   One \n"))

    @Test fun foldsAccentsPrecomposed() {
        assertEquals("beyonce", normalize("Beyonc\u00E9"))
        assertEquals("cafe", normalize("caf\u00E9"))
        assertEquals("zoe", normalize("Zo\u00E9"))
        assertEquals("motorhead", normalize("Mot\u00F6rhead"))
    }

    @Test fun spokenTextKeepsBracketsAndDashSuffix() {
        assertEquals("shape of you 2017", normalizeSpoken("‏Shape of You - 2017"))
        assertEquals("song live", normalizeSpoken("Song (Live)"))
        assertEquals("beatles", normalizeSpoken("The Beatles"))
    }

    @Test fun foldsAccentsDecomposed() {
        assertEquals("cafe", normalize("cafe\u0301"))
        assertEquals("istanbul", normalize("\u0130stanbul"))
    }
}
