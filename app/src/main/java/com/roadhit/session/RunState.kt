package com.roadhit.session

/** Where the service's game run is. */
internal enum class RunState {
    /** No run. */
    IDLE,
    /** A run is active. */
    RUNNING,
    /** The run has ended or was cancelled; its cleanup (release, flush) is in progress. */
    ENDING,
    /** As [ENDING], and a start arrived meanwhile: a new run begins once the cleanup is done. */
    ENDING_THEN_START,
}

internal enum class RunEvent {
    /** A start was requested. */
    START,
    /** A stop was requested (or focus was lost for good). */
    STOP,
    /** The run's coroutine has returned or was cancelled; cleanup begins. */
    RUN_ENDING,
    /** Cleanup is done: everything released and flushed. */
    RUN_ENDED,
}

internal enum class RunEffect {
    /** Start a new run. */
    LAUNCH,
    /** A start while a run is active: ignored. */
    IGNORE,
    /** A start remembered until the ending run is gone. */
    PEND,
    /** Cancel the active run (its end follows as RUN_ENDING, RUN_ENDED). */
    CANCEL_RUN,
    /** Leave the foreground and stop the service. */
    STOP_SERVICE,
    NONE,
}

internal data class RunTransition(val state: RunState, val effect: RunEffect)

/** The service's run lifecycle: what [event] does in [state]. */
internal fun runTransition(state: RunState, event: RunEvent): RunTransition = when (state) {
    RunState.IDLE -> when (event) {
        RunEvent.START -> RunTransition(RunState.RUNNING, RunEffect.LAUNCH)
        RunEvent.STOP -> RunTransition(RunState.IDLE, RunEffect.STOP_SERVICE)
        RunEvent.RUN_ENDING, RunEvent.RUN_ENDED -> RunTransition(RunState.IDLE, RunEffect.NONE)
    }
    RunState.RUNNING -> when (event) {
        RunEvent.START -> RunTransition(RunState.RUNNING, RunEffect.IGNORE)
        RunEvent.STOP -> RunTransition(RunState.ENDING, RunEffect.CANCEL_RUN)
        RunEvent.RUN_ENDING -> RunTransition(RunState.ENDING, RunEffect.NONE)
        RunEvent.RUN_ENDED -> RunTransition(RunState.IDLE, RunEffect.STOP_SERVICE)
    }
    RunState.ENDING -> when (event) {
        RunEvent.START -> RunTransition(RunState.ENDING_THEN_START, RunEffect.PEND)
        RunEvent.STOP, RunEvent.RUN_ENDING -> RunTransition(RunState.ENDING, RunEffect.NONE)
        RunEvent.RUN_ENDED -> RunTransition(RunState.IDLE, RunEffect.STOP_SERVICE)
    }
    RunState.ENDING_THEN_START -> when (event) {
        RunEvent.START, RunEvent.RUN_ENDING -> RunTransition(RunState.ENDING_THEN_START, RunEffect.NONE)
        RunEvent.STOP -> RunTransition(RunState.ENDING, RunEffect.NONE) // the pending start is dropped
        RunEvent.RUN_ENDED -> RunTransition(RunState.RUNNING, RunEffect.LAUNCH)
    }
}
