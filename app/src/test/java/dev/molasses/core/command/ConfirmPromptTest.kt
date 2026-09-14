package dev.molasses.core.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second Enter on a long lock.
 *
 * These read as trivial, and that is the point: the alternative is the same
 * three rules spread across a Compose callback where they cannot be run.
 */
class ConfirmPromptTest {

    private val month = 30L * 24 * 60 * 60 * 1000
    private val command = Command.Block("instagram", month)
    private val echo = CommandRender.render(command)

    // ------------------------------------------------------------- pending

    @Test
    fun `arming holds the command and the canonical echo`() {
        val pending = ConfirmPrompt.arm(command, echo)
        assertEquals(command, pending.command)
        assertEquals("block instagram 30d", pending.line)
    }

    @Test
    fun `nothing pending means dispatch afresh`() {
        assertSame(
            ConfirmPrompt.Decision.Dispatch,
            ConfirmPrompt.onSubmit(null, "block instagram 30d"),
        )
    }

    // ------------------------------------------------------------- confirm

    @Test
    fun `a second Enter on the armed line confirms`() {
        val pending = ConfirmPrompt.arm(command, echo)
        val decision = ConfirmPrompt.onSubmit(pending, echo)
        assertTrue(decision is ConfirmPrompt.Decision.Confirm)
        assertEquals(command, (decision as ConfirmPrompt.Decision.Confirm).command)
    }

    @Test
    fun `surrounding whitespace still confirms`() {
        // The prompt trims before parsing, so it must trim before comparing
        // too, or a stray space silently turns a confirmation into a fresh
        // dispatch of the same line and the lock arms without a second Enter.
        val pending = ConfirmPrompt.arm(command, echo)
        assertTrue(ConfirmPrompt.onSubmit(pending, "  $echo ") is ConfirmPrompt.Decision.Confirm)
    }

    // -------------------------------------------------------------- cancel

    @Test
    fun `editing the line cancels`() {
        val pending = ConfirmPrompt.arm(command, echo)
        assertNull(ConfirmPrompt.onTextChanged(pending, "block instagram 3d"))
    }

    @Test
    fun `a single keystroke is enough to cancel`() {
        val pending = ConfirmPrompt.arm(command, echo)
        assertNull(ConfirmPrompt.onTextChanged(pending, echo + "x"))
        assertNull(ConfirmPrompt.onTextChanged(pending, echo.dropLast(1)))
    }

    @Test
    fun `typing the same text back does not cancel`() {
        val pending = ConfirmPrompt.arm(command, echo)
        assertSame(pending, ConfirmPrompt.onTextChanged(pending, echo))
    }

    @Test
    fun `an Enter on a different line dispatches rather than confirming`() {
        // Belt and braces. onTextChanged should already have cleared it, but
        // a confirmation that can fire on a line the user did not agree to is
        // the one failure here that costs a month.
        val pending = ConfirmPrompt.arm(command, echo)
        assertSame(
            ConfirmPrompt.Decision.Dispatch,
            ConfirmPrompt.onSubmit(pending, "block instagram 3d"),
        )
    }

    @Test
    fun `an unrelated command typed over the top is not confirmed`() {
        val pending = ConfirmPrompt.arm(command, echo)
        assertSame(ConfirmPrompt.Decision.Dispatch, ConfirmPrompt.onSubmit(pending, "status"))
    }

    @Test
    fun `clearing the field cancels`() {
        val pending = ConfirmPrompt.arm(command, echo)
        assertNull(ConfirmPrompt.onTextChanged(pending, ""))
    }

    // ------------------------------------------------------- the round trip

    @Test
    fun `the echo reparses to the command it confirms`() {
        // Without this the user confirms one thing and arms another. The
        // render round trip is asserted in CommandRenderTest; this pins that
        // the confirmation path is the same round trip.
        for (c in listOf<Command>(
            Command.Block("instagram", month),
            Command.Focus(month),
            Command.Block("youtube", 25L * 60 * 60 * 1000),
        )) {
            val pending = ConfirmPrompt.arm(c, CommandRender.render(c))
            val reparsed = CommandParser.parse(pending.line)
            assertEquals("echo did not reparse: ${pending.line}", ParseResult.Ok(c), reparsed)
            assertTrue(ConfirmPrompt.onSubmit(pending, pending.line) is ConfirmPrompt.Decision.Confirm)
        }
    }
}
