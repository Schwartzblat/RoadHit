package com.roadhit.catalog

import com.roadhit.game.Difficulty
import com.roadhit.game.GameSettings
import com.roadhit.game.Origin
import com.roadhit.game.Pool
import com.roadhit.game.Song
import com.roadhit.game.years
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class Catalog(val songs: List<Song>) {
    private val songsById = songs.associateBy { it.id }

    /**
     * Each song's popularity third within its origin and decade: 0 for the best-known third, 1 for
     * the next, 2 for the least known. Popularity is not comparable across them (an Israeli 1960s hit
     * ranks far below a recent international one), so every group is ranked on its own.
     */
    private val popularityThird: Map<String, Int> = buildMap {
        for (group in songs.groupBy { it.origin to it.year / 10 }.values) {
            val byRank = group.sortedWith(compareByDescending<Song> { it.rank }.thenBy { it.id })
            byRank.forEachIndexed { i, song -> put(song.id, i * 3 / byRank.size) }
        }
    }

    /**
     * Songs matching the pool, the years (whole decades, or the exact range when one is set), the
     * genre and the difficulty.
     */
    fun filter(s: GameSettings): List<Song> {
        val years = s.years
        val lastThird = when (s.difficulty) {
            Difficulty.EASY -> 0
            Difficulty.MEDIUM -> 1
            Difficulty.HARD -> 2
        }
        return songs.filter { song ->
            song.year in years &&
                inPool(song.origin, s.pool) &&
                (s.genre == null || song.genre == s.genre) &&
                popularityThird.getValue(song.id) <= lastThird
        }
    }

    fun byId(id: String): Song? = songsById[id]

    private fun inPool(origin: Origin, pool: Pool): Boolean = when (pool) {
        Pool.ISRAELI -> origin == Origin.IL
        Pool.INTERNATIONAL -> origin == Origin.INTL
        Pool.MIXED -> true
    }

    @Serializable
    private data class CatalogFile(val version: Int, val songs: List<Song>)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses a catalog file: `{ "version": 1, "songs": [ ... ] }`. */
        fun parse(json: String): Catalog = Catalog(this.json.decodeFromString<CatalogFile>(json).songs)
    }
}
