package dev.molasses.core.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatchTest {

    private val registry = CommandRegistry(
        CommandRegistry.Keys(
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
            14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26,
        ),
    )

    private val ranged = mutableListOf<Command>()

    private fun surface(s: Surface, availability: Availability) = object : EffectSurface {
        override val surface = s
        override fun availability(spec: CommandSpec) = availability
    }

    private fun dispatcher(
        state: Availability = Availability.Available,
        intent: Availability = Availability.Available,
        service: Availability = Availability.Available,
        subsystem: Availability = Availability.Available,
        relief: Availability = Availability.Available,
    ) = CommandDispatch(
        registry = registry,
        surfaces = mapOf(
            Surface.STATE to surface(Surface.STATE, state),
            Surface.INTENT to surface(Surface.INTENT, intent),
            Surface.SERVICE to surface(Surface.SERVICE, service),
            Surface.SUBSYSTEM to surface(Surface.SUBSYSTEM, subsystem),
        ),
        reliefPolicy = { relief },
        missingSurfaceKey = 999,
        execute = { ranged += it; DispatchResult.Confirmed(ackKey = 100) },
    )

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `an available command runs`() {
        val r = dispatcher().dispatch(Command.Focus(60_000))
        assertTrue(r is DispatchResult.Confirmed)
        assertEquals(listOf<Command>(Command.Focus(60_000)), ranged)
    }

    @Test
    fun `one unbound surface stops every command on it, with one check`() {
        // The reason the unit is the surface and not the verb: six commands
        // fail identically without six checks, and a seventh inherits it.
        val d = dispatcher(subsystem = Availability.Unavailable(reasonKey = 42))
        val all = listOf<Command>(
            Command.Block("ig", 60_000),
            Command.Focus(60_000),
            Command.Allow("yt", 60_000),
            Command.Bedtime,
            Command.Log(null),
            Command.Remind(60_000, "x"),
        )
        for (command in all) {
            val r = d.dispatch(command)
            assertTrue("$command should be unavailable", r is DispatchResult.Unavailable)
            assertEquals(42, (r as DispatchResult.Unavailable).reasonKey)
        }
        assertTrue("nothing should have executed", ranged.isEmpty())
    }

    @Test
    fun `a command on a healthy surface still runs while another is down`() {
        val d = dispatcher(subsystem = Availability.Unavailable(reasonKey = 42))
        assertTrue(d.dispatch(Command.Status) is DispatchResult.Confirmed)
    }

    @Test
    fun `a surface may answer differently for two commands on it`() {
        // Whether an Intent resolves cannot be answered without knowing which
        // Intent, which is why the spec is a parameter. One INTENT surface,
        // two answers, and neither command needs its own check.
        val perSpec = object : EffectSurface {
            override val surface = Surface.INTENT
            override fun availability(spec: CommandSpec): Availability =
                if (spec.verb == "wifi") Availability.Unavailable(reasonKey = 11)
                else Availability.Available
        }
        val d = CommandDispatch(
            registry = registry,
            surfaces = mapOf(Surface.INTENT to perSpec),
            reliefPolicy = { Availability.Available },
            missingSurfaceKey = 999,
            execute = { ranged += it; DispatchResult.Confirmed(ackKey = 100) },
        )
        assertTrue(d.dispatch(Command.Alarm(6 * 60)) is DispatchResult.Confirmed)
        val r = d.dispatch(Command.Wifi(null))
        assertEquals(11, (r as DispatchResult.Unavailable).reasonKey)
    }

    @Test
    fun `a missing surface reports rather than crashing`() {
        val d = CommandDispatch(
            registry = registry,
            surfaces = emptyMap(),
            reliefPolicy = { Availability.Available },
            missingSurfaceKey = 999,
            execute = { DispatchResult.Confirmed(1) },
        )
        val r = d.dispatch(Command.Status)
        assertEquals(999, (r as DispatchResult.Unavailable).reasonKey)
        // Named separately from a surface that reports unavailable: this one
        // is a wiring bug, and it must render rather than throw.
    }

    // ------------------------------------------------------- relief policy

    @Test
    fun `relief is refused by policy even when its surface is fine`() {
        // Without this the command bar is an escape hatch from the whole app.
        val d = dispatcher(relief = Availability.Unavailable(reasonKey = 7))
        val r = d.dispatch(Command.Allow("youtube", 600_000))
        assertEquals(7, (r as DispatchResult.Unavailable).reasonKey)
        assertTrue("relief must not reach the handler", ranged.isEmpty())
    }

    @Test
    fun `the relief gate applies only to relief commands`() {
        val d = dispatcher(relief = Availability.Unavailable(reasonKey = 7))
        assertTrue(d.dispatch(Command.Block("ig", 60_000)) is DispatchResult.Confirmed)
        assertTrue(d.dispatch(Command.Focus(60_000)) is DispatchResult.Confirmed)
    }

    @Test
    fun `the relief gate is checked before anything is armed`() {
        val d = dispatcher(relief = Availability.Unavailable(reasonKey = 7))
        d.dispatch(Command.Allow("youtube", 600_000))
        assertTrue(ranged.isEmpty())
    }

    // --------------------------------------------------------- confirmation

    @Test
    fun `a lock over a day echoes back and does not run`() {
        val r = dispatcher().dispatch(Command.Block("instagram", 30 * day))
        assertTrue(r is DispatchResult.NeedsConfirmation)
        assertEquals("block instagram 30d", (r as DispatchResult.NeedsConfirmation).echo)
        assertTrue("must not arm on the first Enter", ranged.isEmpty())
    }

    @Test
    fun `the second Enter runs it`() {
        val d = dispatcher()
        val command = Command.Block("instagram", 30 * day)
        d.dispatch(command)
        val r = d.dispatch(command, confirmed = true)
        assertTrue(r is DispatchResult.Confirmed)
        assertEquals(listOf<Command>(command), ranged)
    }

    @Test
    fun `exactly a day does not need confirming, a millisecond over does`() {
        assertTrue(dispatcher().dispatch(Command.Block("ig", day)) is DispatchResult.Confirmed)
        assertTrue(
            dispatcher().dispatch(Command.Block("ig", day + 1)) is DispatchResult.NeedsConfirmation,
        )
    }

    @Test
    fun `focus needs confirming too, allow never does`() {
        assertTrue(
            dispatcher().dispatch(Command.Focus(30 * day)) is DispatchResult.NeedsConfirmation,
        )
        // A lease is bounded relief, not an unfixable lock.
        assertTrue(dispatcher().dispatch(Command.Allow("yt", 30 * day)) is DispatchResult.Confirmed)
    }

    @Test
    fun `a command that arms nothing never asks for confirmation`() {
        for (command in listOf<Command>(Command.Status, Command.Bedtime, Command.Reboot)) {
            assertTrue(
                "$command should not need confirming",
                dispatcher().dispatch(command) !is DispatchResult.NeedsConfirmation,
            )
        }
    }

    @Test
    fun `availability is checked before confirmation`() {
        // A command that cannot run should say so rather than asking the user
        // to confirm something that will then fail.
        val d = dispatcher(subsystem = Availability.Unavailable(reasonKey = 5))
        val r = d.dispatch(Command.Block("ig", 30 * day))
        assertTrue(r is DispatchResult.Unavailable)
    }
}
