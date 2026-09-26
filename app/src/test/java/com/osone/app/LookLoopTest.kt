package com.osone.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LookLoopTest {
    @Test fun looksWithoutActingAreWarnedThenRefused() {
        var now = 0L
        val loop = LookLoop { now }
        assertEquals(LookLoop.Verdict.OK, loop.look())
        assertEquals(LookLoop.Verdict.OK, loop.look())
        assertEquals(LookLoop.Verdict.WARN, loop.look())
        assertEquals(LookLoop.Verdict.REFUSE, loop.look())
        assertEquals(LookLoop.Verdict.REFUSE, loop.look())
        // Agir zera a contagem.
        loop.acted()
        assertEquals(LookLoop.Verdict.OK, loop.look())
    }

    @Test fun idleMinuteResets() {
        var now = 0L
        val loop = LookLoop { now }
        repeat(4) { loop.look() }
        now += LookLoop.IDLE_RESET_MS + 1
        assertEquals(LookLoop.Verdict.OK, loop.look())
    }
}
