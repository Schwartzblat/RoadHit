package com.roadhit.game

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

private const val SHAPE = "ed-sheeran-shape-of-you"
private const val RINGS = "ariana-grande-7-rings"
private const val HELP = "the-beatles-help"
private const val LOSE = "eminem-lose-yourself"
private const val HOUND = "elvis-presley-hound-dog"

/** A fully right answer for each test song. */
private val RIGHT = mapOf(
    SHAPE to "shape of you ed sheeran 2017",
    RINGS to "seven rings ariana grande 2019",
    HELP to "help the beatles 1965",
    LOSE to "lose yourself eminem 2002",
)

/** A song whose title is the PASS phrase "לא יודע" (as in the bundled catalog). */
private val loYodea = Song(
    id = "yehuda-poliker-lo-yodea",
    title = Names("לא יודע", "Lo Yodea"),
    artist = Names("יהודה פוליקר", "Yehuda Poliker"),
    script = Lang.HE,
    year = 1996,
    origin = Origin.IL,
    genre = Genre.ROCK,
    deezerId = 1558401352,
    rank = 62606,
)

private fun builtSong(id: String, title: String, titleHe: String, artist: String, artistHe: String, year: Int) = Song(
    id = id, title = Names(titleHe, title), artist = Names(artistHe, artist), script = Lang.EN, year = year,
    origin = Origin.INTL, genre = Genre.POP, deezerId = 1, rank = 1,
)

private val u2One = builtSong("u2-one", "One", "וואן", "U2", "יו טו", 1991)
private val oneMoreTime = builtSong("daft-punk-one-more-time", "One More Time", "וואן מור טיים", "Daft Punk", "דאפט פאנק", 2000)
private val tooClose = builtSong("next-too-close", "Too Close", "טו קלוז", "Next", "נקסט", 1998)

private val NONE = FieldMatches(title = false, artist = false, year = false)

private class Harness(
    songIds: List<String>,
    settings: GameSettings? = null,
    game: SavedGame? = null,
    var prefs: Prefs = Prefs(),
    allSongs: Boolean = false,
    songList: List<Song>? = null,
) {
    val log = EventLog()
    val clips = FakeClipPlayer(log)
    val speaker = FakeSpeaker(log)
    val listener = FakeListener(log)
    val display = FakeDisplay()
    val store = FakeStore(log, settings, game)
    val songs = FakeSongSource(songList ?: if (allSongs) seedCatalog.songs else songIds.map(::seedSong))
    val engine = GameEngine(clips, speaker, listener, display, store, songs, LocalAnswerJudge()) { prefs }
    val p: Phrases get() = phrasesFor(prefs.lang)

    /** Setup through last time's settings and "start". */
    fun quickStart(players: Int, rounds: Int) {
        store.settings = GameSettings(players = players, rounds = rounds)
        listener.speech("start")
    }

    fun right(id: String) = answer(RIGHT.getValue(id))

    /** An answer said with the answer phrase. */
    fun answer(text: String, audio: Boolean = false) = listener.speech("the answer is $text", audio = audio)
    fun names(n: Int) = (1..n).map { p.defaultPlayerName(it) }
    val finalScores get() = display.views.last { it.phase == Phase.FINISHED }.scores
    val phases get() = display.views.map { it.phase }
}

@OptIn(ExperimentalCoroutinesApi::class)
class GameEngineTest {

    private fun TestScope.launchRun(h: Harness) = launch { h.engine.run() }

    // ---- 1 ----

    @Test fun twoPlayersOneRound() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.quickStart(players = 2, rounds = 1)
        h.right(SHAPE)
        h.answer("seven rings") // title only
        h.engine.run()

