package com.roadhit.game

private const val SETUP_LISTEN_SECONDS = 8
private const val SILENCES_BEFORE_GIVING_UP = 3
private val PLAYERS = 1..8
private val ROUNDS = 1..20

/**
 * The spoken questions before a game. Every function returns null when nobody answered
 * (three silences in a row on one question): the caller then ends quietly.
 */
internal class SetupDialogue(
    private val io: Conversation,
    private val store: GameStore,
    private val songs: SongSource,
) {
    /** "Continue the saved game, or a new one?" (the game's settings and round are not read out). */
    suspend fun askContinue(): Choice? =
        ask({ it.askContinue() }) { parseChoice(it, io.phrases.continueWords, io.phrases.newGameWords) }

    /**
     * Last time's settings ("start", without reading them out) or the full set of questions; saves
     * the result. "Let's start" is said only when something was asked.
     */
    suspend fun run(): GameSettings? {
        val last = store.loadSettings()
        var reuse: GameSettings? = null
        if (last != null) {
            val choice = ask({ it.startOrChange() }) {
                parseChoice(it, io.phrases.startWords, io.phrases.changeWords)
            } ?: return null
            if (choice == Choice.FIRST) reuse = last
        }
        var asked = reuse == null
        var s = reuse ?: askEverything(last?.difficulty ?: Difficulty.HARD) ?: return null
        while (true) {
            val have = songs.count(s)
            val need = s.players * s.rounds
            if (have >= need) break
            asked = true
            val widest = songs.count(widest(s))
            if (widest >= need) {
                io.say { it.tooFewSongs(have, need) }
            } else {
                // No filter can fill this game: ask for a smaller one first.
                io.say { it.tooFewSongs(widest, need) }
                s = askSize(s) ?: return null
            }
            s = askFilter(s) ?: return null
        }
        store.saveSettings(s)
        if (asked) io.say { it.letsStart() }
        return s
    }

    /** Every question; the [difficulty] is never asked (it is chosen on the settings screen). */
    private suspend fun askEverything(difficulty: Difficulty): GameSettings? {
        val players = askPlayers() ?: return null
        val filtered = askFilter(GameSettings(players = players, difficulty = difficulty)) ?: return null
        val rounds = askRounds() ?: return null
        return filtered.copy(rounds = rounds)
    }

    /** Players, then rounds, onto [base]. */
    private suspend fun askSize(base: GameSettings): GameSettings? {
        val players = askPlayers() ?: return null
        val rounds = askRounds() ?: return null
        return base.copy(players = players, rounds = rounds)
    }

    private suspend fun askPlayers() = ask({ it.askPlayers() }) { parseCount(it, PLAYERS) }
    private suspend fun askRounds() = ask({ it.askRounds() }) { parseCount(it, ROUNDS) }

    /** [s] with the widest filter: every pool, decade and genre, and no exact years (the difficulty stays). */
    private fun widest(s: GameSettings): GameSettings {
        val all = GameSettings()
        return s.copy(
            pool = all.pool, fromDecade = all.fromDecade, toDecade = all.toDecade, genre = all.genre,
            fromYear = null, toYear = null,
        )
    }

    /** Pool, decades (replacing any exact years) and genre, onto [base]. */
    private suspend fun askFilter(base: GameSettings): GameSettings? {
        val pool = ask({ it.askPool() }, ::parsePool) ?: return null
        val (from, to) = ask({ it.askDecades() }, ::parseDecades) ?: return null
        var genre: GenreAnswer
        while (true) {
            genre = ask({ it.askGenre() }, ::parseGenre) ?: return null
            if (genre != GenreAnswer.WhichOnes) break
            io.say { it.genreList() }
        }
        return base.copy(
            pool = pool, fromDecade = from, toDecade = to, genre = (genre as? GenreAnswer.One)?.genre,
            fromYear = null, toYear = null,
        )
    }

    /**
     * Asks [question] until [parse] accepts an answer. A pending PAUSE takes effect before the
     * question is (re-)asked. Unparseable speech gets didNotUnderstand, without a limit.
     */
    private suspend fun <T : Any> ask(question: (Phrases) -> String, parse: (List<String>) -> T?): T? {
        var silences = 0
        while (true) {
            if (io.pausePending) io.waitForPlay()
            io.show(idleView(Phase.SETUP))
            io.say(question)
            when (val heard = io.listen(SETUP_LISTEN_SECONDS)) {
                Heard.Silence, is Heard.Unclear -> { // Unclear never gets here (Conversation.listen)
                    io.heardSomething()
                    if (++silences >= SILENCES_BEFORE_GIVING_UP) return null
                }
                is Heard.Failed -> {
                    silences = 0
                    if (io.listenFailed()) io.waitForPlay()
                }
                is Heard.Speech -> {
                    silences = 0
                    io.heardSomething()
                    parse(heard.transcripts)?.let { return it }
                    io.say { it.didNotUnderstand() }
                }
            }
        }
    }
}
