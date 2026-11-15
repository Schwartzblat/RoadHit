package com.roadhit.car

import com.roadhit.game.GameView
import com.roadhit.game.Lang
import com.roadhit.game.Phase
import com.roadhit.ui.GameScreenModel
import com.roadhit.ui.isGameActive
import com.roadhit.ui.screenModel
import com.roadhit.ui.uiStringsFor

/**
 * The car screen's header: the round text the phone shows ("Round 2 / 5") while a game runs and
 * the rounds are known, else the app name; both in the app language. Never the song.
 */
internal fun carHeaderTitle(view: GameView, lang: Lang): String =
    (screenModel(view, lang) as? GameScreenModel.InGame)?.round ?: uiStringsFor(lang).appName

/** What opening RoadHit in the car does. */
internal enum class CarOpenAction {
    /** No microphone permission: explain and ask for it on the phone; no game starts. */
    ASK_PERMISSION,
    /** Start a game (it offers to continue a saved one) and show it. */
    START_GAME,
    /** A game is already running (started on the phone or before a reconnect): show it. */
    SHOW_GAME,
}

internal fun carOpenAction(phase: Phase, micGranted: Boolean): CarOpenAction = when {
    isGameActive(phase) -> CarOpenAction.SHOW_GAME
    !micGranted -> CarOpenAction.ASK_PERMISSION
    else -> CarOpenAction.START_GAME
}

/** What a play command arriving through the media session (car card, assistant, media button, notification) does. */
internal enum class MediaPlayAction {
    /** Start a game (as [com.roadhit.session.GameService.start] does). */
    START_GAME,
    /** A game is under way: the engine's PLAY (resumes a pause). */
    RESUME,
    /** RoadHit is open neither in the car nor on the phone: no game starts by itself (e.g. Android Auto resuming the last media app on connect). */
    IGNORE,
}

internal fun mediaPlayAction(phase: Phase, carSessionOpen: Boolean, phoneUiVisible: Boolean): MediaPlayAction = when {
    isGameActive(phase) -> MediaPlayAction.RESUME
    carSessionOpen || phoneUiVisible -> MediaPlayAction.START_GAME
    else -> MediaPlayAction.IGNORE
}

/** What the car disconnecting (the car session's end) does to the game. */
internal enum class CarCloseAction {
    /** Stop the game as [com.roadhit.session.GameService.stop] does: the saved game stays, the service ends. */
    STOP_GAME,
    /** The phone screen is in use and no game runs: nothing to stop (a start from the phone is left alone). */
    NONE,
}

/**
 * A running game always stops (Ruling R38: it never carries on over the phone speaker by itself;
 * the next open offers to continue it). Without the phone screen any phase stops: a stop also
 * drops a start still on its way to the service.
 */
internal fun carCloseAction(phase: Phase, phoneUiVisible: Boolean): CarCloseAction = when {
    !phoneUiVisible || isGameActive(phase) -> CarCloseAction.STOP_GAME
    else -> CarCloseAction.NONE
}

/** What swiping the app away (task removal) does to the game. */
internal enum class TaskRemovedAction { STOP_GAME, KEEP_RUNNING }

/** Ruling R39: a running game keeps going only while a car session is open; on the phone alone it stops (it stays saved). */
internal fun taskRemovedAction(phase: Phase, carSessionOpen: Boolean): TaskRemovedAction =
    if (carSessionOpen && isGameActive(phase)) TaskRemovedAction.KEEP_RUNNING else TaskRemovedAction.STOP_GAME
