package com.roadhit.session

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.roadhit.game.Button
import com.roadhit.game.Display
import com.roadhit.game.GameView
import com.roadhit.game.Lang
import com.roadhit.game.Phase
import com.roadhit.game.Song
import com.roadhit.game.phrasesFor
import com.roadhit.ui.uiStringsFor
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * What the notification, the car and the media buttons see: a player with one item, the game,
 * whose state and metadata follow the engine's latest [GameView]. It plays nothing itself.
 *
 * Commands map to the engine's buttons: play → [Controls.play] (the service decides: resume, a new
 * game, or nothing), pause → PAUSE, next → NEXT, previous → PREVIOUS, stop → stops the game. Not seekable.
 *
 * The song is in the metadata only in [Phase.REVEAL], after the answer was judged.
 *
 * Lives on the main thread; [show] may be called from any thread.
 */
@OptIn(UnstableApi::class)
class GamePlayer(
    private val lang: () -> Lang,
    /** The app logo as PNG bytes, the artwork outside the reveal. */
    private val logo: ByteArray?,
    /** The album cover of a revealed song, if known. */
    private val coverFor: (Song) -> String?,
    private val controls: Controls,
) : SimpleBasePlayer(Looper.getMainLooper()), Display {

    /** What the player's commands do. Called on the main thread. */
    interface Controls {
        /** A play command (car card, assistant, media button, notification). */
        fun play()
        fun stop()
        fun press(b: Button)
    }

    private val main = Handler(Looper.getMainLooper())

    // Main thread only.
    private var view = IDLE_VIEW
    private var face = faceOf(IDLE_VIEW, Lang.EN) { null }
    private var released = false

    /** The phase on display. Main thread. */
    val phase: Phase get() = view.phase

    override fun show(view: GameView) {
        main.post {
            if (released) return@post
            this.view = view
            face = faceOf(view, lang(), coverFor)
            invalidateState()
        }
    }

    override fun getState(): State {
        val metadata = MediaMetadata.Builder()
            .setTitle(face.title)
            .setArtist(face.artist)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .apply {
                val cover = face.coverUrl
                if (cover != null) setArtworkUri(cover.toUri())
                else if (logo != null) setArtworkData(logo, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            }
            .build()
        val item = MediaItemData.Builder(ITEM_UID)
            .setMediaItem(MediaItem.Builder().setMediaId(GameService.GAME_MEDIA_ID).setMediaMetadata(metadata).build())
            .setIsSeekable(false)
            .setDurationUs(C.TIME_UNSET)
            .build()
        return State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(face.playbackState)
            .setPlayWhenReady(face.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setAudioAttributes(AUDIO_ATTRIBUTES)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controls.play() else controls.press(Button.PAUSE)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        controls.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> controls.press(Button.NEXT)
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> controls.press(Button.PREVIOUS)
            else -> Unit // no other seek is offered
        }
        return Futures.immediateVoidFuture()
    }

    /** A play request by item (car browse list, assistant): the game is the only item, so nothing changes. */
    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> =
        Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        return Futures.immediateVoidFuture()
    }

    private companion object {
        val ITEM_UID = Any()

        val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES,
            Player.COMMAND_RELEASE,
        ).build()

        val AUDIO_ATTRIBUTES: AudioAttributes =
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
    }
}

internal val IDLE_VIEW = GameView(Phase.IDLE, emptyList(), emptyList(), 0, 0, 0, null)

/** How the player presents one [GameView]: transport state and metadata. */
internal data class PlayerFace(
    val playing: Boolean,
    /** A `Player.STATE_*` value. */
    val playbackState: Int,
    val title: String,
    val artist: String?,
    /** The album cover to show instead of the logo; only ever set in [Phase.REVEAL]. */
    val coverUrl: String?,
)

/**
 * Setup, clip, listening and reveal are "playing"; paused is ready but not playing; idle and
 * finished are ended (not playing): idle shows the app name and "press play". Outside the reveal the title is whose turn it is (as spoken) and the
 * artist the score line; [coverFor] is consulted only for the reveal.
 */
internal fun faceOf(view: GameView, lang: Lang, coverFor: (Song) -> String?): PlayerFace {
    val scores = if (view.playerNames.isEmpty()) null else scoreLine(view.playerNames, view.scores)
    val turn = view.playerNames.getOrNull(view.currentPlayer)?.let { phrasesFor(lang).turn(it) } ?: uiStringsFor(lang).appName
    val inTurn = PlayerFace(true, Player.STATE_READY, turn, scores, null)
    return when (view.phase) {
        // A valid, non-playing item (not STATE_IDLE, which the car host shows as "Could not load your selection").
        Phase.IDLE -> PlayerFace(false, Player.STATE_ENDED, uiStringsFor(lang).appName, uiStringsFor(lang).pressPlayToStart, null)
        Phase.SETUP -> PlayerFace(true, Player.STATE_READY, uiStringsFor(lang).appName, null, null)
        Phase.CLIP, Phase.LISTENING -> inTurn
        Phase.PAUSED -> inTurn.copy(playing = false)
        Phase.FINISHED -> PlayerFace(false, Player.STATE_ENDED, gameOver(lang), scores, null)
        Phase.REVEAL -> view.revealed?.let { song ->
            val title = if (song.script == Lang.HE) song.title.he else song.title.en
            val artist = if (lang == Lang.HE) song.artist.he else song.artist.en
            PlayerFace(true, Player.STATE_READY, "$title (${song.year})", artist, coverFor(song))
        } ?: inTurn
    }
}

private fun gameOver(lang: Lang) = when (lang) {
    Lang.HE -> "סוף המשחק"
    Lang.EN -> "Game over"
}

/**
 * "P1 12 · P2 7 · P3 9": each player as initials (at most three), or "P<n>" for a default or
 * blank name and wherever initials would be ambiguous.
 */
internal fun scoreLine(names: List<String>, scores: List<Int>): String {
    val short = names.mapIndexed { i, name -> initials(name, i + 1) }
    val counts = short.groupingBy { it }.eachCount()
    return names.indices.joinToString(" · ") { i ->
        val label = short[i]?.takeIf { counts[it] == 1 } ?: "P${i + 1}"
        "$label ${scores.getOrElse(i) { 0 }}"
    }
}

/** Initials of [name], or null when it is blank or the default name of player [n]. */
private fun initials(name: String, n: Int): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || Lang.entries.any { phrasesFor(it).defaultPlayerName(n) == trimmed }) return null
    return trimmed.split(Regex("[\\s-]+"))
        .filter { it.isNotEmpty() }
        .take(3)
        .joinToString("") { it.substring(0, Character.charCount(it.codePointAt(0))).uppercase() }
}
