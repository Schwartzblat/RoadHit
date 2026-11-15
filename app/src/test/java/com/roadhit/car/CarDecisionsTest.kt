package com.roadhit.car

import com.roadhit.game.GameView
import com.roadhit.game.Genre
import com.roadhit.game.Lang
import com.roadhit.game.Names
import com.roadhit.game.Origin
import com.roadhit.game.Phase
import com.roadhit.game.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CarDecisionsTest {

    private val song = Song(
        id = "eminem-lose-yourself", title = Names("לוז יורסלף", "Lose Yourself"), artist = Names("אמינם", "Eminem"),
        script = Lang.EN, year = 2002, origin = Origin.INTL, genre = Genre.HIPHOP, deezerId = 1, rank = 1,
    )

    private fun view(phase: Phase, round: Int = 2, rounds: Int = 5) = GameView(
        phase = phase,
        playerNames = listOf("Dana", "Avi"),
        scores = listOf(5, 3),
        currentPlayer = 1,
        round = round,
        rounds = rounds,
        revealed = if (phase == Phase.REVEAL) song else null,
    )

    // --- header title ---

    @Test
    fun `idle and finished show the app name in the app language`() {
        for (phase in listOf(Phase.IDLE, Phase.FINISHED)) {
            assertEquals("RoadHit", carHeaderTitle(view(phase), Lang.EN))
            assertEquals("RoadHit", carHeaderTitle(view(phase), Lang.HE))
        }
    }

    @Test
    fun `in a game the header is the round text the phone shows`() {
        for (phase in listOf(Phase.CLIP, Phase.LISTENING, Phase.REVEAL, Phase.PAUSED)) {
            assertEquals("Round 2 / 5", carHeaderTitle(view(phase), Lang.EN))
            assertEquals("סיבוב 2 מתוך 5", carHeaderTitle(view(phase), Lang.HE))
        }
    }

    @Test
    fun `before the rounds are known the header is the app name`() {
        assertEquals("RoadHit", carHeaderTitle(view(Phase.SETUP, round = 0, rounds = 0), Lang.EN))
        assertEquals("RoadHit", carHeaderTitle(view(Phase.SETUP, round = 0, rounds = 0), Lang.HE))
    }

    @Test
    fun `a round past the last is shown as the last`() {
        assertEquals("Round 5 / 5", carHeaderTitle(view(Phase.REVEAL, round = 6, rounds = 5), Lang.EN))
    }

    @Test
    fun `the header never carries the song`() {
        for (phase in Phase.entries) for (lang in Lang.entries) {
            val title = carHeaderTitle(view(phase), lang)
            for (word in listOf("Lose Yourself", "Eminem", "לוז יורסלף", "אמינם", "2002")) {
                assertFalse("$phase $lang: $title", title.contains(word))
            }
        }
    }

    // --- session create ---

    @Test
    fun `no microphone permission asks for it instead of starting`() {
        for (phase in listOf(Phase.IDLE, Phase.FINISHED)) {
            assertEquals(CarOpenAction.ASK_PERMISSION, carOpenAction(phase, micGranted = false))
        }
    }

    @Test
    fun `with the permission an idle or finished game starts`() {
        for (phase in listOf(Phase.IDLE, Phase.FINISHED)) {
            assertEquals(CarOpenAction.START_GAME, carOpenAction(phase, micGranted = true))
        }
    }

    @Test
    fun `a game already running is shown, not restarted`() {
        for (phase in listOf(Phase.SETUP, Phase.CLIP, Phase.LISTENING, Phase.REVEAL, Phase.PAUSED)) {
            assertEquals(CarOpenAction.SHOW_GAME, carOpenAction(phase, micGranted = true))
            // It was started with the permission; the car shows it rather than a permission screen.
            assertEquals(CarOpenAction.SHOW_GAME, carOpenAction(phase, micGranted = false))
        }
    }

    // --- session destroy ---

    @Test
    fun `a car disconnect stops the game when the phone screen is not in use`() {
        // Every phase: a stop also drops a start still on its way to the service.
        for (phase in Phase.entries) {
            assertEquals(CarCloseAction.STOP_GAME, carCloseAction(phase, phoneUiVisible = false))
        }
    }

    @Test
    fun `a car disconnect stops a running game also when the phone screen shows the app`() {
        // Ruling R38: the game never carries on over the phone speaker by itself; it stays saved.
        for (phase in listOf(Phase.SETUP, Phase.CLIP, Phase.LISTENING, Phase.REVEAL, Phase.PAUSED)) {
            assertEquals(CarCloseAction.STOP_GAME, carCloseAction(phase, phoneUiVisible = true))
        }
    }

    @Test
    fun `with the phone in use and no game there is nothing to stop`() {
        for (phase in listOf(Phase.IDLE, Phase.FINISHED)) {
            assertEquals(CarCloseAction.NONE, carCloseAction(phase, phoneUiVisible = true))
        }
    }

    // --- task removal (Ruling R39) ---

    @Test
    fun `swiping the app away keeps a game running while a car session is open`() {
        for (phase in listOf(Phase.SETUP, Phase.CLIP, Phase.LISTENING, Phase.REVEAL, Phase.PAUSED)) {
            assertEquals(TaskRemovedAction.KEEP_RUNNING, taskRemovedAction(phase, carSessionOpen = true))
        }
    }

    @Test
    fun `swiping the app away stops the game without a car session`() {
        for (phase in Phase.entries) {
            assertEquals(TaskRemovedAction.STOP_GAME, taskRemovedAction(phase, carSessionOpen = false))
        }
        for (phase in listOf(Phase.IDLE, Phase.FINISHED)) {
            assertEquals(TaskRemovedAction.STOP_GAME, taskRemovedAction(phase, carSessionOpen = true))
        }
    }

    // --- play command through the media session ---

    private val activePhases = listOf(Phase.SETUP, Phase.CLIP, Phase.LISTENING, Phase.REVEAL, Phase.PAUSED)

    @Test
    fun `play while idle or finished starts a game only when the app is open in the car or on the phone`() {
        for (phase in listOf(Phase.IDLE, Phase.FINISHED)) {
            assertEquals(MediaPlayAction.START_GAME, mediaPlayAction(phase, carSessionOpen = true, phoneUiVisible = false))
            assertEquals(MediaPlayAction.START_GAME, mediaPlayAction(phase, carSessionOpen = false, phoneUiVisible = true))
            assertEquals(MediaPlayAction.START_GAME, mediaPlayAction(phase, carSessionOpen = true, phoneUiVisible = true))
            assertEquals(MediaPlayAction.IGNORE, mediaPlayAction(phase, carSessionOpen = false, phoneUiVisible = false))
        }
    }

    @Test
    fun `play during a game resumes it wherever the app is`() {
        for (phase in activePhases) for (car in listOf(true, false)) for (phone in listOf(true, false)) {
            assertEquals("$phase car=$car phone=$phone", MediaPlayAction.RESUME, mediaPlayAction(phase, car, phone))
        }
    }
}
