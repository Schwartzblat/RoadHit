package com.roadhit.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import com.roadhit.game.ClipPlayer
import com.roadhit.game.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Plays a song's Deezer preview, fetched fresh at play time.
 *
 * The ExoPlayer lives on the main thread; [play] and [stop] may be called from any
 * thread. Clips play as media/music and do not request audio focus (the game service
 * owns focus). The clip carries no title or artist, and the preview's ID3 tags are not
 * read, so nothing can surface the song before the answer.
 *
 * Every wait is bounded: by [startTimeoutMs] until the audio starts, then by the clip
 * length plus [stallTimeoutMs] of buffering at a time.
 */
class ExoClipPlayer(
    context: Context,
    private val previews: DeezerPreviews,
    /** Playback must start (fetch and buffering included) within this long, or [play] returns false. */
    private val startTimeoutMs: Long = 20_000,
    /** Buffering this long after the clip started ends it ([play] returns true: it was partly heard). */
    private val stallTimeoutMs: Long = 10_000,
) : ClipPlayer {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The result of the [play] in progress; completing it with true ends that play. Guarded by [lock]. */
    private var pending: CompletableDeferred<Boolean>? = null
    private val lock = Any()

    // Main thread only.
    private var player: ExoPlayer? = null
    /** The play whose audio the player currently holds. */
    private var owner: CompletableDeferred<Boolean>? = null

    override suspend fun play(song: Song, maxSeconds: Int): Boolean {
        val done = CompletableDeferred<Boolean>()
        val started = CompletableDeferred<Unit>()
        // A newer play ends the older one.
        synchronized(lock) {
            pending?.complete(true)
            pending = done
        }
        // Loads in the player's own scope so that a stop or cancellation returns at once,
        // without waiting for a blocking preview fetch to give up.
        val work = scope.launch {
            val result = try {
                load(song, maxSeconds, done, started)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "clip failed deezer=${song.deezerId}", e)
                false
            }
            done.complete(result)
        }
        val watchdog = scope.launch {
            if (withTimeoutOrNull(startTimeoutMs) { started.await() } == null) {
                Log.w(TAG, "clip start-timeout deezer=${song.deezerId}")
                done.complete(false)
            }
        }
        try {
            return done.await()
        } finally {
            work.cancel()
            watchdog.cancel()
            synchronized(lock) { if (pending === done) pending = null }
            withContext(NonCancellable + Dispatchers.Main.immediate) { silence(done) }
        }
    }

    override fun stop() {
        val current = synchronized(lock) { pending?.also { it.complete(true) } } ?: return
        // Silence at once, without waiting for the playing coroutine to resume.
        main.post { silence(current) }
    }

    /** Releases the player. Safe from any thread; a later [play] builds a new one. */
    fun release() {
        stop()
        main.post {
            player?.release()
            player = null
            owner = null
        }
    }

    /** Main thread: stops the audio if it still belongs to [play]. */
    private fun silence(play: CompletableDeferred<Boolean>) {
        if (owner !== play) return
        owner = null
        player?.run {
            stop()
            clearMediaItems()
        }
    }

    /** Fetches and plays the clip; true when it ended, false when it could not be played. */
    private suspend fun load(
        song: Song,
        maxSeconds: Int,
        play: CompletableDeferred<Boolean>,
        started: CompletableDeferred<Unit>,
    ): Boolean {
        val url = previews.urlFor(song.deezerId)
        if (url == null) {
            Log.w(TAG, "clip no-preview deezer=${song.deezerId}")
            return false
        }
        return withContext(Dispatchers.Main.immediate) { playUrl(url, maxSeconds, play, started) }
    }

    /** Main thread. */
    @OptIn(UnstableApi::class)
    private suspend fun playUrl(
        url: String,
        maxSeconds: Int,
        play: CompletableDeferred<Boolean>,
        started: CompletableDeferred<Unit>,
    ): Boolean {
        if (play.isCompleted) return true
        val p = player ?: buildPlayer().also { player = it }
        return suspendCancellableCoroutine { cont ->
            var limit: PlayerMessage? = null
            lateinit var listener: Player.Listener
            lateinit var stall: Runnable
            fun finish(result: Boolean) {
                p.removeListener(listener)
                limit?.cancel()
                main.removeCallbacks(stall)
                if (cont.isActive) cont.resume(result)
            }
            stall = Runnable {
                Log.w(TAG, "clip stalled")
                finish(true)
            }
            listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) started.complete(Unit)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_ENDED -> finish(true)
                        Player.STATE_BUFFERING -> if (started.isCompleted) main.postDelayed(stall, stallTimeoutMs)
                        else -> main.removeCallbacks(stall)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "clip playback-error ${error.errorCodeName}")
                    finish(false)
                }
            }
            p.addListener(listener)
            owner = play
            // No title or artist: the song is never shown before the answer.
            p.setMediaItem(MediaItem.fromUri(url))
            // Fires when playback reaches maxSeconds, however long buffering took.
            limit = p.createMessage { _, _ -> finish(true) }
                .setLooper(Looper.getMainLooper())
                .setPosition(maxSeconds.coerceAtLeast(1) * 1000L)
                .setDeleteAfterDelivery(true)
                .send()
            p.prepare()
            p.play()
            cont.invokeOnCancellation { main.post { finish(false) } }
        }
    }

    @OptIn(UnstableApi::class)
    private fun buildPlayer(): ExoPlayer {
        // Deezer previews are MP3s whose ID3 tags carry the title, artist and cover.
        val extractors = DefaultExtractorsFactory().setMp3ExtractorFlags(Mp3Extractor.FLAG_DISABLE_ID3_METADATA)
        val dataSources = DefaultDataSource.Factory(appContext, clipHttpDataSourceFactory())
        return ExoPlayer.Builder(appContext, DefaultMediaSourceFactory(dataSources, extractors))
            .setLooper(Looper.getMainLooper())
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ false,
            )
            .build()
    }

    private companion object {
        const val TAG = "RoadHit"
    }
}

/** The clip stream's HTTP source, with the same pinned User-Agent as the API call ([USER_AGENT]). */
@OptIn(UnstableApi::class)
internal fun clipHttpDataSourceFactory(): DefaultHttpDataSource.Factory =
    DefaultHttpDataSource.Factory().setUserAgent(USER_AGENT)
