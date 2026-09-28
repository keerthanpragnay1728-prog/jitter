package dev.molasses.core.safety

import dev.molasses.core.safety.MuteGuard.Action
import dev.molasses.core.safety.MuteGuard.State
import org.junit.Assert.assertEquals
import org.junit.Test

class MuteGuardTest {

    private val empty = State()

    @Test
    fun `a silencing overlay mutes an unmuted stream and gives it back on release`() {
        val up = MuteGuard.take(empty, "gate", silences = true, streamMuted = false)
        assertEquals(Action.MUTE, up.action)
        assertEquals(State(setOf("gate"), ours = true), up.state)
        val down = MuteGuard.release(up.state, "gate")
        assertEquals(Action.UNMUTE, down.action)
        assertEquals(empty, down.state)
    }

    @Test
    fun `never at entry`() {
        for (overlay in OverlayAudio.Overlay.entries) {
            val step = MuteGuard.take(empty, "x", silences = OverlayAudio.silences(overlay), streamMuted = false)
            val expected = if (overlay == OverlayAudio.Overlay.ENTRY_GATE || overlay == OverlayAudio.Overlay.LOCK_AT_ENTRY) {
                Action.NONE
            } else {
                Action.MUTE
            }
            assertEquals("$overlay", expected, step.action)
        }
    }

    @Test
    fun `a stream muted before we arrived is left alone, now and later`() {
        val up = MuteGuard.take(empty, "gate", silences = true, streamMuted = true)
        assertEquals(Action.NONE, up.action)
        assertEquals(false, up.state.ours)
        assertEquals(Action.NONE, MuteGuard.release(up.state, "gate").action)
    }

    @Test
    fun `a handover never unmutes under the overlay still up`() {
        val gate = MuteGuard.take(empty, "gate", silences = true, streamMuted = false).state
        // The lock goes up before the gate comes down. The stream reads muted
        // by then, and that must not make the mute look like someone else's.
        val both = MuteGuard.take(gate, "lock", silences = true, streamMuted = true)
        assertEquals(Action.NONE, both.action)
        assertEquals(true, both.state.ours)
        val gateDown = MuteGuard.release(both.state, "gate")
        assertEquals(Action.NONE, gateDown.action)
        assertEquals(Action.UNMUTE, MuteGuard.release(gateDown.state, "lock").action)
    }

    @Test
    fun `taking twice or releasing a stranger does nothing`() {
        val up = MuteGuard.take(empty, "gate", silences = true, streamMuted = false).state
        assertEquals(Action.NONE, MuteGuard.take(up, "gate", silences = true, streamMuted = true).action)
        assertEquals(Step(up, Action.NONE), MuteGuard.release(up, "stranger"))
    }

    @Test
    fun `a failed mute leaves nothing to undo`() {
        val up = MuteGuard.take(empty, "gate", silences = true, streamMuted = false).state
        val failed = MuteGuard.muteFailed(up)
        assertEquals(Action.NONE, MuteGuard.release(failed, "gate").action)
    }

    @Test
    fun `a process death with our mute in force is repaired on connect`() {
        assertEquals(Action.UNMUTE, MuteGuard.onConnect(persistedOurs = true))
        assertEquals(Action.NONE, MuteGuard.onConnect(persistedOurs = false))
    }

    private fun Step(state: State, action: Action) = MuteGuard.Step(state, action)
}
