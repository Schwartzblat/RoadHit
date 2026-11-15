package com.roadhit.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.roadhit.game.GameSettings
import com.roadhit.game.GameStore
import com.roadhit.game.Lang
import com.roadhit.game.PlayedIds
import com.roadhit.game.Prefs
import com.roadhit.game.SavedGame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistent state on a DataStore: last settings, game in progress, preferences and played song ids.
 * Values are JSON strings; anything unreadable counts as absent.
 */
class AppStores private constructor(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    initialPrefs: Prefs,
    initialSettings: GameSettings?,
    initialPlayed: Set<String>,
    private val onWriteError: (Throwable) -> Unit,
) : GameStore, PlayedIds {

    private val _prefs = MutableStateFlow(initialPrefs)
    val prefs: StateFlow<Prefs> = _prefs

    private val _settings = MutableStateFlow(initialSettings)

    /** The last game's choices, which the next game offers first; null until a game was set up or one was changed. */
    val settings: StateFlow<GameSettings?> = _settings

    private val lock = Any()
    private var played: Set<String> = initialPlayed

    /** Guards [played], [dirty] and [writerActive]. */
    private var dirty = false
    private var writerActive = false

    /** Serialises disk writes of the played set; each write takes the latest snapshot inside the lock. */
    private val writeLock = Mutex()

    /** Serialises [updatePrefs] so [prefs] always equals what was last committed. */
    private val prefsLock = Mutex()

    /** The same for [saveSettings], [updateSettings] and [settings]. */
    private val settingsLock = Mutex()

    override suspend fun loadSettings(): GameSettings? = read(SETTINGS, GameSettings.serializer())

    override suspend fun saveSettings(s: GameSettings) {
        settingsLock.withLock {
            dataStore.edit { it[SETTINGS] = json.encodeToString(s) }
            _settings.value = s
        }
    }

    /** Changes the choices the next game offers first; starts from the defaults when none are stored. */
    suspend fun updateSettings(f: (GameSettings) -> GameSettings) {
        settingsLock.withLock {
            var result = _settings.value
            dataStore.edit {
                val next = f(decode(it[SETTINGS], GameSettings.serializer()) ?: GameSettings())
                it[SETTINGS] = json.encodeToString(next)
                result = next
            }
            _settings.value = result
        }
    }

    override suspend fun loadGame(): SavedGame? = read(GAME, SavedGame.serializer())

    override suspend fun saveGame(g: SavedGame?) {
        dataStore.edit {
            if (g == null) it.remove(GAME) else it[GAME] = json.encodeToString(g)
        }
    }

    suspend fun updatePrefs(f: (Prefs) -> Prefs) {
        prefsLock.withLock {
            var result = _prefs.value
            dataStore.edit {
                val current = decode(it[PREFS], Prefs.serializer()) ?: result
                result = clamp(f(current))
                it[PREFS] = json.encodeToString(result)
            }
            _prefs.value = result
        }
    }

    override fun all(): Set<String> = synchronized(lock) { played }

    override fun add(id: String) = change { it + id }

    override fun clear(ids: Set<String>) = change { it - ids }

    private fun change(f: (Set<String>) -> Set<String>) {
        val start = synchronized(lock) {
            played = f(played)
            dirty = true
            (!writerActive).also { if (it) writerActive = true }
        }
        if (start) scope.launch { writeLoop() }
    }

    /** Persists the latest snapshot until nothing is left dirty; queued changes coalesce into one write. */
    private suspend fun writeLoop() {
        try {
            while (true) {
                val more = synchronized(lock) { dirty }
                if (!more) {
                    // Re-check under the lock so a change racing with exit is not lost.
                    val exit = synchronized(lock) { if (dirty) false else { writerActive = false; true } }
                    if (exit) return
                    continue
                }
                persist()
            }
        } catch (e: CancellationException) {
            synchronized(lock) { writerActive = false }
            throw e
        }
    }

    /** Writes the current snapshot; failures are reported, never thrown (except real cancellation). */
    private suspend fun persist() {
        writeLock.withLock {
            val snapshot = synchronized(lock) { dirty = false; played }
            try {
                dataStore.edit { it[PLAYED] = json.encodeToString(snapshot.toList()) }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                onWriteError(e)
            } catch (e: Exception) {
                onWriteError(e)
            }
        }
    }

    /**
     * Returns when the current played set is on disk. Independent of the background writer and the
     * scope; a failed write is reported through onWriteError and flush still returns.
     */
    suspend fun flush() = persist()

    private suspend fun <T> read(key: Preferences.Key<String>, ser: KSerializer<T>): T? =
        decode(dataStore.data.first()[key], ser)

    companion object {
        private val SETTINGS = stringPreferencesKey("settings")
        private val GAME = stringPreferencesKey("game")
        private val PREFS = stringPreferencesKey("prefs")
        private val PLAYED = stringPreferencesKey("played")

        private val json = Json { ignoreUnknownKeys = true }

        private fun <T> decode(text: String?, ser: KSerializer<T>): T? {
            if (text == null) return null
            return try {
                json.decodeFromString(ser, text)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        private fun clamp(p: Prefs) = p.copy(
            yearTolerance = p.yearTolerance.coerceIn(0, 10),
            clipSeconds = p.clipSeconds.coerceIn(10, 30),
            playerNames = p.playerNames.take(8).map { it.trim().take(30) },
        )

        /** Reads the stored state once; [defaultLang] is used only if no prefs were ever stored. */
        suspend fun open(
            dataStore: DataStore<Preferences>,
            scope: CoroutineScope,
            defaultLang: Lang,
            onWriteError: (Throwable) -> Unit = {},
        ): AppStores {
            val stored = dataStore.data.first()
            val prefs = decode(stored[PREFS], Prefs.serializer())?.let(::clamp) ?: Prefs(lang = defaultLang)
            val settings = decode(stored[SETTINGS], GameSettings.serializer())
            val played = decode(stored[PLAYED], ListSerializer(String.serializer()))?.toSet() ?: emptySet()
            return AppStores(dataStore, scope, prefs, settings, played, onWriteError)
        }
    }
}
