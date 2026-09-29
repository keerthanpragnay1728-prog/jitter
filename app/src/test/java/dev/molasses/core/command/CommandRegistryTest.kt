package dev.molasses.core.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRegistryTest {

    private val registry = CommandRegistry(dummyCommandKeys())

    /** One instance of every Command variant. */
    private val allCommands: List<Command> = listOf(
        Command.Block("app", 60_000),
        Command.Focus(60_000),
        Command.Bedtime,
        Command.Status,
        Command.Help,
        Command.Alarm(6 * 60),
        Command.Timer(60_000),
        Command.Reboot,
        Command.PowerOff,
        Command.Wifi(null),
        Command.Dnd(null),
        Command.Calc("2+2"),
        Command.Conv("5 km mi"),
        Command.Days("until 25 dec"),
        Command.Rem(dev.molasses.core.remind.ReminderBook.When.In(600_000), "text"),
        Command.RemList,
    )

    @Test
    fun `every Command variant has a registry entry`() {
        // This is the half that fails when a command is added without being
        // registered: verbOf is exhaustive, so a new variant breaks the build
        // there, and if someone adds the branch without adding the row,
        // specFor throws here.
        for (command in allCommands) {
            assertNotNull(
                "no registry entry for $command",
                registry.specFor(command),
            )
        }
    }

    @Test
    fun `every registry entry maps back to a Command variant`() {
        val registeredVerbs = registry.specs.map { it.verb }.toSet()
        val commandVerbs = allCommands.map { CommandRegistry.verbOf(it) }.toSet()
        assertEquals(
            "registry and Command have drifted",
            commandVerbs,
            registeredVerbs,
        )
    }

    @Test
    fun `the registry covers every verb the parser accepts`() {
        // Aliases resolve to their canonical verb, so sleep is covered by
        // bedtime rather than needing a row that could drift from it.
        for (verb in CommandParser.VERBS) {
            val parsed = CommandParser.parse(verbSample(verb))
            val command = (parsed as? ParseResult.Ok)?.command
                ?: error("sample for '$verb' did not parse: $parsed")
            assertNotNull("no spec for '$verb'", registry.specFor(command))
        }
    }

    @Test
    fun `verbs are unique`() {
        val verbs = registry.specs.map { it.verb }
        assertEquals(verbs.distinct(), verbs)
    }

    @Test
    fun `allow is the only relief command`() {
        // If this starts failing, a new command suspends friction and the
        // dispatcher's relief gate needs to be the reason it is safe.
        // No shipped spec is relief today. "allow" was the only one and it
        // is gone, so this asserts the absence rather than the membership.
        //
        // That is a branch waiting for a command, not a guard that cannot
        // fire, and the difference is that CommandDispatch.availabilityOf
        // still consults isRelief for any spec it is handed. DispatchTest
        // drives it with a synthetic spec, so the mechanism stays covered
        // while no command uses it.
        assertEquals(emptyList<String>(), registry.specs.filter { it.isRelief }.map { it.verb })
    }

    @Test
    fun `the lock commands require confirmation above a day`() {
        for (verb in listOf("block", "focus")) {
            assertEquals(
                "$verb should need confirming above a day",
                CommandRegistry.CONFIRM_ABOVE_MS,
                registry.specForVerb(verb)?.requiresConfirmAboveMs,
            )
        }
        // A ten minute lease is not a thing to confirm.
        assertEquals(null, registry.specForVerb("allow")?.requiresConfirmAboveMs)
    }

    @Test
    fun `surfaces are assigned as documented`() {
        fun surfaceOf(verb: String) = registry.specForVerb(verb)?.surface
        assertEquals(Surface.SUBSYSTEM, surfaceOf("block"))
        assertEquals(Surface.SUBSYSTEM, surfaceOf("focus"))
        assertEquals(Surface.SUBSYSTEM, surfaceOf("bedtime"))
        assertEquals(Surface.STATE, surfaceOf("status"))
        assertEquals(Surface.STATE, surfaceOf("help"))
        assertEquals(Surface.INTENT, surfaceOf("alarm"))
        assertEquals(Surface.INTENT, surfaceOf("timer"))
        assertEquals(Surface.INTENT, surfaceOf("wifi"))
        assertEquals(Surface.INTENT, surfaceOf("dnd"))
        assertEquals(Surface.SERVICE, surfaceOf("reboot"))
        assertEquals(Surface.SERVICE, surfaceOf("poweroff"))
    }

    @Test
    fun `durationOf reports exactly the commands that arm something`() {
        assertEquals(60_000L, CommandRegistry.durationOf(Command.Block("a", 60_000)))
        assertEquals(60_000L, CommandRegistry.durationOf(Command.Focus(60_000)))
        assertEquals(null, CommandRegistry.durationOf(Command.Status))
        assertEquals(null, CommandRegistry.durationOf(Command.Bedtime))
        assertEquals(null, CommandRegistry.durationOf(Command.Alarm(0)))
    }

    @Test
    fun `sleep resolves to the bedtime row rather than its own`() {
        val sleep = (CommandParser.parse("sleep") as ParseResult.Ok).command
        assertEquals("bedtime", CommandRegistry.verbOf(sleep))
    }

    @Test
    fun `usage keys are distinct so help cannot show one command twice`() {
        val usages = registry.specs.map { it.usageKey }
        assertEquals(usages.distinct(), usages)
        val descriptions = registry.specs.map { it.descriptionKey }
        assertEquals(descriptions.distinct(), descriptions)
    }

    @Test
    fun `parser usage strings still contain no concrete values`() {
        // Carried forward deliberately: autocomplete must structurally never
        // be able to offer an argument value.
        for (verb in CommandParser.VERBS) {
            val usage = CommandParser.USAGE.getValue(verb)
            assertTrue("$verb usage has a digit: $usage", usage.none { it.isDigit() })
        }
    }

    /** A minimal line that parses, per verb. */
    private fun verbSample(verb: String): String = when (verb) {
        "block", "allow" -> "$verb app 30m"
        "focus", "timer" -> "$verb 30m"
        "alarm" -> "alarm 6am"
        "calc" -> "calc 2+2"
        "conv" -> "conv 5 km mi"
        "days" -> "days until 25 dec"
        "rem" -> "rem 10m text"
        "log" -> "log"
        else -> verb
    }
}
