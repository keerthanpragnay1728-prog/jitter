package dev.molasses.sensing

import dev.molasses.core.model.GateProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepGateTest {

    private fun feed(gate: StepGate, intervalsMs: List<Long>, startMs: Long = 0): GateEvaluation {
        var t = startMs
        var last = gate.onStep(t)
        for (i in intervalsMs) {
            t += i
            last = gate.onStep(t)
        }
        return last
    }

    @Test
    fun `metronomic gait at 2 Hz passes`() {
        val g = StepGate()
        val p = feed(g, List(13) { 500L })
        assertTrue("reason=${p.progress.reason}", p.passed)
        assertEquals(GateProgress.Reason.PASSED, p.progress.reason)
    }

    @Test
    fun `slow walking at 1 Hz passes`() {
        val g = StepGate()
        val p = feed(g, List(13) { 850L })
        assertTrue("reason=${p.progress.reason}", p.passed)
    }

    @Test
    fun `eleven steps is not enough`() {
        val g = StepGate()
        val p = feed(g, List(10) { 500L })
        assertFalse(p.passed)
        assertEquals(GateProgress.Reason.NEED_MORE_STEPS, p.progress.reason)
        assertEquals(11, p.progress.events)
    }

    @Test
    fun `shaking produces erratic intervals and is rejected on CV`() {
        // This is the anti-cheat: OEM fusions do emit steps for a shaken
        // phone, but the intervals scatter.
        val g = StepGate()
        val erratic = listOf(
            310L, 880L, 300L, 760L, 320L, 700L, 330L, 850L, 290L, 820L, 300L, 900L, 310L,
        )
        val p = feed(g, erratic)
        assertFalse("should reject shaking, reason=${p.progress.reason}", p.passed)
        assertEquals(GateProgress.Reason.CADENCE_IRREGULAR, p.progress.reason)
    }

    @Test
    fun `sprinting cadence below the interval floor is rejected`() {
        val g = StepGate()
        val p = feed(g, List(13) { 200L })
        assertFalse(p.passed)
        assertEquals(GateProgress.Reason.CADENCE_TOO_FAST, p.progress.reason)
    }

    @Test
    fun `strolling above the interval ceiling is rejected`() {
        val g = StepGate()
        val p = feed(g, List(13) { 1_400L })
        assertFalse(p.passed)
        assertEquals(GateProgress.Reason.CADENCE_TOO_SLOW, p.progress.reason)
    }

    @Test
    fun `steps older than the 45 second window are pruned`() {
        val g = StepGate()
        // Twelve good steps, then a long pause, then two more: the early ones
        // must have fallen out of the window.
        feed(g, List(12) { 500L })
        val p = g.onStep(120_000)
        assertFalse(p.passed)
        assertTrue("expected pruning, events=${p.progress.events}", p.progress.events <= 2)
    }

    @Test
    fun `reset clears accumulated steps`() {
        val g = StepGate()
        feed(g, List(13) { 500L })
        g.reset()
        assertEquals(GateProgress.Reason.WAITING_TO_START, g.evaluate(0).progress.reason)
    }

    @Test
    fun `progress fraction is monotonic up to the requirement`() {
        val g = StepGate()
        var t = 0L
        var prev = 0f
        repeat(12) {
            val p = g.onStep(t)
            assertTrue(p.progress.fraction >= prev)
            prev = p.progress.fraction
            t += 500
        }
        assertEquals(1f, prev, 1e-4f)
    }
}
