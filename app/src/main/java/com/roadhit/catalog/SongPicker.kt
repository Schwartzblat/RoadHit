package com.roadhit.catalog

import com.roadhit.game.GameSettings
import com.roadhit.game.Origin
import com.roadhit.game.PlayedIds
import com.roadhit.game.Pool
import com.roadhit.game.Song
import com.roadhit.game.SongSource
import kotlin.random.Random

/**
 * Picks random songs from the catalog, never repeating one until the filtered pool is used up.
 *
 * [nextOverride] is a test seam: a catalog id it returns is picked next, whatever the settings
 * (an unknown id or null falls through to the random pick). The app passes one that returns null
 * unless a debug-only hook has queued songs.
 */
class SongPicker(
    private val catalog: Catalog,
    private val played: PlayedIds,
    private val random: Random,
    private val nextOverride: () -> String? = { null },
) : SongSource {

    override fun count(s: GameSettings): Int = catalog.filter(s).size

    override fun byId(id: String): Song? = catalog.byId(id)

    override fun next(s: GameSettings): Song? {
        nextOverride()?.let(catalog::byId)?.let { forced ->
            played.add(forced.id)
            return forced
        }
        val filtered = catalog.filter(s)
        if (filtered.isEmpty()) return null
        val done = played.all()
        var unplayed = filtered.filter { it.id !in done }
        if (unplayed.isEmpty()) {
            played.clear(filtered.map { it.id }.toSet())
            unplayed = filtered
        }
        val pick = choose(unplayed, s.pool)
        played.add(pick.id)
        return pick
    }

    private fun choose(unplayed: List<Song>, pool: Pool): Song {
        if (pool != Pool.MIXED) return unplayed.random(random)
        val first = if (random.nextBoolean()) Origin.IL else Origin.INTL
        val candidates = unplayed.filter { it.origin == first }
            .ifEmpty { unplayed.filter { it.origin != first } }
        return candidates.random(random)
    }
}
