package dev.molasses.core.bit

import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
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
}
