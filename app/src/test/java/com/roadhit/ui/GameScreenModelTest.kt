package com.roadhit.ui

import com.roadhit.game.GameView
import com.roadhit.game.Genre
import com.roadhit.game.Lang
import com.roadhit.game.Names
import com.roadhit.game.Origin
import com.roadhit.game.Phase
import com.roadhit.game.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameScreenModelTest {

    private val hound = Song(
        id = "s1",
        title = Names("האונד דוג", "Hound Dog"),
        artist = Names("אלביס פרסלי", "Elvis Presley"),
        script = Lang.EN,
        year = 1956,
        origin = Origin.INTL,
        genre = Genre.ROCK,
        deezerId = 42,
        rank = 1,
    )

    private val kalaniyot = hound.copy(
        id = "s2",
        title = Names("כלניות", "Kalaniyot"),
        artist = Names("שושנה דמארי", "Shoshana Damari"),
        script = Lang.HE,
        year = 1948,
    )

    private fun view(phase: Phase, revealed: Song? = null) = GameView(
        phase = phase,
        playerNames = listOf("Dana", "Alon", "Noa"),
        scores = listOf(3, 5, 1),
        currentPlayer = 1,
        round = 2,
        rounds = 5,
        revealed = revealed,
    )

    private val idle = GameView(Phase.IDLE, emptyList(), emptyList(), 0, 0, 0, null)

    @Test
    fun `idle is the home screen with no scoreboard`() {
        val m = screenModel(idle, Lang.EN)
        assertEquals(GameScreenModel.Home(finished = false, finalScores = emptyList()), m)
    }

    @Test
    fun `finished is the home screen with the final scoreboard and the winner marked`() {
        val m = screenModel(view(Phase.FINISHED), Lang.EN) as GameScreenModel.Home
        assertTrue(m.finished)
        assertEquals(
            listOf(
                ScoreRow("Dana", 3, current = false, winner = false),
                ScoreRow("Alon", 5, current = false, winner = true),
                ScoreRow("Noa", 1, current = false, winner = false),
            ),
            m.finalScores,
        )
    }

    @Test
    fun `a tie marks every leader as winner`() {
        val m = screenModel(view(Phase.FINISHED).copy(scores = listOf(5, 5, 1)), Lang.EN) as GameScreenModel.Home
        assertEquals(listOf(true, true, false), m.finalScores.map { it.winner })
    }

    @Test
    fun `in game shows turn, round, scoreboard with the current player and status`() {
        val m = screenModel(view(Phase.CLIP), Lang.EN) as GameScreenModel.InGame
        assertEquals("Alon's turn", m.turn)
        assertEquals("Round 2 / 5", m.round)
        assertEquals(listOf(false, true, false), m.scoreboard.map { it.current })
        assertEquals(listOf(false, false, false), m.scoreboard.map { it.winner })
        assertEquals(listOf("Dana", "Alon", "Noa"), m.scoreboard.map { it.name })
        assertEquals(listOf(3, 5, 1), m.scoreboard.map { it.score })
        assertEquals(EnglishUi.statusClip, m.status)
        assertNull(m.revealed)
    }

    @Test
    fun `hebrew texts come from the hebrew strings`() {
        val m = screenModel(view(Phase.LISTENING), Lang.HE) as GameScreenModel.InGame
        assertEquals(HebrewUi.turn("Alon"), m.turn)
        assertEquals(HebrewUi.round(2, 5), m.round)
        assertEquals(HebrewUi.statusListening, m.status)
    }

    @Test
    fun `each in game phase has its status`() {
        val expected = mapOf(
            Phase.SETUP to EnglishUi.statusSetup,
            Phase.CLIP to EnglishUi.statusClip,
            Phase.LISTENING to EnglishUi.statusListening,
            Phase.REVEAL to EnglishUi.statusReveal,
            Phase.PAUSED to EnglishUi.statusPaused,
        )
        for ((phase, status) in expected) {
            val m = screenModel(view(phase, if (phase == Phase.REVEAL) hound else null), Lang.EN) as GameScreenModel.InGame
            assertEquals(phase.name, status, m.status)
        }
    }

    @Test
    fun `setup before the engine names the players shows no turn, round or scoreboard`() {
        val m = screenModel(idle.copy(phase = Phase.SETUP), Lang.EN) as GameScreenModel.InGame
        assertNull(m.turn)
        assertNull(m.round)
        assertTrue(m.scoreboard.isEmpty())
    }

    @Test
    fun `reveal carries the title in its original script, the artist in the app language and the year`() {
        val en = screenModel(view(Phase.REVEAL, kalaniyot), Lang.EN) as GameScreenModel.InGame
        assertEquals(RevealedSong("כלניות", "Shoshana Damari", 1948), en.revealed)
        val he = screenModel(view(Phase.REVEAL, hound), Lang.HE) as GameScreenModel.InGame
        assertEquals(RevealedSong("Hound Dog", "אלביס פרסלי", 1956), he.revealed)
    }

    @Test
    fun `no phase but reveal carries the song, even if the view has one`() {
        for (phase in Phase.entries.filter { it != Phase.REVEAL }) {
            for (lang in Lang.entries) {
                val m = screenModel(view(phase, hound), lang)
                val text = m.toString()
                assertFalse("$phase $lang: $text", text.contains("Hound") || text.contains("האונד") ||
                    text.contains("Elvis") || text.contains("אלביס") || text.contains("1956"))
                if (m is GameScreenModel.InGame) assertNull(m.revealed)
            }
        }
    }

    @Test
    fun `missing scores count as zero`() {
        val m = screenModel(view(Phase.CLIP).copy(scores = listOf(2)), Lang.EN) as GameScreenModel.InGame
        assertEquals(listOf(2, 0, 0), m.scoreboard.map { it.score })
    }
}

class PlayerNamesTest {
    @Test
    fun `setting a name pads earlier fields with empty names`() {
        assertEquals(listOf("", "", "Noa"), withPlayerName(emptyList(), 2, "Noa"))
    }

    @Test
    fun `replacing a name keeps the others`() {
        assertEquals(listOf("Dana", "Noa"), withPlayerName(listOf("Dana", "Alon"), 1, "Noa"))
    }

    @Test
    fun `clearing the last names drops the trailing empty ones`() {
        assertEquals(listOf("Dana"), withPlayerName(listOf("Dana", "", "Alon"), 2, ""))
        assertEquals(emptyList<String>(), withPlayerName(listOf("", "Alon"), 1, " "))
    }

    @Test
    fun `fields beyond eight players are ignored`() {
        assertEquals(listOf("Dana"), withPlayerName(listOf("Dana"), 8, "Noa"))
    }
}
