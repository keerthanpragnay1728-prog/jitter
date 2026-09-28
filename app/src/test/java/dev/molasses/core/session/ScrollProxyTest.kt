package dev.molasses.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollProxyTest {

    @Test
    fun `only a subtree or undefined change qualifies`() {
        assertTrue(ScrollProxy.qualifies(0))
        assertTrue(ScrollProxy.qualifies(1))
        assertTrue(ScrollProxy.qualifies(1 or 2))
        assertFalse("text: a video clock", ScrollProxy.qualifies(2))
        assertFalse("content description", ScrollProxy.qualifies(4))
        assertFalse("state description: a progress bar", ScrollProxy.qualifies(64))
    }

    @Test
    fun `a real scroll switches the proxy off for the session`() {
        val s = ScrollProxy.onRealScroll(ScrollProxy.State())
        assertFalse(ScrollProxy.mayPass(s, 1_000_000L))
    }

    @Test
    fun `at most one pass per second, however many changes arrive`() {
        var s = ScrollProxy.State()
        var passed = 0
        for (t in 0L until 10_000L step 20L) {
            if (ScrollProxy.mayPass(s, t)) {
                passed += 1
                s = ScrollProxy.onPassed(s, t, armedStallMs = 0L)
            }
        }
        assertEquals(10, passed)
    }

    @Test
    fun `it cannot arm more than once per stall window`() {
        // Every pass arms a 3 s stall. Over 30 s of a change every 20 ms that
        // is at most one arm per 3 s window.
        val stall = 3_000L
        var s = ScrollProxy.State()
        val arms = mutableListOf<Long>()
        for (t in 0L until 30_000L step 20L) {
            if (ScrollProxy.mayPass(s, t)) {
                arms += t
                s = ScrollProxy.onPassed(s, t, armedStallMs = stall)
            }
        }
        assertEquals(10, arms.size)
        assertTrue(arms.zipWithNext().all { (a, b) -> b - a >= stall })
    }

    @Test
    fun `inside a stall window nothing passes, and it passes again when the window ends`() {
        val s = ScrollProxy.onPassed(ScrollProxy.State(), nowMs = 0L, armedStallMs = 2_500L)
        assertFalse(ScrollProxy.mayPass(s, 1_000L))
        assertFalse(ScrollProxy.mayPass(s, 2_499L))
        assertTrue(ScrollProxy.mayPass(s, 2_500L))
    }
}
