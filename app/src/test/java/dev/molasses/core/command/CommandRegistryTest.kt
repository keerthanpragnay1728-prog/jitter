package dev.molasses.core.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake resource ids. The registry never dereferences them. */
private fun keys() = CommandRegistry.Keys(
    blockUsage = 1, blockDesc = 2,
    focusUsage = 3, focusDesc = 4,
    allowUsage = 5, allowDesc = 6,
    bedtimeUsage = 7, bedtimeDesc = 8,
    statusUsage = 9, statusDesc = 10,
    helpUsage = 27, helpDesc = 28,
    logUsage = 11, logDesc = 12,
    alarmUsage = 13, alarmDesc = 14,
    timerUsage = 15, timerDesc = 16,
    remUsage = 17, remDesc = 18,
    rebootUsage = 19, rebootDesc = 20,
    poweroffUsage = 21, poweroffDesc = 22,
    wifiUsage = 23, wifiDesc = 24,
    dndUsage = 25, dndDesc = 26,
)

class CommandRegistryTest {

    private val registry = CommandRegistry(keys())

    /** One instance of every Command variant. */
    private val allCommands: List<Command> = listOf(
        Command.Block("app", 60_000),
        Command.Focus(60_000),
        Command.Allow("app", 60_000),
        Command.Bedtime,
        Command.Status,
        Command.Help,
        Command.Log(null),
        Command.Alarm(6 * 60),
        Command.Timer(60_000),
        Command.Remind(60_000, "x"),
        Command.Reboot,
        Command.PowerOff,
        Command.Wifi(null),
        Command.Dnd(null),
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
        assertEquals(listOf("allow"), registry.specs.filter { it.isRelief }.map { it.verb })
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
        assertEquals(Surface.SUBSYSTEM, surfaceOf("allow"))
        assertEquals(Surface.SUBSYSTEM, surfaceOf("bedtime"))
        assertEquals(Surface.SUBSYSTEM, surfaceOf("log"))
        assertEquals(Surface.SUBSYSTEM, surfaceOf("rem"))
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
        assertEquals(60_000L, CommandRegistry.durationOf(Command.Allow("a", 60_000)))
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
        "rem" -> "rem 10m text"
        "log" -> "log"
        else -> verb
    }
}
