package com.roadhit.game

import java.util.concurrent.atomic.AtomicBoolean

private const val TURN_LISTEN_SECONDS = 12
private const val CONFIRM_LISTEN_SECONDS = 8
private const val LOAD_FAILURES_BEFORE_OFFLINE = 3

/** Extra words kept from the end of a second pass that has no readable answer phrase. */
private const val SECOND_PASS_SLACK = 1

/**
 * The whole voice game: resume or set up, every turn, then the ranking. Talks to the world only
 * through the ports, so all waiting happens inside port calls.
 *
 * Flow: [run] → saved game? ask continue/new → [SetupDialogue] → for each round and player a
 * [Turn] → ranking. A turn is attempted until it ends: announce → clip → (beep → listen)* →
 * verdict, reveal, total. A pause (button or lost connection) ends the attempt; after PLAY the
 * same turn starts again with the same song.
 */
class GameEngine(
    private val clips: ClipPlayer,
    speaker: Speaker,
    private val listener: Listener,
    display: Display,
    private val store: GameStore,
    private val songs: SongSource,
    private val judge: AnswerJudge,
    private val prefs: () -> Prefs,
) {
    private val io = Conversation(speaker, listener, display, prefs)
    private val setup = SetupDialogue(io, store, songs)
    private var loadFailuresInRow = 0
    private val running = AtomicBoolean(false)

    /** Safe to call from any thread; never blocks. Buttons with no meaning at the moment are dropped. */
    fun onButton(b: Button) = io.press(b)

    /**
     * Resume-or-setup, all turns, ranking. Cancellable at any point; then shows [Phase.IDLE].
     * Throws [IllegalStateException] if another run() of this engine is still in progress.
     */
    suspend fun run() {
        check(running.compareAndSet(false, true)) { "GameEngine.run() is already running" }
        try {
            io.reset()
            loadFailuresInRow = 0
            var ended = false
            try {
                val game = start()
                if (game == null) io.show(idleView()) else play(game)
                ended = true
            } finally {
                if (!ended) io.show(idleView())
            }
        } finally {
            running.set(false)
        }
    }

    private class Game(
        val settings: GameSettings,
        val scores: MutableList<Int>,
        var round: Int,
        var player: Int,
        /** A song to play first, from a saved game. */
        var resumeSongId: String?,
    ) {
        fun saved(songId: String?) = SavedGame(settings, scores.toList(), round, player, songId)

        /** Where the game goes on after the current turn, saved with no song. */
        fun savedAfterTurn(): SavedGame {
            val last = player == settings.players - 1
            return SavedGame(settings, scores.toList(), if (last) round + 1 else round, if (last) 0 else player + 1, null)
        }
    }

    private suspend fun start(): Game? {
        val saved = store.loadGame()
        if (saved != null) {
            when (setup.askContinue(saved) ?: return null) {
                Choice.FIRST -> return Game(
                    saved.settings,
                    MutableList(saved.settings.players) { saved.scores.getOrElse(it) { 0 } },
                    saved.round,
                    saved.playerIndex,
                    saved.currentSongId,
                )
                Choice.SECOND -> store.saveGame(null)
            }
        }
        val settings = setup.run() ?: return null
        return Game(settings, MutableList(settings.players) { 0 }, 1, 0, null)
    }

    private suspend fun play(game: Game) {
        io.say { it.answerRule() }
        while (game.round <= game.settings.rounds) {
            val song = game.resumeSongId?.let(songs::byId) ?: songs.next(game.settings) ?: break
            game.resumeSongId = null
            store.saveGame(game.saved(song.id))
            if (Turn(game, song).play() == TurnEnd.GAME_OVER) break
            if (++game.player == game.settings.players) {
                game.player = 0
                game.round++
            }
        }
        if (io.pausePending) io.waitForPlay()
        store.saveGame(null)
        io.say { it.ranking(names(game), game.scores) }
        io.show(view(game, Phase.FINISHED))
    }

    private fun name(n: Int): String =
        prefs().playerNames.getOrNull(n)?.takeIf { it.isNotBlank() } ?: io.phrases.defaultPlayerName(n + 1)

    private fun names(game: Game) = (0 until game.settings.players).map(::name)

    private fun view(game: Game, phase: Phase, revealed: Song? = null) = GameView(
        phase, names(game), game.scores.toList(), game.player, game.round, game.settings.rounds, revealed,
    )

    private enum class TurnEnd { NEXT, GAME_OVER }

    /** How one attempt at a turn ended. */
    private enum class Attempt { PAUSED, SCORED, STOPPED, NO_SONGS }

    private inner class Turn(private val game: Game, private var song: Song) {
        private var replayUsed = false

        suspend fun play(): TurnEnd {
            while (true) {
                when (attempt()) {
                    Attempt.PAUSED -> io.waitForPlay()
                    Attempt.SCORED -> return TurnEnd.NEXT
                    Attempt.STOPPED, Attempt.NO_SONGS -> return TurnEnd.GAME_OVER
                }
            }
        }

        private suspend fun attempt(): Attempt {
            if (io.pausePending) return Attempt.PAUSED
            io.show(view(game, Phase.CLIP))
            io.say { it.turn(name(game.player)) }
            if (io.pausePending) return Attempt.PAUSED
            while (true) {
                val loaded = playClip()
                if (io.pausePending) return Attempt.PAUSED
                if (loaded) break
                if (++loadFailuresInRow >= LOAD_FAILURES_BEFORE_OFFLINE) {
                    loadFailuresInRow = 0
                    io.sayNoConnection()
                    return Attempt.PAUSED
                }
                song = songs.next(game.settings) ?: return Attempt.NO_SONGS
                store.saveGame(game.saved(song.id))
            }
            loadFailuresInRow = 0
            return answer()
        }

        /**
         * Beeps and listens, sentence after sentence, with no time limit, until the turn is decided.
         * Players talk freely: only a sentence with the answer phrase ("התשובה היא…") is judged, by
         * the words after the phrase; the phrase alone makes the next sentence the answer. Other
         * sentences and silence are ignored without a word, except voice commands. The beep comes
         * again only after the game has spoken or played.
         */
        private suspend fun answer(): Attempt {
            var cue = true
            var phraseSaid = false
            while (true) {
                if (cue) {
                    io.show(view(game, Phase.LISTENING))
                    io.beep()
                    if (io.pausePending) return Attempt.PAUSED
                    cue = false
                }
                var replay = false
                val heard = io.listen(TURN_LISTEN_SECONDS) { if (it == Button.PREVIOUS) replay = true }
                if (io.pausePending) return Attempt.PAUSED
                if (replay) {
                    replayClip()
                    phraseSaid = false
                    cue = true
                    continue
                }
                when (heard) {
                    Heard.Silence, is Heard.Unclear -> { // Unclear never gets here (Conversation.listen)
                        io.heardSomething()
                        phraseSaid = false
                    }
                    is Heard.Failed -> {
                        if (io.listenFailed()) return Attempt.PAUSED
                        cue = true
                    }
                    is Heard.Speech -> {
                        io.heardSomething()
                        when (val cut = cutAnswer(heard.transcripts)) {
                            is AnswerCut.After -> return settle(decide(heard, cut.transcripts, cut.wordsBefore))
                            AnswerCut.PhraseOnly -> phraseSaid = true
                            AnswerCut.None -> {
                                val command = parseCommand(heard.transcripts)
                                if (command == null && phraseSaid) return settle(decide(heard, heard.transcripts, 0))
                                phraseSaid = false
                                when (command) {
                                    VoiceCommand.AGAIN -> replayClip()
                                    VoiceCommand.PASS -> return settle(NOTHING_RIGHT)
                                    VoiceCommand.SCORE -> io.say { it.scores(names(game), game.scores) }
                                    VoiceCommand.STOP_GAME -> if (stopConfirmed()) return Attempt.STOPPED
                                    null -> Unit // the players are talking among themselves
                                }
                                if (command != null) cue = true
                            }
                        }
                    }
                }
                if (io.pausePending) return Attempt.PAUSED
            }
        }

        /**
         * An answer: "I don't know" passes, unless it is this song's whole name (Ruling R37, e.g.
         * the song "לא יודע"); anything else is judged.
         */
        private suspend fun decide(heard: Heard.Speech, answer: List<String>, wordsBefore: Int): FieldMatches {
            if (isDontKnow(answer) && !judge.isWholeName(song, answer.first())) return NOTHING_RIGHT
            return judgeAnswer(answer, heard.audio, wordsBefore)
        }

        /**
         * Plays the clip; NEXT or PAUSE stops it, PREVIOUS restarts it once (using the turn's replay;
         * ignored when that is used up). Returns false when it could not be loaded.
         */
        private suspend fun playClip(): Boolean {
            var returned = false
            try {
                while (true) {
                    var restart = false
                    val loaded = io.whileWatching({ clips.play(song, prefs().clipSeconds) }) {
                        when (it) {
                            Button.NEXT, Button.PAUSE -> clips.stop()
                            Button.PREVIOUS -> if (!replayUsed) {
                                replayUsed = true
                                restart = true
                                clips.stop()
                            }
                            Button.PLAY -> Unit
                        }
                    }
                    if (restart && !io.pausePending) continue
                    returned = true
                    return loaded
                }
            } finally {
                if (!returned) clips.stop()
            }
        }

        /** The once-per-turn replay (AGAIN or PREVIOUS); a pause does not give it back. */
        private suspend fun replayClip() {
            if (replayUsed) {
                io.say { it.againUsed() }
                return
            }
            replayUsed = true
            io.show(view(game, Phase.CLIP))
            playClip()
        }

        private suspend fun stopConfirmed(): Boolean {
            io.say { it.confirmStop() }
            val heard = io.listen(CONFIRM_LISTEN_SECONDS)
            val p = io.phrases
            return heard is Heard.Speech && parseChoice(heard.transcripts, p.yesWords, p.noWords) == Choice.FIRST
        }

        /**
         * Judges the first pass ([first]: the answer's words), then, when something is missing and
         * there is audio, both passes. The second pass hears the whole sentence again: its words
         * after the answer phrase count; when it has no phrase it can read (the phrase in the
         * other language) and the phrase came mid-sentence, only as many words from its end as
         * the answer had, plus [SECOND_PASS_SLACK], so the guesses before the phrase are not judged.
         */
        private suspend fun judgeAnswer(first: List<String>, audio: RecordedAudio?, wordsBefore: Int): FieldMatches {
            val tolerance = prefs().yearTolerance
            val m = judge.judge(song, first, tolerance)
            if (audio == null || (m.title && m.artist && heardYear(first) != null)) return m
            val other = if (prefs().lang == Lang.HE) Lang.EN else Lang.HE
            val heardAgain = io.whileWatching({ listener.relisten(audio, other) })
            val second = when (val cut = cutAnswer(heardAgain)) {
                is AnswerCut.After -> cut.transcripts
                AnswerCut.PhraseOnly -> emptyList()
                AnswerCut.None -> if (wordsBefore == 0) heardAgain else {
                    val keep = first.first().split(' ').count { it.isNotBlank() } + SECOND_PASS_SLACK
                    heardAgain.map { t -> t.split(' ').filter { it.isNotBlank() }.takeLast(keep).joinToString(" ") }
                }
            }
            return judge.judge(song, first + second, tolerance)
        }

        /** Scores, saves, then verdict, reveal and total; a pause cuts the rest of that speech short. */
        private suspend fun settle(m: FieldMatches): Attempt {
            val points = score(m)
            game.scores[game.player] += points
            store.saveGame(game.savedAfterTurn())
            io.show(view(game, Phase.REVEAL, song))
            io.say { it.verdict(m, points) }
            if (!io.pausePending) io.sayParts(io.phrases.reveal(song))
            if (!io.pausePending) io.say { it.total(name(game.player), game.scores[game.player]) }
            return Attempt.SCORED
        }
    }
}

private val NOTHING_RIGHT = FieldMatches(title = false, artist = false, year = false)
