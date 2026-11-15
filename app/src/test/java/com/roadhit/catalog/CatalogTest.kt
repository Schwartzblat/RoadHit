package com.roadhit.catalog

import com.roadhit.game.GameSettings
import com.roadhit.game.Origin
import com.roadhit.game.Pool
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
