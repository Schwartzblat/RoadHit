package com.roadhit.session

import android.media.AudioManager
import androidx.media3.common.Player
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

class GamePlayerMappingTest {

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
        deezerId = 43,
    )

    private fun view(phase: Phase, revealed: Song? = null, current: Int = 0) = GameView(
        phase = phase,
        playerNames = listOf("שחקן 1", "שחקן 2"),
        scores = listOf(12, 7),
        currentPlayer = current,
        round = 1,
        rounds = 3,
        revealed = revealed,
    )

    private val noCover: (Song) -> String? = { error("cover looked up outside the reveal") }

    @Test
    fun `clip shows whose turn and the scores, playing`() {
        val face = faceOf(view(Phase.CLIP, current = 1), Lang.HE, noCover)
        assertTrue(face.playing)
        assertEquals(Player.STATE_READY, face.playbackState)
        assertEquals("התור של שחקן 2.", face.title)
        assertEquals("P1 12 · P2 7", face.artist)
        assertNull(face.coverUrl)
    }

    @Test
    fun `listening and setup are playing`() {
        val listening = faceOf(view(Phase.LISTENING), Lang.EN, noCover)
        assertTrue(listening.playing)
        assertEquals(Player.STATE_READY, listening.playbackState)
        assertEquals("שחקן 1, your turn.", listening.title)

        val setup = faceOf(GameView(Phase.SETUP, emptyList(), emptyList(), 0, 0, 0, null), Lang.HE, noCover)
        assertTrue(setup.playing)
        assertEquals(Player.STATE_READY, setup.playbackState)
        assertEquals("RoadHit", setup.title) // the app name in the app language (final review M8)
        assertNull(setup.artist)
        val setupEn = faceOf(GameView(Phase.SETUP, emptyList(), emptyList(), 0, 0, 0, null), Lang.EN, noCover)
        assertEquals("RoadHit", setupEn.title)
    }

    @Test
    fun `a view with no players yet shows the app name in the app language`() {
        val noPlayers = GameView(Phase.CLIP, emptyList(), emptyList(), 0, 0, 0, null)
        assertEquals("RoadHit", faceOf(noPlayers, Lang.HE, noCover).title)
        assertEquals("RoadHit", faceOf(noPlayers, Lang.EN, noCover).title)
    }

    @Test
    fun `paused is ready but not playing`() {
        val face = faceOf(view(Phase.PAUSED), Lang.HE, noCover)
        assertFalse(face.playing)
        assertEquals(Player.STATE_READY, face.playbackState)
        assertEquals("התור של שחקן 1.", face.title)
        assertNull(face.coverUrl)
    }

    @Test
    fun `idle is a non-playing item with the game name and an invitation to press play`() {
        val idle = GameView(Phase.IDLE, emptyList(), emptyList(), 0, 0, 0, null)
        val face = faceOf(idle, Lang.EN, noCover)
        assertFalse(face.playing)
        // Not STATE_IDLE: the car host shows "Could not load your selection" for an idle player.
        assertEquals(Player.STATE_ENDED, face.playbackState)
        assertEquals("RoadHit", face.title)
        assertEquals("Press play to start", face.artist)
        assertNull(face.coverUrl)
        val he = faceOf(idle, Lang.HE, noCover)
        assertEquals(Player.STATE_ENDED, he.playbackState)
        assertEquals("RoadHit", he.title)
        assertEquals("לחצו על הפעלה כדי להתחיל", he.artist)
    }

    @Test
    fun `finished is ended with a game over title and the final scores`() {
        val he = faceOf(view(Phase.FINISHED), Lang.HE, noCover)
        assertFalse(he.playing)
        assertEquals(Player.STATE_ENDED, he.playbackState)
        assertEquals("סוף המשחק", he.title)
        assertEquals("P1 12 · P2 7", he.artist)
        assertEquals("Game over", faceOf(view(Phase.FINISHED), Lang.EN, noCover).title)
    }

    @Test
    fun `reveal shows the original title with the year, the artist in the app language and the cover`() {
        val en = faceOf(view(Phase.REVEAL, hound), Lang.HE) { "https://cover/${it.deezerId}" }
        assertTrue(en.playing)
        assertEquals("Hound Dog (1956)", en.title)
        assertEquals("אלביס פרסלי", en.artist)
        assertEquals("https://cover/42", en.coverUrl)

        val he = faceOf(view(Phase.REVEAL, kalaniyot), Lang.EN) { null }
        assertEquals("כלניות (1948)", he.title)
        assertEquals("Shoshana Damari", he.artist)
        assertNull(he.coverUrl)
    }

    @Test
    fun `no phase but the reveal ever carries the song`() {
        // Defensive: even a view that wrongly carries a song outside REVEAL shows no trace of it.
        for (phase in Phase.entries - Phase.REVEAL) {
            val face = faceOf(view(phase, hound), Lang.EN, noCover)
            assertFalse(phase.name, "Hound" in face.title || "Elvis" in (face.artist ?: ""))
            assertNull(face.coverUrl)
        }
    }

    @Test
    fun `score line shortens names to initials or P-number`() {
        assertEquals(
            "AC 12 · D 7 · P3 9",
            scoreLine(listOf("Alon Cohen", "Dana", "Player 3"), listOf(12, 7, 9)),
        )
        assertEquals("P1 0 · P2 4", scoreLine(listOf("שחקן 1", "שחקן 2"), listOf(0, 4)))
        assertEquals("אכ 3 · P2 1", scoreLine(listOf("אלון כהן", " "), listOf(3, 1)))
    }

    @Test
    fun `score line uses P-numbers where initials collide`() {
        assertEquals("P1 1 · P2 2 · M 3", scoreLine(listOf("Dana", "David", "Moshe"), listOf(1, 2, 3)))
    }

    @Test
    fun `initials are capped at three letters`() {
        assertEquals("ABC 0", scoreLine(listOf("anna bella cara dina"), listOf(0)))
    }

    @Test
    fun `score line tolerates missing scores`() {
        assertEquals("P1 0 · P2 0", scoreLine(listOf("Player 1", "Player 2"), emptyList()))
    }

    @Test
    fun `focus changes map to game actions`() {
        assertEquals(FocusAction.PAUSE, focusAction(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT))
        assertEquals(FocusAction.PAUSE, focusAction(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK))
        assertEquals(FocusAction.RESUME, focusAction(AudioManager.AUDIOFOCUS_GAIN))
        assertEquals(FocusAction.STOP, focusAction(AudioManager.AUDIOFOCUS_LOSS))
        assertEquals(FocusAction.NONE, focusAction(12345))
    }
}
