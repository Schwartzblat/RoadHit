package com.roadhit.catalog

import com.roadhit.game.GameSettings
import com.roadhit.game.Genre
import com.roadhit.game.Lang
import com.roadhit.game.Names
import com.roadhit.game.Origin
import com.roadhit.game.PlayedIds
import com.roadhit.game.Pool
import com.roadhit.game.Song
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SongPickerTest {
    private class FakePlayed(initial: Set<String> = emptySet()) : PlayedIds {
        private val ids = initial.toMutableSet()
        override fun all(): Set<String> = ids.toSet()
        override fun add(id: String) { ids.add(id) }
        override fun clear(ids: Set<String>) { this.ids.removeAll(ids) }
    }

    private fun song(index: Int, origin: Origin) = Song(
        id = "${origin.name}-$index",
        title = Names("שיר $index", "Song $index"),
        artist = Names("אמן", "Artist"),
        script = Lang.EN,
        year = 1990,
        origin = origin,
        genre = Genre.POP,
        deezerId = index.toLong(),
        rank = index,
    )

    private fun songs(il: Int, intl: Int) =
        (0 until il).map { song(it, Origin.IL) } + (0 until intl).map { song(it, Origin.INTL) }

    private val mixed = GameSettings(pool = Pool.MIXED)

    private fun picker(
        catalog: List<Song>,
        played: PlayedIds = FakePlayed(),
        seed: Int = 1,
    ) = SongPicker(Catalog(catalog), played, Random(seed))

    @Test
    fun noRepeatsAcrossFullPool() {
        val p = picker(songs(3, 3))
        val ids = (1..6).map { p.next(mixed)!!.id }
        assertEquals(6, ids.toSet().size)
    }

    @Test
    fun seventhCallClearsHistoryAndSucceeds() {
        val played = FakePlayed()
        val p = picker(songs(3, 3), played)
        repeat(6) { p.next(mixed) }
        assertNotNull(p.next(mixed))
        assertEquals(1, played.all().size)
    }

    @Test
    fun resetKeepsUnrelatedIds() {
        val played = FakePlayed(setOf("other"))
        val p = picker(songs(3, 3), played)
        repeat(7) { p.next(mixed) }
        assertTrue("other" in played.all())
        assertEquals(2, played.all().size)
    }

    @Test
    fun mixedIsRoughlyEvenBetweenOrigins() {
        // History is wiped after each draw: with no-repeat, the 50 IL songs would run out
        // long before 1,000 draws, so this isolates the per-pick origin coin flip.
        val played = FakePlayed()
        val p = picker(songs(50, 500), played, seed = 42)
        val il = (1..1000).count {
            val origin = p.next(mixed)!!.origin
            played.clear(played.all())
            origin == Origin.IL
        }
        assertTrue("IL share was $il/1000", il in 450..550)
    }

    @Test
    fun mixedWithOnlyIlSongsReturnsIl() {
        val p = picker(songs(4, 5))
        val s = mixed.copy(genre = Genre.POP, fromDecade = 1990, toDecade = 1990)
        val onlyIl = Catalog(songs(4, 0))
        val picker = SongPicker(onlyIl, FakePlayed(), Random(3))
        repeat(10) {
            val song = picker.next(s)
            assertNotNull(song)
            assertEquals(Origin.IL, song!!.origin)
        }
        assertNotNull(p.next(s))
    }

    @Test
    fun emptyFilterGivesZeroAndNull() {
        val p = picker(songs(3, 3))
        val s = mixed.copy(fromDecade = 1940, toDecade = 1940)
        assertEquals(0, p.count(s))
        assertNull(p.next(s))
    }

    @Test
    fun prePlayedIdsAreRespected() {
        val all = songs(0, 3)
        val played = FakePlayed(setOf("INTL-0", "INTL-1"))
        val p = picker(all, played)
        assertEquals("INTL-2", p.next(mixed)!!.id)
    }

    @Test
    fun israeliAndInternationalPoolsStayInPool() {
        val p = picker(songs(5, 5))
        repeat(5) { assertEquals(Origin.IL, p.next(GameSettings(pool = Pool.ISRAELI))!!.origin) }
        repeat(5) { assertEquals(Origin.INTL, p.next(GameSettings(pool = Pool.INTERNATIONAL))!!.origin) }
    }

    @Test
    fun byIdReturnsCatalogSong() {
        val all = songs(2, 2)
        val p = picker(all)
        assertSame(all[3], p.byId("INTL-1"))
        assertNull(p.byId("nope"))
    }

    @Test
    fun countIgnoresPlayedHistory() {
        val played = FakePlayed(setOf("IL-0", "IL-1"))
        val p = picker(songs(3, 3), played)
        assertEquals(6, p.count(mixed))
    }

    @Test
    fun nextReadsPlayedIdsAtMostTwiceOver500Songs() {
        var reads = 0
        val inner = FakePlayed()
        val counting = object : PlayedIds {
            override fun all(): Set<String> { reads++; return inner.all() }
            override fun add(id: String) = inner.add(id)
            override fun clear(ids: Set<String>) = inner.clear(ids)
        }
        val p = picker(songs(250, 250), counting)
        p.next(mixed)
        assertTrue("all() called $reads times", reads <= 2)
    }

    @Test
    fun overrideIdsComeFirstInOrderThenRandomPicksResume() {
        val played = FakePlayed()
        val queue = ArrayDeque(listOf("INTL-2", "IL-0"))
        val p = SongPicker(Catalog(songs(3, 3)), played, Random(1)) { queue.removeFirstOrNull() }
        assertEquals("INTL-2", p.next(mixed)!!.id)
        assertEquals("IL-0", p.next(mixed)!!.id)
        assertEquals(setOf("INTL-2", "IL-0"), played.all())
        val rest = (1..4).map { p.next(mixed)!!.id }
        assertEquals(6, (rest + listOf("INTL-2", "IL-0")).toSet().size) // forced picks count as played
    }

    @Test
    fun overrideIgnoresTheSettingsFilterAndUnknownIdsFallBackToRandom() {
        val ilOnly = GameSettings(pool = Pool.ISRAELI)
        val queue = ArrayDeque(listOf("INTL-1", "no-such-song"))
        val p = SongPicker(Catalog(songs(3, 3)), FakePlayed(), Random(1)) { queue.removeFirstOrNull() }
        assertEquals("INTL-1", p.next(ilOnly)!!.id)
        assertEquals(Origin.IL, p.next(ilOnly)!!.origin)
    }
}
