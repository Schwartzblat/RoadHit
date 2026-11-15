package com.roadhit.session

import com.roadhit.session.RunEffect.CANCEL_RUN
import com.roadhit.session.RunEffect.IGNORE
import com.roadhit.session.RunEffect.LAUNCH
import com.roadhit.session.RunEffect.NONE
import com.roadhit.session.RunEffect.PEND
import com.roadhit.session.RunEffect.STOP_SERVICE
import com.roadhit.session.RunEvent.RUN_ENDED
import com.roadhit.session.RunEvent.RUN_ENDING
import com.roadhit.session.RunEvent.START
import com.roadhit.session.RunEvent.STOP
import com.roadhit.session.RunState.ENDING
import com.roadhit.session.RunState.ENDING_THEN_START
import com.roadhit.session.RunState.IDLE
import com.roadhit.session.RunState.RUNNING
import org.junit.Assert.assertEquals
import org.junit.Test

class RunStateTest {

    private fun check(from: RunState, event: RunEvent, to: RunState, effect: RunEffect) =
        assertEquals("$from + $event", RunTransition(to, effect), runTransition(from, event))

    @Test
    fun `no run`() {
        check(IDLE, START, RUNNING, LAUNCH)
        check(IDLE, STOP, IDLE, STOP_SERVICE)
        check(IDLE, RUN_ENDING, IDLE, NONE)
        check(IDLE, RUN_ENDED, IDLE, NONE)
    }

    @Test
    fun `running`() {
        check(RUNNING, START, RUNNING, IGNORE)
        check(RUNNING, STOP, ENDING, CANCEL_RUN)
        check(RUNNING, RUN_ENDING, ENDING, NONE)
        check(RUNNING, RUN_ENDED, IDLE, STOP_SERVICE)
    }

    @Test
    fun `ending`() {
        check(ENDING, START, ENDING_THEN_START, PEND)
        check(ENDING, STOP, ENDING, NONE)
        check(ENDING, RUN_ENDING, ENDING, NONE)
        check(ENDING, RUN_ENDED, IDLE, STOP_SERVICE)
    }

    @Test
    fun `ending with a pending start`() {
        check(ENDING_THEN_START, START, ENDING_THEN_START, NONE)
        check(ENDING_THEN_START, STOP, ENDING, NONE)
        check(ENDING_THEN_START, RUN_ENDING, ENDING_THEN_START, NONE)
        check(ENDING_THEN_START, RUN_ENDED, RUNNING, LAUNCH)
    }

    @Test
    fun `stop then start during shutdown launches once the old run has ended`() {
        var s = IDLE
        val effects = mutableListOf<RunEffect>()
        for (e in listOf(START, STOP, START, RUN_ENDING, RUN_ENDED)) {
            val t = runTransition(s, e)
            s = t.state
            effects += t.effect
        }
        assertEquals(listOf(LAUNCH, CANCEL_RUN, PEND, NONE, LAUNCH), effects)
        assertEquals(RUNNING, s)
    }
}
