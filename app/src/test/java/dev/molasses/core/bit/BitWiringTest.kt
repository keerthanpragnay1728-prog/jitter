package dev.molasses.core.bit

import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between Bit's pure logic and its host, asserted as text.
 *
 * ## Why this file exists
 * Two of Bit's three shipped defects were host-side with the pure layer
 * already correct, and the third was a pure rule with a clause the host
 * could not clear. All three have the same tell: `BitTap` answers a
 * question, `BitDock` answers a different one, and the host is the only
 * place they meet. Nothing that compiles here can see that meeting, because
 * `LauncherActivity.kt` is one of the files no tool in this environment
 * compiles.
 *
 * So the meeting is asserted the way `FontScaleWiringTest` asserts its call
 * sites, `AudioFocusWiringTest` asserts its take and release positions and
 * `LeaseGrantVisibilityTest` asserts its seed position: read the source,
 * slice out the function, and check both that the call exists and that it is
 * in the right place relative to the others.
 *
 * ## What would compile and be wrong
 * 1. **The idle clock written before the branch.** That was the first bug:
 *    the tap that opened the readout also un-docked Bit, so steps two and
 *    three were unreachable. Moving one statement up restores it exactly.
 * 2. **The undock write dropped.** That is the shape the second bug would
 *    take: `Action.undocks` is read by the host and by nothing else, so a
 *    handler that only writes on the poke branch leaves the readout with no
 *    exit and no face ever returns.
 * 3. **A captured Boolean.** Passing the composition's `docked` into the tap
 *    lambda instead of re-deriving it reads whatever the composition that
 *    built that lambda saw. It type-checks, and the tap decision is the one
 *    place in this app where a stale answer costs the whole gesture.
 * 4. **A second dock derivation.** Two places computing "is Bit retreated"
 *    is two places to keep in step, and the one that decides the tap would
 *    be the one nobody looks at.
 */
class BitWiringTest {

    private val host: String by lazy {
        repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherActivity.kt").readText()
    }

    /** The body of the `onTap` lambda the host hands to `BitCompanion`. */
    private val tapHandler: String by lazy {
        val start = host.indexOf("onTap = { taps ->")
        assertTrue("the host must hand BitCompanion an onTap lambda", start >= 0)
        val end = host.indexOf("onInteract", start).let { if (it >= 0) it else host.length }
        host.substring(start, minOf(end, start + 2_000))
    }

    @Test
    fun `dock state is derived in one place and re-derived at the tap`() {
        assertEquals(
            "BitDock.isDocked belongs at exactly one call site in the host",
            1,
            Regex("BitDock\\.isDocked\\(").findAll(host).count(),
        )
        assertTrue(
            "the host must expose the derivation as a function the tap can call",
            host.contains("fun dockedNow(): Boolean = BitDock.isDocked("),
        )
    }

    @Test
    fun `the tap re-derives rather than reading a captured value`() {
        // The whole point. A lambda handed to a gesture detector holds the
        // values of the composition that built it, and which composition that
        // is depends on when the pointer input was last restarted. Calling
        // the derivation removes the question instead of answering it.
        assertTrue(
            "the tap must call BitTap.onTap with freshly derived arguments",
            tapHandler.contains("BitTap.onTap(dockedNow(), hudStepNow(), taps)"),
        )
    }

    @Test
    fun `the idle clock is written after the branch, never before`() {
        // The first Bit bug, exactly. Writing it first un-docked Bit on the
        // tap that opened the readout, so the next tap took the other branch
        // and two thirds of the readout was unreachable.
        val branch = tapHandler.indexOf("BitTap.onTap(")
        val write = tapHandler.indexOf("if (action.undocks) lastBitTouchMs")
        assertTrue("the tap handler must write the clock on undocks", write >= 0)
        assertTrue("the branch must be chosen before the clock is written", branch in 0 until write)
        assertTrue(
            "both action branches must be handled before the write",
            tapHandler.indexOf("is BitTap.Action.React ->") in 0 until write,
        )
    }

    @Test
    fun `nothing else moves the idle clock`() {
        // The third thing to check when Bit will not come back out. A write
        // on resume or on recomposition would look identical to a premature
        // retreat and would make IDLE_MS the obvious suspect, which it is
        // not.
        val writes = Regex("lastBitTouchMs = ").findAll(host).count()
        assertEquals(
            "exactly two writers: the drag-start callback and the undock branch",
            2,
            writes,
        )
        assertTrue(
            "the drag-start callback is one of them",
            host.contains("onInteract = { lastBitTouchMs = SystemClock.elapsedRealtime() }"),
        )
        assertEquals(
            "and one seed, at composition rather than at zero",
            1,
            Regex("var lastBitTouchMs by remember").findAll(host).count(),
        )
    }

    @Test
    fun `the tap decision has one call site`() {
        // A second one would be a second grammar for the same gesture, and
        // the readout's exit would depend on which one ran.
        assertEquals(1, Regex("BitTap\\.onTap\\(").findAll(host).count())
    }

    @Test
    fun `the keystroke clock reaches the dock decision`() {
        // The clause that made the readout a trap on a prompt with text. If
        // this argument goes away the text retreat becomes unconditional
        // again and no tap can clear it.
        assertTrue(
            "the dock derivation must be handed the keystroke clock",
            host.contains("msSinceKeystroke = SystemClock.elapsedRealtime() - lastKeystrokeMs"),
        )
    }
    // ------------------------------------------------ the answer's lifetime