        assertEquals(listOf(5, 1), h.finalScores)
        assertTrue(h.speaker.texts.contains(h.p.ranking(h.names(2), listOf(5, 1))))
        assertNull(h.store.gameSaves.last())
        assertEquals("saveGame:null", h.log.events.last { it.startsWith("save") })
        assertEquals(Phase.FINISHED, h.display.last.phase)
        assertEquals(0, h.listener.remaining)
    }

    // ---- 2 ----

    @Test fun nextDuringClipStopsItAndBeeps() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.clips.hold = true
        h.right(SHAPE)
        val job = launchRun(h)
        advanceUntilIdle()
        assertTrue(h.clips.isPlaying)
        assertEquals(0, h.speaker.beeps)

        h.engine.onButton(Button.NEXT)
        advanceUntilIdle()
        assertEquals(1, h.clips.stops)
        assertEquals("beep", h.log.after("stop"))
        assertTrue(job.isCompleted)
        assertEquals(listOf(5), h.finalScores)
    }

    // ---- 3 ----

    @Test fun againTwicePlaysTwiceAndSaysAgainUsedOnce() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.listener.speech("again")
        h.listener.speech("again")
        h.right(SHAPE)
        h.engine.run()

        assertEquals(listOf(SHAPE, SHAPE), h.clips.played)
        assertEquals(1, h.speaker.count(h.p.againUsed()))
        assertEquals(listOf(5), h.finalScores)
    }

    // ---- 4 ----

    @Test fun silenceAndTalkWithoutTheAnswerPhraseAreIgnoredWithoutLimit() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.listener.silence(5)
        h.listener.speech("אני חושב שזה אד שירן")
        h.listener.speech("shape of you ed sheeran 2017") // a guess, not an answer
        h.listener.silence(3)
        h.listener.speech("לא יודע") // talk, not a pass
        h.right(SHAPE)
        h.engine.run()

        val texts = h.speaker.texts
        assertEquals(1, h.speaker.beeps)
        assertEquals(texts.indexOf(h.p.answerRule()) + 1, texts.indexOf(h.p.verdict(FieldMatches(true, true, true), 5)))
        assertEquals(listOf(5), h.finalScores)
        assertEquals(0, h.listener.remaining)
    }

    // ---- 5 ----

    @Test fun failedThenRightAnswer() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.quickStart(1, 1)
        h.listener.failed()
        h.right(SHAPE)
        h.engine.run()

        assertEquals(listOf(SHAPE), h.clips.played)
        assertEquals(1, h.speaker.count(h.p.didNotUnderstand()))
        assertEquals(listOf(5), h.finalScores)
    }

    // ---- 6 ----

    @Test fun threeFailuresPauseUntilPlay() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.quickStart(1, 1)
        h.listener.failed(3)
        h.right(SHAPE)
        val job = launchRun(h)
        advanceUntilIdle()

        assertEquals(1, h.speaker.count(h.p.noConnection()))
        assertEquals(Phase.PAUSED, h.display.last.phase)
        assertEquals(listOf(SHAPE), h.clips.played)
        assertFalse(job.isCompleted)

        h.engine.onButton(Button.PLAY)
        advanceUntilIdle()
        assertEquals(listOf(SHAPE, SHAPE), h.clips.played)
        assertEquals(listOf(5), h.finalScores)
    }

    // ---- 7 ----

    @Test fun oneLoadFailureSilentlyPicksAnotherSong() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.quickStart(1, 1)
        h.clips.failNext = 1
        h.right(RINGS)
        h.engine.run()

        assertEquals(listOf(SHAPE, RINGS), h.clips.played)
        val e = h.log.events
        val between = e.subList(e.indexOf("play:$SHAPE") + 1, e.indexOf("play:$RINGS"))
        assertEquals(listOf("saveGame:$RINGS"), between) // nothing spoken about it
        assertEquals(listOf(5), h.finalScores)
    }

    @Test fun threeLoadFailuresPauseUntilPlay() = runTest {
        val h = Harness(listOf(SHAPE, RINGS, HELP))
        h.quickStart(1, 1)
        h.clips.failNext = 3
        h.right(HELP)
        launchRun(h)
        advanceUntilIdle()

        assertEquals(listOf(SHAPE, RINGS, HELP), h.clips.played)
        assertEquals(1, h.speaker.count(h.p.noConnection()))
        assertEquals(Phase.PAUSED, h.display.last.phase)

        h.engine.onButton(Button.PLAY)
        advanceUntilIdle()
        assertEquals(listOf(SHAPE, RINGS, HELP, HELP), h.clips.played)
        assertEquals(listOf(5), h.finalScores)
    }

    // ---- 8 ----

    @Test fun stopGameConfirmedYes() = runTest {
        val h = Harness(listOf(SHAPE, RINGS, HELP, LOSE))
        h.quickStart(2, 2)
        h.listener.speech("stop game")
        h.listener.speech("כן")
        h.engine.run()

        assertEquals(1, h.speaker.count(h.p.confirmStop()))
        assertTrue(h.speaker.texts.contains(h.p.ranking(h.names(2), listOf(0, 0))))
        assertNull(h.store.gameSaves.last())
        assertEquals(Phase.FINISHED, h.display.last.phase)
        assertEquals(listOf(SHAPE), h.clips.played)
    }

    @Test fun stopGameDeclinedListensAgainForSameSong() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.listener.speech("stop game")
        h.listener.speech("לא")
        h.right(SHAPE)
        h.engine.run()

        assertEquals(listOf(SHAPE), h.clips.played)
        assertEquals(listOf(5), h.finalScores)
        assertEquals(3 + 1, h.listener.listens.size) // start + stop + no + answer
    }

    // ---- 9 ----

    @Test fun scoreCommandDoesNotConsumeTurn() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.quickStart(2, 1)
        h.listener.speech("score")
        h.right(SHAPE)
        h.right(RINGS)
        h.engine.run()

        val scoresText = h.p.scores(h.names(2), listOf(0, 0))
        assertEquals(1, h.speaker.count(scoresText))
        assertEquals("beep", h.log.after("say:$scoresText"))
        assertEquals(listOf(SHAPE, RINGS), h.clips.played)
        assertEquals(listOf(5, 5), h.finalScores)
    }

    // ---- 10 ----

    private val savedGame = SavedGame(
        settings = GameSettings(players = 2, rounds = 2),
        scores = listOf(4, 2),
        round = 2,
        playerIndex = 1,
        currentSongId = RINGS,
    )

    @Test fun continueSavedGame() = runTest {
        val h = Harness(listOf(SHAPE, RINGS), game = savedGame)
        h.listener.speech("continue")
        h.right(RINGS)
        h.engine.run()

        assertTrue(h.speaker.texts.contains(h.p.askContinue()))
        assertEquals(RINGS, h.clips.played.first())
        assertEquals(listOf(4, 7), h.finalScores)
        assertTrue(h.store.settingsSaves.isEmpty())
    }

    @Test fun continuingASavedGamePastTheLastRoundReadsTheRanking() = runTest {
        // Stopped during the last turn's verdict: the save points one round past the last.
        val finished = savedGame.copy(round = 3, playerIndex = 0, currentSongId = null)
        val h = Harness(listOf(SHAPE, RINGS), game = finished)
        h.listener.speech("continue")
        h.engine.run()

        assertEquals(1, h.speaker.count(h.p.askContinue()))
        assertTrue(h.clips.played.isEmpty())
        assertTrue(h.speaker.texts.contains(h.p.ranking(h.names(2), listOf(4, 2))))
        assertEquals(listOf(4, 2), h.finalScores)
    }

    @Test fun newGameInsteadOfSavedGame() = runTest {
        val h = Harness(listOf(SHAPE, RINGS), game = savedGame)
        h.listener.speech("new game")
        listOf("2", "mixed", "all", "all", "1").forEach { h.listener.speech(it) }
        h.right(SHAPE)
        h.listener.speech("pass")
        h.engine.run()

        assertEquals(1, h.speaker.count(h.p.askPlayers()))
        assertNull(h.store.gameSaves.first())
        assertEquals(listOf(5, 0), h.finalScores)
    }

    // ---- 11 and ruling 1: second-language pass ----

    @Test fun relistenWhenTitleUnmatchedAndAudioPresent() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.answer("ed sheeran 2017", audio = true)
        h.listener.relistenResults.add(listOf("shape of you"))
        h.engine.run()

        assertEquals(listOf(Lang.EN), h.listener.relistens)
        assertEquals(listOf(5), h.finalScores) // judged over both passes
    }

    @Test fun relistenInHebrewWhenAppIsEnglish() = runTest {
        val h = Harness(listOf(SHAPE), prefs = Prefs(lang = Lang.EN))
        h.quickStart(1, 1)
        h.answer("ed sheeran 2017", audio = true)
        h.engine.run()
        assertEquals(listOf(Lang.HE), h.listener.relistens)
        assertTrue(h.listener.listens.all { it.first == Lang.EN })
    }

    @Test fun noRelistenWhenAllMatchedAndYearHeard() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.answer(RIGHT.getValue(SHAPE), audio = true)
        h.engine.run()
        assertTrue(h.listener.relistens.isEmpty())
        assertEquals(listOf(5), h.finalScores)
    }

    @Test fun relistenWhenNoYearHeard() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.answer("shape of you ed sheeran", audio = true)
        h.listener.relistenResults.add(listOf("2017"))
        h.engine.run()
        assertEquals(listOf(Lang.EN), h.listener.relistens)
        assertEquals(listOf(5), h.finalScores)
    }

    @Test fun noRelistenWithoutAudio() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.answer("ed sheeran 2017")
        h.engine.run()
        assertTrue(h.listener.relistens.isEmpty())
        assertEquals(listOf(2), h.finalScores)
    }

    // ---- Ruling R34: a no-match first pass is tried in the other language ----

    @Test fun unclearAnswerIsJudgedFromTheOtherLanguageWithNoThirdPass() = runTest {
        val h = Harness(listOf(HOUND))
        h.quickStart(1, 1)
        h.listener.unclear()
        h.listener.relistenResults.add(listOf("the answer is hound dog by elvis presley 1956"))
        h.engine.run()

        assertEquals(listOf(Lang.EN), h.listener.relistens) // once, in the other language
        assertEquals(listOf(5), h.finalScores)
    }

    @Test fun unclearInEnglishGameIsTriedInHebrew() = runTest {
        val h = Harness(listOf(HOUND), prefs = Prefs(lang = Lang.EN))
        h.quickStart(1, 1)
        h.listener.unclear()
        h.listener.relistenResults.add(listOf("התשובה היא האונד דוג של אלביס פרסלי 1956"))
        h.engine.run()
        assertEquals(listOf(Lang.HE), h.listener.relistens)
        assertEquals(listOf(5), h.finalScores)
    }

    @Test fun unclearWithNothingInTheOtherLanguageIsSilence() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.quickStart(2, 1)
        h.listener.unclear(2) // second pass returns nothing both times: silence, ignored
        h.right(SHAPE)
        h.right(RINGS)
        h.engine.run()

        assertEquals(listOf(Lang.EN, Lang.EN), h.listener.relistens)
        assertEquals(2, h.speaker.beeps) // one per turn
        assertEquals(listOf(5, 5), h.finalScores)
    }

    @Test fun unclearSetupAnswerIsAcceptedFromTheOtherLanguage() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.store.settings = GameSettings(players = 1, rounds = 1)
        h.listener.speech("change")
        h.listener.unclear()
        h.listener.relistenResults.add(listOf("two players"))
        listOf("mixed", "all", "all", "1").forEach { h.listener.speech(it) }
        h.right(SHAPE)
        h.right(RINGS)
        h.engine.run()

        assertEquals(1, h.speaker.count(h.p.askPlayers()))
        assertEquals(0, h.speaker.count(h.p.didNotUnderstand()))
        assertEquals(listOf(GameSettings(players = 2, rounds = 1)), h.store.settingsSaves)
        assertEquals(listOf(5, 5), h.finalScores)
    }

    // ---- 12 ----

    @Test fun revealedOnlyInRevealPhase() = runTest {
        val h = Harness(listOf(SHAPE, RINGS, HELP, LOSE))
        h.quickStart(2, 2)
        h.listener.silence()
        h.right(SHAPE)
        h.listener.failed(3)
        h.right(RINGS)
        h.listener.speech("again")
        h.listener.speech("pass")
        h.listener.speech("score")
        h.answer("lose yourself")
        launchRun(h)
        advanceUntilIdle()
        assertEquals(Phase.PAUSED, h.display.last.phase)
        h.engine.onButton(Button.PLAY)
        advanceUntilIdle()

        assertEquals(listOf(5, 6), h.finalScores)
        val phases = h.phases.toSet()
        assertTrue(phases.containsAll(listOf(Phase.CLIP, Phase.LISTENING, Phase.PAUSED, Phase.REVEAL)))
        for (v in h.display.views) {
            if (v.phase == Phase.REVEAL) assertTrue(v.revealed != null) else assertNull("in ${v.phase}", v.revealed)
        }
    }

    // ---- 13 (as corrected in fix round 1) ----

    @Test fun catalogTooSmallReasksPlayersAndRounds() = runTest {
        val h = Harness(emptyList(), allSongs = true)
        listOf("8", "mixed", "all", "all", "20").forEach { h.listener.speech(it) } // 160 needed, 24 exist
        listOf("2", "5", "mixed", "all", "all").forEach { h.listener.speech(it) }
        repeat(10) { h.listener.speech("pass") }
        h.engine.run()

        val texts = h.speaker.texts
        val tooFew = texts.indexOf(h.p.tooFewSongs(24, 160))
        assertTrue(tooFew >= 0)
        assertEquals(h.p.askPlayers(), texts[tooFew + 1])
        assertEquals(h.p.askRounds(), texts[tooFew + 2])
        assertEquals(h.p.askPool(), texts[tooFew + 3])
        assertEquals(1, texts.count { it.startsWith(h.p.tooFewSongs(24, 160)) })
        assertEquals(listOf(GameSettings(players = 2, rounds = 5)), h.store.settingsSaves)
        assertEquals(10, h.clips.played.size)
        assertEquals(listOf(0, 0), h.finalScores)
    }

    // ---- ruling 8 ----

    @Test fun tooFewSongsReasksOnlyTheFilter() = runTest {
        val h = Harness(emptyList(), allSongs = true)
        // Israeli songs from the 60s to the 80s: 6 of 24; 2 x 5 = 10 needed
        listOf("2", "israeli", "from the sixties to the eighties", "all", "5").forEach { h.listener.speech(it) }
        listOf("mixed", "all", "all").forEach { h.listener.speech(it) }
        repeat(10) { h.listener.speech("pass") }
        h.engine.run()

        val texts = h.speaker.texts
        val tooFew = texts.indexOf(h.p.tooFewSongs(6, 10))
        assertTrue(tooFew >= 0)
        assertEquals(h.p.askPool(), texts[tooFew + 1])
        assertEquals(1, h.speaker.count(h.p.askPlayers()))
        assertEquals(1, h.speaker.count(h.p.askRounds()))
        assertEquals(2, h.speaker.count(h.p.askPool()))
        assertEquals(2, h.speaker.count(h.p.askDecades()))
        assertEquals(2, h.speaker.count(h.p.askGenre()))
        assertEquals(listOf(GameSettings(players = 2, rounds = 5)), h.store.settingsSaves)
        assertEquals(listOf(0, 0), h.finalScores)
    }

    @Test fun lastSettingsTooBigForCatalogAreChecked() = runTest {
        val h = Harness(emptyList(), allSongs = true)
        h.quickStart(players = 8, rounds = 20)
        listOf("1", "1", "mixed", "all", "all").forEach { h.listener.speech(it) }
        h.listener.speech("pass")
        h.engine.run()

        val texts = h.speaker.texts
        val tooFew = texts.indexOf(h.p.tooFewSongs(24, 160))
        assertTrue(tooFew >= 0)
        assertEquals(h.p.askPlayers(), texts[tooFew + 1])
        assertEquals(1, h.speaker.count(h.p.letsStart())) // the settings changed by voice
        assertEquals(listOf(GameSettings(players = 1, rounds = 1)), h.store.settingsSaves)
        assertEquals(1, h.clips.played.size)
        assertEquals(listOf(0), h.finalScores)
    }

    // ---- 14 ----

    @Test fun lastSettingsAndStart() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.right(SHAPE)
        h.engine.run()

        val s = GameSettings(players = 1, rounds = 1)
        val texts = h.speaker.texts
        assertTrue(texts.contains(h.p.startOrChange()))
        assertFalse(texts.contains(h.p.letsStart())) // last time's settings: nothing more to say
        for (q in listOf(h.p.askPlayers(), h.p.askPool(), h.p.askDecades(), h.p.askGenre(), h.p.askRounds())) {
            assertFalse(q, texts.contains(q))
        }
        assertEquals(listOf(s), h.store.settingsSaves)
    }

    @Test fun lastSettingsAndChangeAsksEverything() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.store.settings = GameSettings(players = 1, rounds = 1)
        h.listener.speech("change")
        listOf("2", "mixed", "all", "all", "1").forEach { h.listener.speech(it) }
        h.right(SHAPE)
        h.right(RINGS)
        h.engine.run()
        assertEquals(1, h.speaker.count(h.p.askPlayers()))
        assertEquals(1, h.speaker.count(h.p.letsStart()))
        assertEquals(listOf(GameSettings(players = 2, rounds = 1)), h.store.settingsSaves)
        assertEquals(listOf(5, 5), h.finalScores)
    }

    @Test fun changingByVoiceReplacesExactYearsWithTheSpokenDecades() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.store.settings = GameSettings(players = 1, rounds = 1, fromYear = 1990, toYear = 1995)
        h.listener.speech("change")
        listOf("2", "mixed", "all", "all", "1").forEach { h.listener.speech(it) }
        h.right(SHAPE)
        h.right(RINGS)
        h.engine.run()
        assertEquals(listOf(GameSettings(players = 2, rounds = 1)), h.store.settingsSaves)
    }

    @Test fun changingByVoiceKeepsTheDifficultyChosenInSettings() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        // Two songs of one origin and decade: medium keeps both (easy would keep one, too few for two turns).
        h.store.settings = GameSettings(players = 1, rounds = 1, difficulty = Difficulty.MEDIUM)
        h.listener.speech("change")
        listOf("2", "mixed", "all", "all", "1").forEach { h.listener.speech(it) }
        h.right(SHAPE)
        h.right(RINGS)
        h.engine.run()
        assertEquals(listOf(GameSettings(players = 2, rounds = 1, difficulty = Difficulty.MEDIUM)), h.store.settingsSaves)
    }

    @Test fun exactYearsWithTooFewSongsAreWidenedByTheFilterQuestions() = runTest {
        val h = Harness(emptyList(), allSongs = true)
        h.store.settings = GameSettings(players = 2, rounds = 5, fromYear = 2002, toYear = 2004) // 3 songs, 10 needed
        h.listener.speech("start")
        listOf("mixed", "all", "all").forEach { h.listener.speech(it) }
        repeat(10) { h.listener.speech("pass") }
        h.engine.run()

        val texts = h.speaker.texts
        val tooFew = texts.indexOf(h.p.tooFewSongs(3, 10))
        assertTrue(tooFew >= 0)
        assertEquals(h.p.askPool(), texts[tooFew + 1])
        assertEquals(listOf(GameSettings(players = 2, rounds = 5)), h.store.settingsSaves)
    }

    // ---- 15 ----

    @Test fun genreWhichOnes() = runTest {
        val h = Harness(emptyList(), allSongs = true)
        listOf("1", "mixed", "all", "which", "רוק", "1").forEach { h.listener.speech(it) }
        h.listener.speech("pass")
        h.engine.run()

        val texts = h.speaker.texts
        assertEquals(1, h.speaker.count(h.p.genreList()))
        assertEquals(2, h.speaker.count(h.p.askGenre()))
        assertEquals(h.p.askGenre(), texts[texts.indexOf(h.p.genreList()) + 1])
        assertEquals(Genre.ROCK, h.store.settingsSaves.single().genre)
        assertEquals(Genre.ROCK, seedSong(h.clips.played.single()).genre)
    }

    // ---- 16 ----

    @Test fun playerNamesFromPrefs() = runTest {
        val h = Harness(listOf(SHAPE, RINGS), prefs = Prefs(playerNames = listOf("דנה")))
        h.quickStart(2, 1)
        h.right(SHAPE)
        h.right(RINGS)
        h.engine.run()

        val texts = h.speaker.texts
        assertTrue(texts.contains(h.p.turn("דנה")))
        assertTrue(texts.contains(h.p.turn(h.p.defaultPlayerName(2))))
        assertEquals(listOf("דנה", h.p.defaultPlayerName(2)), h.display.last.playerNames)
    }

    @Test fun blankPlayerNameFallsBackToDefault() = runTest {
        val h = Harness(listOf(SHAPE, RINGS), prefs = Prefs(playerNames = listOf(" ")))
        h.quickStart(2, 1)
        h.right(SHAPE)
        h.right(RINGS)
        h.engine.run()
        assertTrue(h.speaker.texts.contains(h.p.turn(h.p.defaultPlayerName(1))))
    }

    @Test fun phrasesFollowPrefsLanguageAtSpeakingTime() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.quickStart(2, 1)
        h.right(SHAPE)
        h.listener.speech("score")
        h.right(RINGS)
        launchRun(h)
        h.clips.hold = true
        advanceUntilIdle()
        // first clip held: switch language, then let the game go on
        h.prefs = h.prefs.copy(lang = Lang.EN)
        h.clips.hold = false
        h.clips.release()
        advanceUntilIdle()
        assertTrue(h.speaker.texts.contains(EnglishPhrases.scores(listOf("Player 1", "Player 2"), listOf(5, 0))))
        assertTrue(h.speaker.said.flatten().last().lang == Lang.EN)
    }

    // ---- ruling 4: pause ----

    @Test fun pauseDuringClipStopsAndRestartsTurnKeepingReplayAllowance() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.quickStart(1, 1)
        h.clips.hold = true
        h.listener.speech("again")
        h.listener.speech("again")
        h.right(SHAPE)
        launchRun(h)
        advanceUntilIdle()
        h.clips.release() // first play ends on its own
        advanceUntilIdle()
        assertTrue(h.clips.isPlaying) // the replay
        h.engine.onButton(Button.PLAY) // meaningless now: dropped
        h.engine.onButton(Button.PAUSE)
        advanceUntilIdle()

        assertEquals(1, h.clips.stops)
        assertEquals(Phase.PAUSED, h.display.last.phase)
        assertFalse(h.clips.isPlaying)
        assertEquals(listOf(SHAPE, SHAPE), h.clips.played)

        h.engine.onButton(Button.PLAY)
        advanceUntilIdle()
        assertEquals(listOf(SHAPE, SHAPE, SHAPE), h.clips.played) // the turn started again
        assertTrue(h.clips.isPlaying)
        h.clips.release()
        advanceUntilIdle()

        assertEquals(listOf(SHAPE, SHAPE, SHAPE), h.clips.played)
        assertEquals(1, h.speaker.count(h.p.againUsed()))
        assertEquals(listOf(5), h.finalScores)
    }

    @Test fun pauseDuringListeningDiscardsTheAnswer() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        val gate = h.listener.hold()
        h.right(SHAPE)
        launchRun(h)
        advanceUntilIdle()
        assertEquals(Phase.LISTENING, h.display.last.phase)

        h.engine.onButton(Button.PAUSE)
        advanceUntilIdle()
        assertEquals(Phase.LISTENING, h.display.last.phase) // waits for the listen to return
        gate.complete(Heard.Speech(listOf(RIGHT.getValue(SHAPE)), null))
        advanceUntilIdle()
        assertEquals(Phase.PAUSED, h.display.last.phase)
        assertTrue(h.display.views.none { it.phase == Phase.REVEAL })

        h.engine.onButton(Button.PLAY)
        advanceUntilIdle()
        assertEquals(listOf(SHAPE, SHAPE), h.clips.played)
        assertEquals(listOf(5), h.finalScores)
    }

    @Test fun pauseDuringSpeechTakesEffectWhenUtteranceEnds() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.quickStart(2, 1)
        val turnText = h.p.turn(h.p.defaultPlayerName(1))
        h.speaker.holdOn = turnText
        h.right(SHAPE)
        h.right(RINGS)
        launchRun(h)
        advanceUntilIdle()
        assertTrue(h.speaker.isHolding)

        h.engine.onButton(Button.PAUSE)
        advanceUntilIdle()
        assertTrue(h.display.last.phase != Phase.PAUSED)
        h.speaker.release()
        advanceUntilIdle()
        assertEquals(Phase.PAUSED, h.display.last.phase)
        assertTrue(h.clips.played.isEmpty())

        h.engine.onButton(Button.NEXT) // dropped while paused
        h.engine.onButton(Button.PLAY)
        advanceUntilIdle()
        assertEquals(2, h.speaker.count(turnText))
        assertEquals(listOf(SHAPE, RINGS), h.clips.played)
        assertEquals(listOf(5, 5), h.finalScores)
    }

    @Test fun pauseDuringSetupTakesEffectBeforeNextQuestion() = runTest {
        val h = Harness(listOf(SHAPE))
        val gate = h.listener.hold()
        listOf("mixed", "all", "all", "1").forEach { h.listener.speech(it) }
        h.right(SHAPE)
        launchRun(h)
        advanceUntilIdle()

        h.engine.onButton(Button.PAUSE)
        gate.complete(Heard.Speech(listOf("1"), null))
        advanceUntilIdle()
        assertEquals(Phase.PAUSED, h.display.last.phase)
        assertEquals(0, h.speaker.count(h.p.askPool()))

        h.engine.onButton(Button.PLAY)
        advanceUntilIdle()
        assertEquals(1, h.speaker.count(h.p.askPlayers()))
        assertEquals(1, h.speaker.count(h.p.askPool()))
        assertEquals(1, h.store.settingsSaves.single().players)
        assertEquals(listOf(5), h.finalScores)
    }

    // ---- final review C1: a PLAY while a PAUSE is pending resumes without another button ----

    @Test fun pauseThenPlayDuringHeldAnnouncementRestartsTurnByItself() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.quickStart(2, 1)
        val turnText = h.p.turn(h.p.defaultPlayerName(1))
        h.speaker.holdOn = turnText
        h.right(SHAPE)
        h.right(RINGS)
        val job = launchRun(h)
        advanceUntilIdle()
        assertTrue(h.speaker.isHolding)

        h.engine.onButton(Button.PAUSE)
        h.engine.onButton(Button.PLAY)
        advanceUntilIdle()
        h.speaker.release()
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertEquals(2, h.speaker.count(turnText)) // the turn started again
        assertEquals(listOf(SHAPE, RINGS), h.clips.played)
        assertTrue(h.phases.none { it == Phase.PAUSED })
        assertEquals(listOf(5, 5), h.finalScores)
    }

    @Test fun pauseThenPlayDuringHeldListenReplaysSameSongByItself() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.quickStart(1, 1)
        val gate = h.listener.hold()
        h.right(SHAPE)
        val job = launchRun(h)
        advanceUntilIdle()
        assertEquals(Phase.LISTENING, h.display.last.phase)

        h.engine.onButton(Button.PAUSE)
        h.engine.onButton(Button.PLAY)
        advanceUntilIdle()
        gate.complete(Heard.Speech(listOf(RIGHT.getValue(SHAPE)), null)) // discarded
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertEquals(listOf(SHAPE, SHAPE), h.clips.played)
        assertEquals(1, h.display.views.count { it.phase == Phase.REVEAL })
        assertTrue(h.phases.none { it == Phase.PAUSED })
        assertEquals(listOf(5), h.finalScores)
    }

    @Test fun pausePlayPauseInsideOneUtteranceEndsPaused() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.quickStart(2, 1)
        val turnText = h.p.turn(h.p.defaultPlayerName(1))
        h.speaker.holdOn = turnText
        h.right(SHAPE)
        h.right(RINGS)
        val job = launchRun(h)
        advanceUntilIdle()

        h.engine.onButton(Button.PAUSE)
        h.engine.onButton(Button.PLAY)
        h.engine.onButton(Button.PAUSE)
        advanceUntilIdle()
        h.speaker.release()
        advanceUntilIdle()
        assertEquals(Phase.PAUSED, h.display.last.phase)
        assertFalse(job.isCompleted)
        assertTrue(h.clips.played.isEmpty())

        h.engine.onButton(Button.PLAY)
        advanceUntilIdle()
        assertTrue(job.isCompleted)
        assertEquals(2, h.speaker.count(turnText))
        assertEquals(listOf(5, 5), h.finalScores)
    }

    @Test fun pauseThenPlayInsideSetupListenAsksNextQuestionByItself() = runTest {
        val h = Harness(listOf(SHAPE))
        val gate = h.listener.hold()
        listOf("mixed", "all", "all", "1").forEach { h.listener.speech(it) }
        h.right(SHAPE)
        val job = launchRun(h)
        advanceUntilIdle()

        h.engine.onButton(Button.PAUSE)
        h.engine.onButton(Button.PLAY)
        gate.complete(Heard.Speech(listOf("1"), null))
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertEquals(1, h.speaker.count(h.p.askPlayers()))
        assertEquals(1, h.speaker.count(h.p.askPool()))
        assertTrue(h.phases.none { it == Phase.PAUSED })
        assertEquals(listOf(5), h.finalScores)
    }

    @Test fun pauseThenPlayDuringSecondPassOfUnclearListenReplaysByItself() = runTest {
        val h = Harness(listOf(HOUND))
        h.quickStart(1, 1)
        h.listener.unclear()
        h.listener.relistenGate = CompletableDeferred()
        h.listener.relistenResults.add(listOf("the answer is hound dog by elvis presley 1956")) // discarded by the pause
        h.answer("hound dog elvis presley 1956")
        val job = launchRun(h)
        advanceUntilIdle()
        assertTrue(h.listener.isRelistening)

        h.engine.onButton(Button.PAUSE)
        h.engine.onButton(Button.PLAY)
        advanceUntilIdle()
        h.listener.relistenGate!!.complete(Unit)
        advanceUntilIdle()

        assertTrue(job.isCompleted)
        assertEquals(listOf(HOUND, HOUND), h.clips.played)
        assertEquals(1, h.display.views.count { it.phase == Phase.REVEAL })
        assertTrue(h.phases.none { it == Phase.PAUSED })
        assertEquals(listOf(5), h.finalScores)
    }

    @Test fun lostConnectionAfterPausePlayStillWaitsForPlay() = runTest {
        val h = Harness(listOf(SHAPE))
        h.listener.failed(2)
        val gate = h.listener.hold()
        listOf("1", "mixed", "all", "all", "1").forEach { h.listener.speech(it) }
        h.right(SHAPE)
        val job = launchRun(h)
        advanceUntilIdle()

        h.engine.onButton(Button.PAUSE)
        h.engine.onButton(Button.PLAY)
        gate.complete(Heard.Failed("offline")) // third failure: the connection pause needs its own PLAY
        advanceUntilIdle()
        assertEquals(1, h.speaker.count(h.p.noConnection()))
        assertEquals(Phase.PAUSED, h.display.last.phase)
        assertFalse(job.isCompleted)

        h.engine.onButton(Button.PLAY)
        advanceUntilIdle()
        assertEquals(listOf(5), h.finalScores)
    }

    // ---- Answer phrase: a command word is a command; with the phrase it is an answer ----

    @Test fun commandWordAloneIsTheCommandEvenOnASongOfThatName() = runTest {
        val h = Harness(listOf("shmulik-kraus-shuv"))
        h.quickStart(1, 1)
        h.listener.speech("שוב")
        h.answer("שוב")
        h.engine.run()

        assertEquals(listOf("shmulik-kraus-shuv", "shmulik-kraus-shuv"), h.clips.played) // replayed
        assertTrue(h.speaker.texts.contains(h.p.verdict(FieldMatches(title = true, artist = false, year = false), 1)))
        assertEquals(listOf(1), h.finalScores)
    }

    @Test fun commandWordAfterThePhraseIsAnAnswer() = runTest {
        val h = Harness(listOf("shmulik-kraus-shuv"))
        h.quickStart(1, 1)
        h.listener.speech("התשובה היא שוב")
        h.engine.run()

        assertEquals(listOf("shmulik-kraus-shuv"), h.clips.played) // no replay
        assertEquals(listOf(1), h.finalScores)
    }

    @Test fun commandWordOnAnotherSongIsStillTheCommand() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.listener.speech("שוב")
        h.right(SHAPE)
        h.engine.run()

        assertEquals(listOf(SHAPE, SHAPE), h.clips.played)
        assertEquals(listOf(5), h.finalScores)
    }

    @Test fun dontKnowAsTheAnswerOfASongOfThatNameScoresTheTitle() = runTest { // Ruling R37
        val h = Harness(emptyList(), songList = listOf(loYodea))
        h.quickStart(1, 1)
        h.listener.speech("לא יודע") // talk
        h.listener.speech("התשובה היא לא יודע")
        h.engine.run()

        assertEquals(listOf(1), h.finalScores)
    }

    @Test fun dontKnowAsTheAnswerPasses() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.listener.speech("אין לי מושג") // talk
        h.listener.speech("התשובה היא לא יודע")
        h.engine.run()

        assertTrue(h.speaker.texts.contains(h.p.verdict(NONE, 0)))
        assertEquals(listOf(0), h.finalScores)
    }

    @Test fun stopGameOnTheSongStopIsStillTheStopCommand() = runTest {
        val h = Harness(listOf("spice-girls-stop"))
        h.quickStart(1, 1)
        h.listener.speech("stop game")
        h.listener.speech("yes")
        h.engine.run()

        assertEquals(1, h.speaker.count(h.p.confirmStop()))
        assertTrue(h.display.views.none { it.phase == Phase.REVEAL })
        assertEquals(listOf(0), h.finalScores)
    }

    @Test fun oneMoreTimeOnTheSongOneIsStillTheReplayCommand() = runTest {
        val h = Harness(emptyList(), songList = listOf(u2One))
        h.quickStart(1, 1)
        h.listener.speech("one more time")
        h.answer("one u2 1991")
        h.engine.run()

        assertEquals(listOf(u2One.id, u2One.id), h.clips.played)
        assertEquals(listOf(5), h.finalScores)
    }

    @Test fun oneMoreTimeAfterThePhraseIsAnAnswer() = runTest {
        val h = Harness(emptyList(), songList = listOf(oneMoreTime))
        h.quickStart(1, 1)
        h.answer("one more time")
        h.engine.run()

        assertEquals(listOf(oneMoreTime.id), h.clips.played)
        assertEquals(listOf(1), h.finalScores)
    }

    @Test fun nextAfterThePhraseIsAnAnswer() = runTest {
        val h = Harness(emptyList(), songList = listOf(tooClose))
        h.quickStart(1, 1)
        h.answer("next")
        h.engine.run()

        assertTrue(h.speaker.texts.contains(h.p.verdict(FieldMatches(title = false, artist = true, year = false), 1)))
        assertEquals(listOf(1), h.finalScores)
    }

    // ---- Answer phrase: where it comes and what is judged ----

    @Test fun onlyTheWordsAfterThePhraseAreJudged() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.listener.speech("maybe ed sheeran no wait the answer is seven rings 2017")
        h.engine.run()

        assertEquals(listOf(1), h.finalScores) // year only: "ed sheeran" came before the phrase (it would make 2)
    }

    @Test fun phraseAloneMakesTheNextSentenceTheAnswer() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.listener.speech("התשובה היא")
        h.listener.speech(RIGHT.getValue(SHAPE))
        h.engine.run()

        assertEquals(listOf(5), h.finalScores)
    }

    @Test fun phraseAloneThenSilenceGoesBackToTalking() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.listener.speech("the answer is")
        h.listener.silence()
        h.listener.speech("seven rings") // talk again
        h.right(SHAPE)
        h.engine.run()

        assertEquals(listOf(5), h.finalScores)
    }

    @Test fun phraseAloneThenACommandRunsTheCommand() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.listener.speech("the answer is")
        h.listener.speech("again")
        h.listener.speech("seven rings") // talk: the replay ended the waiting answer
        h.right(SHAPE)
        h.engine.run()

        assertEquals(listOf(SHAPE, SHAPE), h.clips.played)
        assertEquals(listOf(5), h.finalScores)
    }

    @Test fun secondPassWithoutThePhraseJudgesOnlyItsLastWords() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        // First pass: the guess before the phrase is "ed sheeran"; the answer is the title only.
        h.listener.speech("אולי אד שירן התשובה היא שייפ אוף יו", audio = true)
        // Second pass (English) cannot read the Hebrew phrase.
        h.listener.relistenResults.add(listOf("only ed sheeran hat shuva he shape of you"))
        h.engine.run()

        assertEquals(listOf(1), h.finalScores) // title; the artist named before the phrase does not count
    }

    @Test fun secondPassWithThePhraseJudgesWhatFollowsIt() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.answer("shape of you", audio = true)
        h.listener.relistenResults.add(listOf("התשובה היא שייפ אוף יו אד שירן 2017"))
        h.engine.run()

        assertEquals(listOf(5), h.finalScores)
    }

    @Test fun theRuleIsSaidOnceWhenAGameStartsOrResumes() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.quickStart(2, 1)
        h.right(SHAPE)
        h.right(RINGS)
        h.engine.run()

        val texts = h.speaker.texts
        assertEquals(1, h.speaker.count(h.p.answerRule()))
        assertTrue(texts.indexOf(h.p.answerRule()) < texts.indexOf(h.p.turn(h.names(2)[0])))

        val resumed = Harness(listOf(SHAPE, RINGS), game = savedGame)
        resumed.listener.speech("continue")
        resumed.right(RINGS)
        resumed.engine.run()
        assertEquals(1, resumed.speaker.count(resumed.p.answerRule()))
    }

    @Test fun pausePlayDuringTheClipLoadConnectionPauseStillWaitsForPlay() = runTest {
        val h = Harness(listOf(SHAPE, RINGS, HELP))
        h.quickStart(1, 1)
        h.clips.failNext = 3
        h.speaker.holdOn = h.p.noConnection()
        h.right(HELP)
        val job = launchRun(h)
        advanceUntilIdle()
        assertTrue(h.speaker.isHolding)

        h.engine.onButton(Button.PAUSE)
        h.engine.onButton(Button.PLAY)
        advanceUntilIdle()
        h.speaker.release()
        advanceUntilIdle()
        assertEquals(Phase.PAUSED, h.display.last.phase) // the connection pause needs its own PLAY
        assertEquals(listOf(SHAPE, RINGS, HELP), h.clips.played)
        assertFalse(job.isCompleted)

        h.engine.onButton(Button.PLAY)
        advanceUntilIdle()
        assertEquals(listOf(SHAPE, RINGS, HELP, HELP), h.clips.played)
        assertEquals(listOf(5), h.finalScores)
    }

    // ---- ruling 5 ----

    @Test fun nobodyAnswersContinuePrompt() = runTest {
        val h = Harness(listOf(SHAPE, RINGS), game = savedGame)
        h.listener.silence(3)
        h.engine.run()

        assertEquals(Phase.IDLE, h.display.last.phase)
        assertEquals(savedGame, h.store.game)
        assertTrue(h.store.gameSaves.isEmpty())
        assertTrue(h.store.settingsSaves.isEmpty())
        assertEquals(3, h.speaker.count(h.p.askContinue()))
    }

    @Test fun nobodyAnswersSetupQuestion() = runTest {
        val h = Harness(listOf(SHAPE))
        h.listener.silence(3)
        h.engine.run()

        assertEquals(3, h.speaker.count(h.p.askPlayers()))
        assertEquals(Phase.IDLE, h.display.last.phase)
        assertTrue(h.store.settingsSaves.isEmpty())
        assertTrue(h.clips.played.isEmpty())
    }

    @Test fun unparseableSetupAnswersRepeatWithoutLimit() = runTest {
        val h = Harness(listOf(SHAPE))
        listOf("banana", "9", "0", "twelve", "1").forEach { h.listener.speech(it) }
        listOf("mixed", "all", "all").forEach { h.listener.speech(it) }
        h.listener.speech("21") // rounds out of range
        h.listener.silence(2)
        h.listener.speech("1")
        h.right(SHAPE)
        h.engine.run()

        assertEquals(5, h.speaker.count(h.p.askPlayers()))
        assertEquals(5, h.speaker.count(h.p.didNotUnderstand()))
        assertEquals(4, h.speaker.count(h.p.askRounds()))
        assertEquals(GameSettings(players = 1, rounds = 1), h.store.settingsSaves.single())
        assertEquals(listOf(5), h.finalScores)
    }

    @Test fun threeFailuresDuringSetupPause() = runTest {
        val h = Harness(listOf(SHAPE))
        h.listener.failed(3)
        listOf("1", "mixed", "all", "all", "1").forEach { h.listener.speech(it) }
        h.right(SHAPE)
        launchRun(h)
        advanceUntilIdle()
        assertEquals(1, h.speaker.count(h.p.noConnection()))
        assertEquals(2, h.speaker.count(h.p.didNotUnderstand()))
        assertEquals(Phase.PAUSED, h.display.last.phase)
        assertEquals(3, h.speaker.count(h.p.askPlayers()))

        h.engine.onButton(Button.PLAY)
        advanceUntilIdle()
        assertEquals(4, h.speaker.count(h.p.askPlayers()))
        assertEquals(listOf(5), h.finalScores)
    }

    // ---- ruling 6 ----

    @Test fun stopNotConfirmedBySilenceOrFailure() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.listener.speech("stop game")
        h.listener.silence()
        h.listener.speech("stop game")
        h.listener.failed()
        h.listener.speech("stop game")
        h.listener.speech("banana")
        h.right(SHAPE)
        h.engine.run()

        assertEquals(3, h.speaker.count(h.p.confirmStop()))
        assertEquals(listOf(SHAPE), h.clips.played)
        assertEquals(listOf(5), h.finalScores)
    }

    // ---- ruling 7 ----

    @Test fun passScoresZeroRevealsAndMovesOn() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.quickStart(2, 1)
        h.listener.speech("pass", audio = true)
        h.right(RINGS)
        h.engine.run()

        assertTrue(h.listener.relistens.isEmpty()) // commands come from the first pass only
        val texts = h.speaker.texts
        assertTrue(texts.contains(h.p.verdict(NONE, 0)))
        assertTrue(texts.contains(h.p.reveal(seedSong(SHAPE)).first().text))
        assertEquals(listOf(0, 5), h.finalScores)
    }

    @Test fun verdictThenRevealThenRanking() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.right(SHAPE)
        h.engine.run()

        val song = seedSong(SHAPE)
        val expected = listOf(
            h.p.verdict(FieldMatches(true, true, true), 5),
            *h.p.reveal(song).map { it.text }.toTypedArray(),
            h.p.ranking(h.names(1), listOf(5)),
        )
        assertEquals(expected, h.speaker.texts.takeLast(expected.size))
        assertEquals(h.p.reveal(song), h.speaker.said[h.speaker.said.size - 2])
    }

    // ---- shorter speech ----

    @Test fun onePlayerHearsNoTurnAnnouncement() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.right(SHAPE)
        h.engine.run()

        assertEquals(listOf(h.p.startOrChange(), h.p.answerRule()), h.speaker.texts.take(2))
        assertEquals(0, h.speaker.count(h.p.turn(h.p.defaultPlayerName(1))))
        assertTrue(h.display.views.any { it.phase == Phase.CLIP }) // the screen still shows the turn
    }

    @Test fun noRunningTotalAfterATurn() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.quickStart(2, 1)
        h.right(SHAPE)
        h.right(RINGS)
        h.engine.run()

        val texts = h.speaker.texts
        val revealEnd = texts.indexOf(h.p.reveal(seedSong(SHAPE)).last().text)
        assertEquals(h.p.turn(h.names(2)[1]), texts[revealEnd + 1]) // straight to the next turn
    }

    // ---- saves ----

    @Test fun savesAtTurnStartAndAfterScore() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.quickStart(2, 1)
        h.right(SHAPE)
        h.answer("seven rings")
        h.engine.run()

        val s = GameSettings(players = 2, rounds = 1)
        assertEquals(
            listOf(
                SavedGame(s, listOf(0, 0), 1, 0, SHAPE),
                SavedGame(s, listOf(5, 0), 1, 1, null),
                SavedGame(s, listOf(5, 0), 1, 1, RINGS),
                SavedGame(s, listOf(5, 1), 2, 0, null),
                null,
            ),
            h.store.gameSaves,
        )
    }

    // ---- ruling 9 ----

    @Test fun runningOutOfSongsEndsWithRanking() = runTest {
        val h = Harness(listOf(SHAPE, RINGS, HELP, LOSE))
        h.quickStart(2, 2)
        h.songs.nextAllowed = 3
        h.right(SHAPE)
        h.right(RINGS)
        h.right(HELP)
        h.engine.run()

        assertTrue(h.speaker.texts.contains(h.p.ranking(h.names(2), listOf(10, 5))))
        assertEquals(Phase.FINISHED, h.display.last.phase)
        assertNull(h.store.gameSaves.last())
    }

    // ---- ruling 3: buttons from other threads ----

    @Test fun buttonsFromAnotherThread() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.listener.failed(3)
        h.right(SHAPE)
        launchRun(h)
        advanceUntilIdle()
        assertEquals(Phase.PAUSED, h.display.last.phase)

        thread {
            h.engine.onButton(Button.NEXT) // no meaning while paused
            h.engine.onButton(Button.PLAY)
        }.join()
        advanceUntilIdle()
        assertEquals(listOf(5), h.finalScores)
    }

    // ---- PREVIOUS during the clip ----

    @Test fun previousDuringClipRestartsItOncePerTurn() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.clips.hold = true
        h.listener.speech("again")
        h.right(SHAPE)
        launchRun(h)
        advanceUntilIdle()
        assertTrue(h.clips.isPlaying)

        h.engine.onButton(Button.PREVIOUS)
        advanceUntilIdle()
        assertEquals(1, h.clips.stops)
        assertEquals(listOf(SHAPE, SHAPE), h.clips.played)
        assertTrue(h.clips.isPlaying)
        assertEquals(0, h.speaker.beeps)

        h.engine.onButton(Button.PREVIOUS) // allowance used: ignored, clip keeps playing
        advanceUntilIdle()
        assertEquals(1, h.clips.stops)
        assertEquals(listOf(SHAPE, SHAPE), h.clips.played)
        assertTrue(h.clips.isPlaying)
        val spokenBefore = h.speaker.said.size

        h.clips.release()
        advanceUntilIdle()
        assertEquals(spokenBefore, h.speaker.said.indexOfFirst { it.any { u -> u.text == h.p.againUsed() } })
        val e = h.log.events
        val lastPlay = e.lastIndexOf("play:$SHAPE")
        assertEquals(listOf("beep", "listen"), e.subList(lastPlay + 1, lastPlay + 3))
        assertEquals(listOf(SHAPE, SHAPE), h.clips.played)
        assertEquals(1, h.speaker.count(h.p.againUsed()))
        assertEquals(listOf(5), h.finalScores)
    }

    // ---- single run ----

    @Test fun secondConcurrentRunFailsFast() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.clips.hold = true
        h.right(SHAPE)
        val first = launchRun(h)
        advanceUntilIdle()
        assertTrue(h.clips.isPlaying)
        val before = h.log.events.size

        val error = runCatching { h.engine.run() }.exceptionOrNull()
        assertTrue("got $error", error is IllegalStateException)
        assertEquals(before, h.log.events.size)
        assertTrue(h.clips.isPlaying)

        h.clips.release()
        advanceUntilIdle()
        assertTrue(first.isCompleted && !first.isCancelled)
        assertEquals(listOf(5), h.finalScores)
    }

    @Test fun runAgainAfterAnExceptionEndedTheRun() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.quickStart(1, 1) // the script then runs out: the listener throws
        val error = runCatching { h.engine.run() }.exceptionOrNull()
        assertTrue("got $error", error is AssertionError)

        h.listener.speech("continue")
        h.right(SHAPE)
        h.engine.run()
        assertEquals(listOf(5), h.finalScores)
    }

    // ---- cancellation ----

    @Test fun cancelDuringClipStopsItAndGoesIdle() = runTest {
        val h = Harness(listOf(SHAPE))
        h.quickStart(1, 1)
        h.clips.hold = true
        h.right(SHAPE)
        val job = launchRun(h)
        advanceUntilIdle()
        assertTrue(h.clips.isPlaying)
        val before = h.log.events.size

        job.cancel()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertEquals(listOf("stop"), h.log.events.drop(before))
        assertEquals(Phase.IDLE, h.display.last.phase)
        assertFalse(h.clips.isPlaying)
    }

    @Test fun cancelWhilePausedThenRunAgain() = runTest {
        val h = Harness(listOf(SHAPE, RINGS))
        h.quickStart(1, 1)
        h.listener.failed(3)
        val job = launchRun(h)
        advanceUntilIdle()
        assertEquals(Phase.PAUSED, h.display.last.phase)
        h.engine.onButton(Button.PAUSE) // stale button, must not leak into the next run
        job.cancel()
        advanceUntilIdle()
        assertEquals(Phase.IDLE, h.display.last.phase)

        // the saved game from the cancelled run is offered again
        h.listener.speech("continue")
        h.right(SHAPE)
        h.engine.run()
        assertEquals(listOf(5), h.finalScores)
    }
}
