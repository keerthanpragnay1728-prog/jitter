package dev.molasses.core.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatchTest {

    private val registry = CommandRegistry(
        dummyCommandKeys(),
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
            Command.Bedtime,
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
        //
        // Driven through a synthetic spec rather than a command, because no
        // shipped command is relief since "allow" was deleted. That is the
        // point of testing it this way: the gate belongs to the dispatcher
        // and not to any one verb, so a future relief command inherits it
        // rather than having to remember it, and the branch stays covered in
        // the meantime.
        val d = dispatcher(relief = Availability.Unavailable(reasonKey = 7))
        val spec = CommandSpec("someday", 1, 2, Surface.SUBSYSTEM, isRelief = true)
        val available = d.availabilityOf(spec)
        assertEquals(7, (available as Availability.Unavailable).reasonKey)
    }

    @Test
    fun `a relief spec whose policy allows it still passes the gate`() {
        // The other direction, so the test above is proving the policy is
        // consulted rather than that relief is refused unconditionally.
        val d = dispatcher(relief = Availability.Available)
        val spec = CommandSpec("someday", 1, 2, Surface.SUBSYSTEM, isRelief = true)
        assertEquals(Availability.Available, d.availabilityOf(spec))
    }

    @Test
    fun `dispatch refuses before execute whenever availabilityOf does`() {
        // What the deleted half of the relief test used to assert, stated
        // against the mechanism rather than against a relief command. dispatch
        // routes every gate through availabilityOf, so anything that fails
        // there cannot reach the handler, relief included.
        val d = dispatcher(subsystem = Availability.Unavailable(reasonKey = 9))
        val r = d.dispatch(Command.Bedtime)
        assertEquals(9, (r as DispatchResult.Unavailable).reasonKey)
        assertTrue("a refused command must not reach the handler", ranged.isEmpty())
    }

    @Test
    fun `the relief gate applies only to relief commands`() {
        val d = dispatcher(relief = Availability.Unavailable(reasonKey = 7))
        assertTrue(d.dispatch(Command.Block("ig", 60_000)) is DispatchResult.Confirmed)
        assertTrue(d.dispatch(Command.Focus(60_000)) is DispatchResult.Confirmed)
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
    fun `focus needs confirming too`() {
        assertTrue(
            dispatcher().dispatch(Command.Focus(30 * day)) is DispatchResult.NeedsConfirmation,
        )
        // The other half of this named "allow", which never needed confirming
        // because a lease is bounded relief rather than an unfixable lock.
        // Timer stands in: it also carries a duration and also arms nothing
        // that LockRegistry will hold, which is the property that decided it.
        assertTrue(dispatcher().dispatch(Command.Timer(30 * day)) is DispatchResult.Confirmed)
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
