package com.roadhit.session

import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

private const val TAG = "RoadHit"
private const val APP_TITLE = "RoadHit"

private val ROOT = MediaItem.Builder()
    .setMediaId("root")
    .setMediaMetadata(
        MediaMetadata.Builder().setTitle(APP_TITLE).setIsBrowsable(true).setIsPlayable(false).build(),
    )
    .build()

private val GAME = MediaItem.Builder()
    .setMediaId(GameService.GAME_MEDIA_ID)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(APP_TITLE)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build(),
    )
    .build()

/**
 * The browse tree for Android Auto (a root with one playable item, the game), and who may
 * control the session: the app itself (and its notification controller), the Android Auto host,
 * and controllers the platform trusts (Media3's [MediaSession.ControllerInfo.isTrusted]: the system
 * UI, Bluetooth media buttons, the system's media-key dispatcher) get everything; any other app may
 * only read.
 */
internal class GameLibraryCallback : MediaLibrarySession.Callback {

    @OptIn(UnstableApi::class)
    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        val trusted = controller.isTrusted ||
            controller.uid == Process.myUid() ||
            session.isAutoCompanionController(controller) ||
            session.isAutomotiveController(controller) ||
            session.isMediaNotificationController(controller)
        Log.i(TAG, "session controller ${controller.packageName} trusted=$trusted")
        if (trusted) return super.onConnect(session, controller)
        // Read what is on display (never the song before the reveal), nothing else.
        val readOnly = Player.Commands.Builder().addAll(
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_AUDIO_ATTRIBUTES,
            Player.COMMAND_RELEASE,
        ).build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(SessionCommands.EMPTY)
            .setAvailablePlayerCommands(readOnly)
            .build()
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(ROOT, params))

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
        when (mediaId) {
            ROOT.mediaId -> LibraryResult.ofItem(ROOT, null)
            GAME.mediaId -> LibraryResult.ofItem(GAME, null)
            else -> LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        },
    )

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = Futures.immediateFuture(
        if (parentId == ROOT.mediaId) LibraryResult.ofItemList(listOf(GAME), params)
        else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE),
    )

    /** Every play request (by id, search or URI) is for the game; the following play starts it. */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<List<MediaItem>> {
        Log.i(TAG, "game service play request from=${controller.packageName}")
        return Futures.immediateFuture(listOf(GAME))
    }

    @OptIn(UnstableApi::class)
    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        Log.i(TAG, "game service resumption request from=${controller.packageName} forPlayback=$isForPlayback")
        return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(listOf(GAME), 0, C.TIME_UNSET))
    }
}
