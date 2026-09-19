package dev.molasses.core.util

import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the three utilities are wired into the prompt, asserted as text.
 *
 * ## Why text
 * `LauncherDispatch.kt` and `LauncherActivity.kt` are two of the files
 * nothing in this environment compiles. The pure layer can say that
 * `Calc.evaluate` is right and that `percentNotes` is empty without a `%`;
 * it cannot say that the handler for `$ calc` calls `Calc` rather than
 * `Convert`, or that the note is attached conditionally rather than always.
 *
 * ## The three things that would compile and be wrong
 * 1. **A swapped handler.** `Command.Conv` evaluated by `Calc` type-checks:
 *    both take a `String` and both return a sealed result. The failure would
 *    be every conversion refusing with a calculator's error message.
 * 2. **An unconditional note.** Dropping the `if (notes.isEmpty())` compiles
 *    and turns the second line into an echo on every answer, which is the
 *    one thing this feature was explicitly not to do.
 * 3. **An answer routed through the reaction ladder.** `react(...)` takes a
 *    `String` and so does the answer, so sending it to `Reaction.Confirm`
 *    compiles and silently puts the answer back on a 1.6 second clock.
 *    Nothing on screen would look broken, it would just be unreadable.
 */
class UtilityWiringTest {

    private val dispatch: String by lazy {
        repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherDispatch.kt").readText()
    }

    private val activity: String by lazy {
        repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherActivity.kt").readText()
    }

    /** The body of `when (command)`'s branch for [variant], to its closing line. */
    private fun branch(variant: String): String {
        val start = dispatch.indexOf("is Command.$variant ->")
        assertTrue("no dispatch branch for Command.$variant", start >= 0)
        val next = listOf("is Command.", "\n}")
            .mapNotNull { marker ->
                dispatch.indexOf(marker, start + 1).takeIf { it >= 0 }
            }
            .minOrNull() ?: dispatch.length
        return dispatch.substring(start, next)
    }

    @Test
    fun `each verb is handed to its own utility`() {
        assertTrue("calc must evaluate through Calc", branch("Calc").contains("Calc.evaluate("))
        assertTrue("conv must parse through Convert", branch("Conv").contains("Convert.parse("))
        assertTrue("days must parse through DateMath", branch("Days").contains("DateMath.parse("))
    }

    @Test
    fun `no verb reaches a utility that is not its own`() {
        // The swap this exists for. Stated as an absence, because the
        // presence of the right call does not rule out the wrong one.
        assertTrue(!branch("Calc").contains("Convert.") && !branch("Calc").contains("DateMath."))
        assertTrue(!branch("Conv").contains("Calc.evaluate") && !branch("Conv").contains("DateMath."))
        assertTrue(!branch("Days").contains("Calc.evaluate") && !branch("Days").contains("Convert."))
    }

    @Test
    fun `the percent note is attached only when there is one`() {
        val calc = branch("Calc")
        assertTrue("percentNotes must be consulted", calc.contains("Calc.percentNotes("))
        assertTrue(
            "the note key must be conditional on notes being non-empty",
            calc.contains("if (notes.isEmpty()) null"),
        )
    }

    @Test
    fun `the date note is attached only when a date was resolved`() {
        val days = branch("Days")
        assertTrue(
            "the span note must be conditional on result.resolved",
            days.contains("if (result.resolved)"),
        )
    }

    @Test
    fun `a conversion carries no note at all`() {
        // Not an omission. Nothing in a conversion is ambiguous or
        // substituted: the user named both units and the answer names the
        // one it is in, so a second line would be the default echo.
        assertTrue(!branch("Conv").contains("noteKey"))
    }

    @Test
    fun `today is injected at the boundary and read nowhere else`() {
        assertTrue("the date handler must supply today", branch("Days").contains("LocalDate.now()"))
        assertEquals(
            "LocalDate.now() belongs at this one call site, not in core",
            1,
            Regex("LocalDate\\.now\\(\\)").findAll(dispatch).count(),
        )
    }

    @Test
    fun `an answer is held, not reacted to`() {
        val start = activity.indexOf("is DispatchResult.Answered ->")
        assertTrue("the prompt must handle Answered", start >= 0)
        val body = activity.substring(start, start + 400)
        assertTrue(
            "an answer must be held as console state, already rendered",
            body.contains("answer = outcome.message(context)"),
        )
        assertTrue(
            "an answer must not go through the reaction ladder, which expires on a clock",
            !body.contains("react("),
        )
    }
}
