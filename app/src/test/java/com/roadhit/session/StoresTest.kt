package com.roadhit.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.roadhit.game.Difficulty
import com.roadhit.game.GameSettings
import com.roadhit.game.Genre
import com.roadhit.game.Lang
import com.roadhit.game.Pool
import com.roadhit.game.Prefs
import com.roadhit.game.SavedGame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StoresTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private lateinit var file: File

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    private fun dataStore(scope: CoroutineScope): DataStore<Preferences> {
        if (!::file.isInitialized) file = File(tmp.newFolder(), "stores.preferences_pb")
        return PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
    }

    private suspend fun open(lang: Lang = Lang.HE): AppStores {
        val scope = newScope()
        return AppStores.open(dataStore(scope), scope, lang)
    }

    /** Cancels everything opened so far, so the file can be opened again. */
    private suspend fun closeAll() {
        scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
        scopes.clear()
    }

    private suspend fun writeRaw(key: String, value: String) {
        val scope = newScope()
        dataStore(scope).edit { it[stringPreferencesKey(key)] = value }
        closeAll()
    }

    @After
    fun tearDown() = runBlocking { closeAll() }

    private val settings = GameSettings(3, Pool.ISRAELI, 1960, 2000, Genre.ROCK, 7)
    private val game = SavedGame(settings, listOf(1, 2, 3), 4, 1, "song-9")

    @Test
    fun settingsAndGameSurviveReopen() = runBlocking {
        val a = open()
        assertNull(a.loadSettings())
        assertNull(a.loadGame())
        a.saveSettings(settings)
        a.saveGame(game)
        closeAll()
        val b = open()
        assertEquals(settings, b.loadSettings())
        assertEquals(game, b.loadGame())
    }

    @Test
    fun settingsSavedBeforeDifficultyExistedLoadAsHard() = runBlocking {
        writeRaw("settings", """{"players":3,"pool":"ISRAELI","rounds":7}""")
        val loaded = open().loadSettings()
        assertEquals(GameSettings(players = 3, pool = Pool.ISRAELI, rounds = 7), loaded)
        assertEquals(Difficulty.HARD, loaded?.difficulty)
    }

    @Test
    fun difficultySurvivesReopen() = runBlocking {
        open().saveSettings(settings.copy(difficulty = Difficulty.EASY))
        closeAll()
        assertEquals(Difficulty.EASY, open().loadSettings()?.difficulty)
    }

    @Test
    fun updateSettingsStartsFromTheDefaultsAndPersists() = runBlocking {
        val a = open()
        assertNull(a.settings.value)
        a.updateSettings { it.copy(pool = Pool.ISRAELI) }
        assertEquals(GameSettings(pool = Pool.ISRAELI), a.settings.value)
        assertEquals(GameSettings(pool = Pool.ISRAELI), a.loadSettings())
        closeAll()
        assertEquals(GameSettings(pool = Pool.ISRAELI), open().settings.value)
    }

    @Test
    fun updateSettingsKeepsTheOtherChoicesOfTheLastGame() = runBlocking {
        val a = open()
        a.saveSettings(settings)
        assertEquals(settings, a.settings.value)
        a.updateSettings { it.copy(pool = Pool.MIXED) }
        assertEquals(settings.copy(pool = Pool.MIXED), a.settings.value)
        assertEquals(settings.copy(pool = Pool.MIXED), a.loadSettings())
    }

    @Test
    fun saveGameNullClears() = runBlocking {
        val a = open()
        a.saveGame(game)
        a.saveGame(null)
        assertNull(a.loadGame())
        closeAll()
        assertNull(open().loadGame())
    }

    @Test
    fun playedIdsSurviveReopen() = runBlocking {
        val a = open()
        a.add("a")
        a.add("b")
        assertEquals(setOf("a", "b"), a.all())
        a.flush()
        closeAll()
        assertEquals(setOf("a", "b"), open().all())
    }

    @Test
    fun clearRemovesOnlyGivenIds() = runBlocking {
        val a = open()
        a.add("a")
        a.add("b")
        a.clear(setOf("a"))
        assertEquals(setOf("b"), a.all())
        a.flush()
        closeAll()
        assertEquals(setOf("b"), open().all())
    }

    @Test
    fun manyRapidAddsAllPersist() = runBlocking {
        val a = open()
        val ids = (1..200).map { "id$it" }
        ids.forEach { a.add(it) }
        a.flush()
        closeAll()
        assertEquals(ids.toSet(), open().all())
    }

    @Test
    fun allReturnsSnapshot() = runBlocking {
        val a = open()
        a.add("a")
        val snap = a.all()
        a.add("b")
        assertEquals(setOf("a"), snap)
    }

    @Test
    fun emptyStoreUsesDefaultLang() = runBlocking {
        assertEquals(Prefs(lang = Lang.HE), open(Lang.HE).prefs.value)
        closeAll()
        assertEquals(Prefs(lang = Lang.EN), open(Lang.EN).prefs.value)
    }

    @Test
    fun storedLangWinsOverDefault() = runBlocking {
        val a = open(Lang.HE)
        a.updatePrefs { it.copy(lang = Lang.EN) }
        closeAll()
        assertEquals(Lang.EN, open(Lang.HE).prefs.value.lang)
    }

    @Test
    fun updatePrefsClampsAndPersists() = runBlocking {
        val a = open()
        a.updatePrefs { it.copy(yearTolerance = 99) }
        assertEquals(10, a.prefs.value.yearTolerance)
        a.updatePrefs { it.copy(yearTolerance = -4) }
        assertEquals(0, a.prefs.value.yearTolerance)
        a.updatePrefs { it.copy(clipSeconds = 3) }
        assertEquals(10, a.prefs.value.clipSeconds)
        a.updatePrefs { it.copy(clipSeconds = 99) }
        assertEquals(30, a.prefs.value.clipSeconds)
        closeAll()
        val b = open().prefs.value
        assertEquals(0, b.yearTolerance)
        assertEquals(30, b.clipSeconds)
    }

    @Test
    fun playerNamesTrimmedAndCapped() = runBlocking {
        val a = open()
        val names = (1..12).map { "  p$it  " }
        a.updatePrefs { it.copy(playerNames = names) }
        assertEquals((1..8).map { "p$it" }, a.prefs.value.playerNames)
        a.updatePrefs { it.copy(playerNames = listOf("x".repeat(50))) }
        assertEquals(listOf("x".repeat(30)), a.prefs.value.playerNames)
    }

    @Test
    fun prefsValueChanges() = runBlocking {
        val a = open()
        val before = a.prefs.value
        a.updatePrefs { it.copy(autoStartOnOpen = true) }
        assertNotEquals(before, a.prefs.value)
        assertEquals(true, a.prefs.value.autoStartOnOpen)
    }

    @Test
    fun corruptValuesAreTreatedAsAbsent() = runBlocking {
        for (key in listOf("settings", "game", "prefs", "played")) writeRaw(key, "{not json[")
        val a = open(Lang.EN)
        assertNull(a.loadSettings())
        assertNull(a.loadGame())
        assertEquals(Prefs(lang = Lang.EN), a.prefs.value)
        assertEquals(emptySet<String>(), a.all())
        a.add("z")
        a.flush()
        assertEquals(setOf("z"), a.all())
    }

    // --- fix round 1 ---

    /** Wraps a DataStore to count writes and optionally fail the first one. */
    private class SpyStore(
        private val inner: DataStore<Preferences>,
        private val failFirst: Boolean = false,
    ) : DataStore<Preferences> {
        val writes = java.util.concurrent.atomic.AtomicInteger()
        override val data = inner.data
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (writes.incrementAndGet() == 1 && failFirst) throw java.io.IOException("disk full")
            return inner.updateData(transform)
        }
    }

    private fun spyOpen(spy: (DataStore<Preferences>) -> SpyStore, storesScope: CoroutineScope,
                        onErr: (Throwable) -> Unit = {}): Pair<AppStores, SpyStore> = runBlocking {
        val dsScope = newScope()
        val s = spy(dataStore(dsScope))
        AppStores.open(s, storesScope, Lang.HE, onErr) to s
    }

    @Test
    fun failedWriteDoesNotKillWriter() = runBlocking<Unit> {
        withTimeout(20_000) {
            val failed = CompletableDeferred<Throwable>()
            val (a, _) = spyOpen({ SpyStore(it, failFirst = true) }, newScope()) { failed.complete(it) }
            a.add("a")
            failed.await()
            a.add("b")
            a.flush()
            closeAll()
            assertEquals(setOf("a", "b"), open().all())
        }
    }

    @Test
    fun flushAfterScopeCancelledReturns() = runBlocking<Unit> {
        withTimeout(20_000) {
            val storesScope = newScope()
            val (a, _) = spyOpen({ SpyStore(it) }, storesScope)
            a.add("a")
            storesScope.coroutineContext[Job]!!.cancelAndJoin()
            a.flush()
            a.add("x")
            assertEquals(setOf("a", "x"), a.all())
            a.flush()
        }
    }

    @Test
    fun cancellingScopeDuringFlushDoesNotHang() = runBlocking<Unit> {
        withTimeout(20_000) {
            val storesScope = newScope()
            val (a, _) = spyOpen({ SpyStore(it) }, storesScope)
            a.add("a")
            val flushing = async(Dispatchers.IO) { runCatching { a.flush() } }
            storesScope.coroutineContext[Job]!!.cancel()
            flushing.await()
        }
    }

    @Test
    fun addAfterScopeCancelledDoesNotThrow() = runBlocking<Unit> {
        withTimeout(20_000) {
            val storesScope = newScope()
            val (a, _) = spyOpen({ SpyStore(it) }, storesScope)
            storesScope.coroutineContext[Job]!!.cancelAndJoin()
            a.add("x")
            assertEquals(setOf("x"), a.all())
        }
    }

    @Test
    fun concurrentUpdatePrefsEndsConsistentWithDisk() = runBlocking<Unit> {
        withTimeout(30_000) {
            val a = open()
            (0..20).map { n -> async(Dispatchers.IO) { a.updatePrefs { it.copy(yearTolerance = n % 11) } } }.awaitAll()
            val inMemory = a.prefs.value
            closeAll()
            assertEquals(inMemory, open().prefs.value)
        }
    }

    @Test
    fun rapidAddsCoalesceWrites() = runBlocking<Unit> {
        withTimeout(30_000) {
            val (a, spy) = spyOpen({ SpyStore(it) }, newScope())
            val ids = (1..200).map { "id$it" }
            ids.forEach { a.add(it) }
            a.flush()
            assertTrue("writes=${spy.writes.get()}", spy.writes.get() < 50)
            closeAll()
            assertEquals(ids.toSet(), open().all())
        }
    }
}
