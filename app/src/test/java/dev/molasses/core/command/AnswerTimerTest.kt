package dev.molasses.core.command

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerTimerTest {

    private val start = AnswerTimer()

    @Test
    fun `a clock dismisses the answer it was started for`() {
        val a = AnswerTimer.shown(start, holdMs = 4_250L)
        assertTrue(AnswerTimer.mayExpire(a, a.serial))
    }

    @Test
    fun `answer A is cleared, B is shown inside A's window, and A's clock does not dismiss B`() {
        val a = AnswerTimer.shown(start, holdMs = 4_250L)
        val aStarted = a.serial
        val cleared = AnswerTimer.cleared(a)
        val b = AnswerTimer.shown(cleared, holdMs = 4_250L)
        assertFalse(AnswerTimer.mayExpire(b, aStarted))
        assertTrue("B's own clock still works", AnswerTimer.mayExpire(b, b.serial))
    }

    @Test
    fun `a cleared answer's clock does nothing, so nothing logs expired`() {
        val a = AnswerTimer.shown(start, holdMs = 4_250L)
        assertFalse(AnswerTimer.mayExpire(AnswerTimer.cleared(a), a.serial))
    }

    @Test
    fun `an answer held until the user moves on has no clock at all`() {
        val a = AnswerTimer.shown(start, holdMs = null)
        assertFalse(AnswerTimer.mayExpire(a, a.serial))
    }

    @Test
    fun `B replacing A directly also retires A's clock`() {
        val a = AnswerTimer.shown(start, holdMs = 4_250L)
        val b = AnswerTimer.shown(a, holdMs = null)
        assertFalse(AnswerTimer.mayExpire(b, a.serial))
    }
}
