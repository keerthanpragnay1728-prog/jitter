package dev.molasses.core.lease

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playhead on the lease gate, as wired. Read as text: the screen
 * compiles nowhere here.
 */
class GatePlayheadWiringTest {

    private val screen by lazy { repoFile("app/src/main/java/dev/molasses/ui/gate/LeaseGateScreen.kt").readText() }
    private val gate by lazy { functionBody(screen, "fun LeaseGateScreen(") }
    private val playhead by lazy { functionBody(screen, "private fun Playhead(") }
    private val manager by lazy { repoFile("app/src/main/java/dev/molasses/overlay/LeaseGateOverlayManager.kt").readText() }

    @Test
    fun `the playhead reads the same remaining value that drives the numeral`() {
        val numeral = gate.indexOf("text = fields.countdown,")
        val call = gate.indexOf("Playhead(remainingSec = fields.remainingSec, totalSec = countdownTotalSec)")
        assertTrue("the numeral is drawn from fields", numeral >= 0)
        assertTrue("directly under the numeral, in the same branch", call > numeral)
        // Both from one Fields, built once per tick by GateReadout.fields.
        val countdownBranch = gate.substring(gate.indexOf("} else {", gate.indexOf("if (controls.leases) {")))
        assertTrue(countdownBranch.indexOf("Playhead(") < countdownBranch.indexOf("\n            }\n"))
        assertTrue(playhead.contains("GatePlayhead.playhead(remainingSec, totalSec)"))
    }

    @Test
    fun `the total is a parameter the manager passes, not derived by the screen`() {
        // The screen takes it and derives nothing.
        val params = screen.substring(screen.indexOf("fun LeaseGateScreen("), screen.indexOf(") {", screen.indexOf("fun LeaseGateScreen(")))
        assertTrue(params.contains("countdownTotalSec: Int,"))
        assertFalse("no first-frame derivation", screen.contains("remember {"))
        assertFalse(screen.contains("playheadTotalSec"))
        // The manager passes the total it starts the countdown with: the same
        // countdownMs that sets the deadline and the first remaining value.
        val show = functionBody(manager, "fun show(")
        assertTrue(show.contains("val deadline = monotonicMs() + countdownMs"))
        assertTrue(show.contains("var remaining by mutableLongStateOf(countdownMs)"))
        assertTrue(show.contains("val countdownTotalSec = GateReadout.remainingSeconds(countdownMs)"))
        assertTrue(show.contains("countdownTotalSec = countdownTotalSec,"))
        assertTrue("computed before the window is shown", show.indexOf("val countdownTotalSec") < show.indexOf("h.show("))
    }

    /** The screen's code with comments removed, so prose about animation does not count as animation. */
    private val code by lazy {
        screen.replace(Regex("""/\*[\s\S]*?\*/"""), "").lines().joinToString("\n") { it.substringBefore("//") }
    }

    @Test
    fun `nothing in the screen animates`() {
        val apis = Regex("""\banimate\w*\(|\bAnimatable\b|\bAnimatedVisibility\b|\bAnimatedContent\b|\bCrossfade\b|\bupdateTransition\b|\brememberInfiniteTransition\b|\btween\(|\bspring\(|\bkeyframes\b|Easing\b""")
        assertEquals(emptyList<String>(), apis.findAll(code).map { it.value }.toList())
        assertFalse("no animation import", Regex("""import androidx\.compose\.animation""").containsMatchIn(screen))
    }

    @Test
    fun `the track is display only, hidden from TalkBack, in existing tokens`() {
        for (input in listOf("clickable", "pointerInput", "onClick", "combinedClickable", "selectable", "toggleable")) {
            assertFalse(input, playhead.contains(input))
        }
        assertTrue(playhead.contains("modifier = Modifier.clearAndSetSemantics { },"))
        assertTrue(playhead.contains("color = PhosphorDim,"))
        assertTrue(playhead.contains("SpanStyle(color = PhosphorGreen)"))
        assertFalse(Regex("""0x[0-9A-Fa-f]{6,8}""").containsMatchIn(playhead))
        assertTrue(playhead.contains("fontFamily = FontFamily.Monospace,"))
        assertTrue(playhead.contains("textAlign = TextAlign.Center,"))
    }

    @Test
    fun `only the screen draws it, and the walking gate does not use it`() {
        // The lease gate manager passes a number and draws nothing.
        assertFalse(manager.contains("GatePlayhead"))
        assertFalse(manager.contains("Playhead("))
        for (path in listOf(
            "app/src/main/java/dev/molasses/overlay/GateOverlayManager.kt",
            "app/src/main/java/dev/molasses/overlay/OverlayHost.kt",
            "app/src/main/java/dev/molasses/ui/gate/GateScreen.kt",
        )) {
            assertFalse(path, repoFile(path).readText().contains("Playhead"))
        }
        assertEquals(1, Regex("""GatePlayhead\.playhead\(""").findAll(screen).count())
    }
}
