package com.roadhit.catalog

import com.roadhit.game.GameSettings
import com.roadhit.game.Origin
import com.roadhit.game.Pool
import com.roadhit.game.Song
import com.roadhit.game.years
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class Catalog(val songs: List<Song>) {
    private val songsById = songs.associateBy { it.id }

    /** Songs matching the pool, the years (whole decades, or the exact range when one is set) and the genre. */
    fun filter(s: GameSettings): List<Song> {
        val years = s.years
        return songs.filter { song ->
            song.year in years &&
                inPool(song.origin, s.pool) &&
                (s.genre == null || song.genre == s.genre)
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
