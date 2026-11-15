package com.roadhit.game

/** Where the game gets its songs from. */
interface SongSource {
    /** Number of songs matching the settings, whether or not they were played. */
    fun count(s: GameSettings): Int

    /** A song not yet played, or null when nothing matches the settings. */
    fun next(s: GameSettings): Song?

    fun byId(id: String): Song?
}

/** Remembers which songs were already played. */
interface PlayedIds {
    fun all(): Set<String>
    fun add(id: String)
    fun clear(ids: Set<String>)
}

/** Plays song clips. */
interface ClipPlayer {
    /** Returns when the clip ends or [stop] is called; false means the clip could not be loaded. */
    suspend fun play(song: Song, maxSeconds: Int): Boolean

    /** Safe to call at any time, from any thread. */
    fun stop()
}

/** Speaks to the players. */
interface Speaker {
    /** Returns when every part has been spoken. */
    suspend fun say(parts: List<Utterance>)

    /** The "your answer now" cue. */
    suspend fun beep()
}

/** Raw audio of one spoken answer, kept so it can be recognized again in the other language. */
class RecordedAudio(val pcm: ByteArray)

/** What one listen produced. */
sealed interface Heard {
    data class Speech(val transcripts: List<String>, val audio: RecordedAudio?) : Heard
    object Silence : Heard
    data class Failed(val reason: String) : Heard

    /**
     * The recognizer found no match in the listen's language but audio was kept: not silence until
     * the other language has been tried. Only a [Listener] returns it; `Conversation.listen`
     * resolves it to [Speech] or [Silence] and never passes it on.
     */
    class Unclear(val audio: RecordedAudio) : Heard
}

/** Listens for one spoken sentence. */
interface Listener {
    suspend fun listen(lang: Lang, maxSeconds: Int): Heard

    /** Recognizes [audio] again, in [lang]; the transcripts, best first. */
    suspend fun relisten(audio: RecordedAudio, lang: Lang): List<String>
}

enum class Phase { IDLE, SETUP, CLIP, LISTENING, REVEAL, PAUSED, FINISHED }

/** What the screen shows. [revealed] is non-null only in [Phase.REVEAL]. */
data class GameView(
    val phase: Phase,
    val playerNames: List<String>,
    val scores: List<Int>,
    val currentPlayer: Int,
    val round: Int,
    val rounds: Int,
    val revealed: Song?,
)

interface Display {
    fun show(view: GameView)
}

/** Keeps the last settings and the game in progress. */
interface GameStore {
    suspend fun loadSettings(): GameSettings?
    suspend fun saveSettings(s: GameSettings)
    suspend fun loadGame(): SavedGame?
    /** null clears the saved game. */
    suspend fun saveGame(g: SavedGame?)
}

/** Media buttons (steering wheel, headset, notification). */
enum class Button { NEXT, PREVIOUS, PAUSE, PLAY }
