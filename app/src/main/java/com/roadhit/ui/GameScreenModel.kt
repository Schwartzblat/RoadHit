package com.roadhit.ui

import com.roadhit.game.GameView
import com.roadhit.game.Lang
import com.roadhit.game.Phase

/** One line of the scoreboard. */
data class ScoreRow(val name: String, val score: Int, val current: Boolean, val winner: Boolean)

/** The judged song, as the reveal shows it: title in its original script, artist in the app language. */
data class RevealedSong(val title: String, val artist: String, val year: Int)

/** What the game screen shows, with its texts in the app language. */
sealed interface GameScreenModel {
    /** No game running: the Start button; after a game ([finished]) also the final scores. */
    data class Home(val finished: Boolean, val finalScores: List<ScoreRow>) : GameScreenModel

    /** A game is running. [revealed] is set only in [Phase.REVEAL]; [turn] and [round] are null until the engine knows them. */
    data class InGame(
        val turn: String?,
        val round: String?,
        val scoreboard: List<ScoreRow>,
        val status: String,
        val revealed: RevealedSong?,
    ) : GameScreenModel
}

/** Maps the engine's [view] to the screen. The song leaves [view] only in [Phase.REVEAL]. */
fun screenModel(view: GameView, lang: Lang): GameScreenModel {
    val s = uiStringsFor(lang)
    val scores = view.playerNames.indices.map { view.scores.getOrElse(it) { 0 } }
    return when (view.phase) {
        Phase.IDLE -> GameScreenModel.Home(finished = false, finalScores = emptyList())
        Phase.FINISHED -> {
            val best = scores.maxOrNull()
            GameScreenModel.Home(
                finished = true,
                finalScores = view.playerNames.mapIndexed { i, name -> ScoreRow(name, scores[i], current = false, winner = scores[i] == best) },
            )
        }
        Phase.SETUP, Phase.CLIP, Phase.LISTENING, Phase.REVEAL, Phase.PAUSED -> GameScreenModel.InGame(
            turn = view.playerNames.getOrNull(view.currentPlayer)?.let(s::turn),
            round = if (view.round > 0 && view.rounds > 0) s.round(view.round.coerceAtMost(view.rounds), view.rounds) else null,
            scoreboard = view.playerNames.mapIndexed { i, name -> ScoreRow(name, scores[i], current = i == view.currentPlayer, winner = false) },
            status = when (view.phase) {
                Phase.SETUP -> s.statusSetup
                Phase.CLIP -> s.statusClip
                Phase.LISTENING -> s.statusListening
                Phase.REVEAL -> s.statusReveal
                else -> s.statusPaused
            },
            revealed = if (view.phase == Phase.REVEAL) view.revealed?.let { song ->
                RevealedSong(
                    title = if (song.script == Lang.HE) song.title.he else song.title.en,
                    artist = if (lang == Lang.HE) song.artist.he else song.artist.en,
                    year = song.year,
                )
            } else null,
        )
    }
}

/** Whether a game is under way (the screen is kept on). */
fun isGameActive(phase: Phase): Boolean = phase != Phase.IDLE && phase != Phase.FINISHED

/** The most players a game has, and so the number of name fields. */
const val MAX_PLAYERS = 8

/**
 * [names] with player [index]'s name set to [name] (blank = the default name): earlier players
 * keep theirs (padded with empty names), trailing empty names are dropped, and indices from
 * [MAX_PLAYERS] on are ignored.
 */
fun withPlayerName(names: List<String>, index: Int, name: String): List<String> {
    if (index !in 0 until MAX_PLAYERS) return names
    val padded = names.take(MAX_PLAYERS).toMutableList()
    while (padded.size <= index) padded += ""
    padded[index] = name
    return padded.dropLastWhile { it.isBlank() }
}
