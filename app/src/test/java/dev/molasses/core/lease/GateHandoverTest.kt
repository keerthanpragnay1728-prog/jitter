package dev.molasses.core.lease

import dev.molasses.core.lease.GateHandover.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateHandoverTest {

    private val a = "com.instagram.android"
    private val b = "com.google.android.youtube"

    @Test
    fun `a direct switch closes the other app's session first`() {
        assertTrue(GateHandover.mustCloseFirst(openPkg = a, pkg = b))
    }

    @Test
    fun `re-entering the open app or entering with nothing open closes nothing`() {
        assertFalse(GateHandover.mustCloseFirst(openPkg = a, pkg = a))
        assertFalse(GateHandover.mustCloseFirst(openPkg = null, pkg = b))
    }

    @Test
    fun `another app's gate never stands in for this one`() {
        assertEquals(Action.ReleaseFirst(a), GateHandover.forGate(anyShowing = true, owner = a, pkg = b))
    }

    @Test
    fun `a window with no known owner is treated as foreign`() {
        assertEquals(Action.ReleaseFirst(null), GateHandover.forGate(anyShowing = true, owner = null, pkg = b))
    }

    @Test
    fun `this app's own gate is the decision`() {
        assertEquals(Action.AlreadyOwn, GateHandover.forGate(anyShowing = true, owner = b, pkg = b))
    }

    @Test
    fun `nothing showing means decide`() {
        assertEquals(Action.Decide, GateHandover.forGate(anyShowing = false, owner = null, pkg = b))
        // A stale owner with nothing on screen is still nothing on screen.
        assertEquals(Action.Decide, GateHandover.forGate(anyShowing = false, owner = a, pkg = b))
    }

    @Test
    fun `A then B then A each get their own answer`() {
        // The switch sequence the bug was found on, walked end to end.
        assertEquals(Action.ReleaseFirst(a), GateHandover.forGate(true, a, b))
        assertEquals(Action.Decide, GateHandover.forGate(false, null, b))
        assertEquals(Action.ReleaseFirst(b), GateHandover.forGate(true, b, a))
    }
}