    @Test
    fun `an answer is cleared by each of the three things that end it`() {
        // Each of these is the user moving on, and a missing one is an answer
        // that outlives its moment on a surface whose argument is that nothing
        // sits on it without earning the space.
        val edit = host.substring(host.indexOf("onValueChange = edit@{ edited ->")).substringBefore("textStyle =")
        assertTrue("the next keystroke clears it", edit.contains("clearAnswer()\n                        lastKeystrokeMs ="))
        assertTrue(
            "a selection or composing change is not a keystroke",
            edit.indexOf("if (next == query) return@edit") in 0 until edit.indexOf("clearAnswer()"),
        )
        assertTrue(
            "a new dispatch clears it, before the branch that may set one",
            host.contains("clearAnswer()\n                            val outcome = submit()"),
        )
        assertTrue(
            "leaving the launcher clears it",
            host.substringAfter("fun clearPrompt() {").substringBefore("}").contains("clearAnswer()"),
        )
    }

    @Test
    fun `only a reading-window acknowledgement is on a clock, and only the reminder's is one`() {
        // Content the user asked for waits for them. An acknowledgement of
        // something they just did goes by itself once read.
        val declaration = host.indexOf("var answer by remember")
        assertTrue("the host must hold the answer as state", declaration >= 0)
        assertTrue("an answer must not be handed to the reaction ladder", !host.contains("Reaction.Answer("))
        val clock = host.substring(host.indexOf("LaunchedEffect(answerTimer.serial) {")).substringBefore("\n    }\n")
        assertTrue(clock.contains("val hold = started.holdMs ?: return@LaunchedEffect"))
        assertTrue(clock.contains("delay(hold)"))
        val gate = clock.indexOf("if (AnswerTimer.mayExpire(answerTimer, started.serial)) {")
        assertTrue("only its own answer, and only then the expired line", gate in 0 until clock.indexOf("answer expired after"))
        assertTrue(gate < clock.indexOf("clearAnswer()"))
        val answered = host.substring(host.indexOf("is DispatchResult.Answered -> {")).substringBefore("is DispatchResult.Unavailable")
        assertTrue(answered.contains("answerTimer = AnswerTimer.shown("))
        assertTrue(answered.contains("holdMs = if (outcome.readingWindow) ReadingWindow.holdMs(text) else null,"))
        val dispatch = repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherDispatch.kt").readText()
        assertEquals(1, Regex("""readingWindow = true""").findAll(dispatch).count())
        val rem = dispatch.substring(dispatch.indexOf("is Command.Rem -> DispatchResult.Deferred"))
        assertTrue(rem.substringBefore("Command.RemList ->").contains("readingWindow = true"))
    }

    @Test
    fun `every clear retires the clock, because every clear is clearAnswer`() {
        assertEquals("answer = null is written in one place", 1, Regex("""\banswer = null\b""").findAll(host).count())
        val clear = host.substring(host.indexOf("fun clearAnswer() {")).substringBefore("\n    }\n")
        assertTrue(clear.contains("answer = null") && clear.contains("answerTimer = AnswerTimer.cleared(answerTimer)"))
        assertTrue("declared before the clock that calls it", host.indexOf("fun clearAnswer() {") < host.indexOf("LaunchedEffect(answerTimer.serial) {"))
    }

    @Test
    fun `a deferred answer lands with the cleared prompt in one frame, and Enter waits for it`() {
        val deferred = host.substring(host.indexOf("is DispatchResult.Deferred -> {")).substringBefore("\n            }\n")
        assertFalse("clearing here drew an empty frame before the answer", deferred.contains("query = \"\""))
        assertTrue(deferred.contains("awaitingDeferred = true") && deferred.contains("awaitingDeferred = false"))
        assertTrue(host.contains("if (awaitingDeferred) return@go"))
    }

    @Test
    fun `a deferred answer that never lands times out and releases Enter`() {
        val deferred = host.substring(host.indexOf("is DispatchResult.Deferred -> {")).substringBefore("\n        }\n    }\n")
        assertTrue(deferred.contains("DeferredWait.start("))
        assertTrue(deferred.contains("timedOut = DispatchResult.Failed(R.string.cmd_err_deferred_timeout)"))
        assertFalse("not the raw await, which has no deadline", deferred.contains("outcome.await {"))
        val release = deferred.indexOf("awaitingDeferred = false")
        assertTrue("Enter is released on either result, before it is handled", release in 0 until deferred.indexOf("handleOutcome(result)"))
    }

    @Test
    fun `the prompt is a command line, not prose`() {
        val options = host.substring(host.indexOf("keyboardOptions = KeyboardOptions(")).substringBefore("keyboardActions")
        assertTrue(options.contains("autoCorrectEnabled = false"))
        assertTrue(options.contains("capitalization = KeyboardCapitalization.None"))
        assertTrue("an outside change drops the composing region", host.contains("TextFieldValue(query, TextRange(query.length))"))
    }

    @Test
    fun `the answer reaches the one precedence table`() {
        // Not rendered off to the side. It ranks with the prompt, the readout
        // and the notice or it is a second display path.
        assertTrue(host.contains("answer = answer,"))
        assertEquals(1, Regex("BitDisplay\\.resolve\\(").findAll(host).count())
    }

    @Test
    fun `both spoken variants blank Bit's usual slot`() {
        // One Bit, in the speech row, rather than a face in its usual row and
        // a second one below it. A check narrowed back to Speech would draw
        // two Bits whenever an answer was up.
        assertTrue(host.contains("if (display is BitDisplay.Spoken) {"))
        assertTrue(host.contains("val spoken = display as? BitDisplay.Spoken"))
    }

}
