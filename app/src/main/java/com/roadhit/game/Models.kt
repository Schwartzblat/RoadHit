package com.roadhit.game

import kotlinx.serialization.Serializable

/** Where a song comes from. */
enum class Origin { IL, INTL }

/** Which songs a game draws from: Israeli, international, or both (MIXED). */
enum class Pool { ISRAELI, INTERNATIONAL, MIXED }

enum class Genre { POP, ROCK, HIPHOP, DANCE, RNB, MIZRAHI, OTHER }

enum class Lang { HE, EN }

/**
 * How well-known a game's songs are, each song ranked by popularity against the songs of its own
 * origin and decade: easy keeps the best-known third, medium two thirds, hard every song.
 * Chosen only on the settings screen; never asked or spoken.
 */
enum class Difficulty { EASY, MEDIUM, HARD }

/** A name in two scripts: [he] is always Hebrew-script, [en] always Latin-script. */
@Serializable
data class Names(val he: String, val en: String)

@Serializable
data class Song(
    val id: String,
    val title: Names,
    /** First-credited artist only; fuller credits go in [artistAliases]. */
    val artist: Names,
    val titleAliases: List<String> = emptyList(),
    val artistAliases: List<String> = emptyList(),
    /** Script of the original title. */
    val script: Lang,
    /** Original release year of the song. */
    val year: Int,
    val origin: Origin,
    val genre: Genre,
    val deezerId: Long,
    val rank: Int,
)

/**
 * Per-game choices. Limits (not enforced here): players 1..8, rounds 1..20.
 * Decades are the first year of the decade, e.g. 1980 covers 1980..1989. An exact range of years
 * ([fromYear]..[toYear], both set) is used instead of the decades; see [years].
 */
@Serializable
data class GameSettings(
    val players: Int = 2,
    val pool: Pool = Pool.MIXED,
    val fromDecade: Int = 1940,
    val toDecade: Int = 2020,
    /** null means every genre. */
    val genre: Genre? = null,
    val rounds: Int = 5,
    val fromYear: Int? = null,
    val toYear: Int? = null,
    val difficulty: Difficulty = Difficulty.HARD,
)

/** The years a game's songs come from: the exact range when one is set, else the whole decades. */
val GameSettings.years: IntRange
    get() = if (fromYear != null && toYear != null) fromYear..toYear else fromDecade..(toDecade + 9)

/**
 * Lasting preferences. Limits (not enforced here): yearTolerance 0..10, clipSeconds 10..30.
 */
@Serializable
data class Prefs(
    val yearTolerance: Int = 5,
    val clipSeconds: Int = 30,
    val lang: Lang = Lang.HE,
    val playerNames: List<String> = emptyList(),
    val autoStartOnOpen: Boolean = false,
)

/** Which fields of an answer were judged right. */
data class FieldMatches(val title: Boolean, val artist: Boolean, val year: Boolean)

/** A game in progress, saved so it can be continued later. */
@Serializable
data class SavedGame(
    val settings: GameSettings,
    val scores: List<Int>,
    val round: Int,
    val playerIndex: Int,
    val currentSongId: String?,
)
