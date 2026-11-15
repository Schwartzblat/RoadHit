package com.roadhit.catalog

import com.roadhit.game.Difficulty
import com.roadhit.game.GameSettings
import com.roadhit.game.Genre
import com.roadhit.game.Lang
import com.roadhit.game.Names
import com.roadhit.game.Origin
import com.roadhit.game.Pool
import com.roadhit.game.Song
import java.io.File
import java.lang.Character.UnicodeScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    // Gradle runs JVM unit tests with the module directory (app/) as the working directory.
    private val bundled by lazy { Catalog.parse(File("src/main/assets/catalog.json").readText()) }

    private val seed by lazy {
        val json = javaClass.classLoader!!.getResource("seed-catalog.json")!!.readText()
        Catalog.parse(json)
    }

    @Test
    fun bundledCatalogParsesWithCompleteSongs() {
        assertTrue(bundled.songs.isNotEmpty())
        for (song in bundled.songs) {
            assertTrue("${song.id}: title.he", song.title.he.isNotBlank())
            assertTrue("${song.id}: title.en", song.title.en.isNotBlank())
            assertTrue("${song.id}: artist.he", song.artist.he.isNotBlank())
            assertTrue("${song.id}: artist.en", song.artist.en.isNotBlank())
            assertTrue("${song.id}: year ${song.year}", song.year in 1940..2026)
        }
    }

    @Test
    fun bundledCatalogHasUniqueIdsAndDeezerTracks() {
        val ids = bundled.songs.groupingBy { it.id }.eachCount().filterValues { it > 1 }
        assertEquals(emptyMap<String, Int>(), ids)
        for (song in bundled.songs) assertTrue("${song.id}: deezerId ${song.deezerId}", song.deezerId > 0)
        val tracks = bundled.songs.groupingBy { it.deezerId }.eachCount().filterValues { it > 1 }
        assertEquals(emptyMap<Long, Int>(), tracks)
    }

    @Test
    fun bundledCatalogKeepsEachSpellingInItsScript() {
        for (song in bundled.songs) {
            assertFalse("${song.id}: title.he ${song.title.he}", song.title.he.hasScript(UnicodeScript.LATIN))
            assertFalse("${song.id}: artist.he ${song.artist.he}", song.artist.he.hasScript(UnicodeScript.LATIN))
            assertFalse("${song.id}: title.en ${song.title.en}", song.title.en.hasScript(UnicodeScript.HEBREW))
            assertFalse("${song.id}: artist.en ${song.artist.en}", song.artist.en.hasScript(UnicodeScript.HEBREW))
        }
    }

    @Test
    fun difficultyKeepsTheBestKnownPartOfEachOriginAndDecade() {
        assertEquals(bundled.songs.size, bundled.filter(GameSettings()).size) // hard, the default: every song
        for ((difficulty, thirds) in listOf(Difficulty.EASY to 1, Difficulty.MEDIUM to 2)) {
            val kept = bundled.filter(GameSettings(difficulty = difficulty)).map { it.id }.toSet()
            for ((group, songs) in bundled.songs.groupBy { it.origin to it.year / 10 * 10 }) {
                val (inside, outside) = songs.partition { it.id in kept }
                assertEquals("$difficulty $group", (songs.size * thirds + 2) / 3, inside.size)
                if (outside.isNotEmpty()) {
                    assertTrue("$difficulty $group", inside.minOf { it.rank } >= outside.maxOf { it.rank })
                }
            }
        }
        val easyOldIsraeli = GameSettings(pool = Pool.ISRAELI, fromDecade = 1960, toDecade = 1960, difficulty = Difficulty.EASY)
        assertTrue(bundled.filter(easyOldIsraeli).size >= 60)
    }

    @Test
    fun difficultyRanksASongOnlyAgainstItsOwnOriginAndDecade() {
        fun song(id: String, origin: Origin, year: Int, rank: Int) = Song(
            id = id, title = Names("ש", "s"), artist = Names("א", "a"), script = Lang.EN, year = year,
            origin = origin, genre = Genre.POP, deezerId = id.hashCode().toLong(), rank = rank,
        )
        val c = Catalog(
            listOf(
                song("il80-a", Origin.IL, 1981, 30_000), song("il80-b", Origin.IL, 1985, 20_000),
                song("il80-c", Origin.IL, 1989, 10_000),
                song("intl80-a", Origin.INTL, 1982, 900_000), song("intl80-b", Origin.INTL, 1983, 800_000),
                song("intl80-c", Origin.INTL, 1984, 700_000),
                song("il20-a", Origin.IL, 2021, 500_000),
            ),
        )
        fun ids(d: Difficulty) = c.filter(GameSettings(difficulty = d)).map { it.id }.toSet()
        // The best-known Israeli 1980s song stays on easy although international songs rank far higher.
        assertEquals(setOf("il80-a", "intl80-a", "il20-a"), ids(Difficulty.EASY))
        assertEquals(setOf("il80-a", "il80-b", "intl80-a", "intl80-b", "il20-a"), ids(Difficulty.MEDIUM))
        assertEquals(7, ids(Difficulty.HARD).size)
        val exact = GameSettings(fromYear = 1985, toYear = 1989, difficulty = Difficulty.EASY)
        assertEquals(emptyList<Song>(), c.filter(exact)) // ranked within the whole decade, not the chosen years
    }

    @Test
    fun bundledCatalogKeepsTheSeedSongs() {
        val missing = seed.songs.map { it.id } - bundled.songs.map { it.id }.toSet()
        assertEquals(24, seed.songs.size)
        assertEquals(emptyList<String>(), missing)
    }

    private fun String.hasScript(script: UnicodeScript) = codePoints().anyMatch { UnicodeScript.of(it) == script }

    @Test
    fun filterByPoolAndDecades() {
        val result = seed.filter(GameSettings(pool = Pool.ISRAELI, fromDecade = 1980, toDecade = 1990))

        assertTrue(result.isNotEmpty())
        for (song in result) {
            assertEquals(song.id, Origin.IL, song.origin)
            assertTrue("${song.id}: ${song.year}", song.year in 1980..1999)
        }
        val expected = seed.songs.filter { it.origin == Origin.IL && it.year in 1980..1999 }
        assertEquals(expected, result)
    }

    @Test
    fun exactYearsFilterInsteadOfTheDecades() {
        val result = seed.filter(GameSettings(fromDecade = 1940, toDecade = 1940, fromYear = 2002, toYear = 2004))

        assertEquals(seed.songs.filter { it.year in 2002..2004 }, result)
        assertEquals(3, result.size)
    }

    @Test
    fun byIdFindsSongOrNull() {
        assertNotNull(seed.byId("ed-sheeran-shape-of-you"))
        assertEquals(null, seed.byId("no-such-song"))
    }
}
