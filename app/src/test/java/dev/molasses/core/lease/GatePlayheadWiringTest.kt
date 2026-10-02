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

    @Test
    fun `the playhead reads the same remaining value that drives the numeral`() {
        val numeral = gate.indexOf("text = fields.countdown,")
        val call = gate.indexOf("Playhead(remainingSec = fields.remainingSec, totalSec = playheadTotalSec)")
        assertTrue("the numeral is drawn from fields", numeral >= 0)
        assertTrue("directly under the numeral, in the same branch", call > numeral)
        // Both from one Fields, built once per tick by GateReadout.fields.
        val countdownBranch = gate.substring(gate.indexOf("} else {", gate.indexOf("if (controls.leases) {")))
        assertTrue(countdownBranch.indexOf("Playhead(") < countdownBranch.indexOf("\n            }\n"))
        assertTrue(playhead.contains("GatePlayhead.playhead(remainingSec, totalSec)"))
        // The track's length is the gate's own countdown, from the first frame.
        assertTrue(gate.contains("val playheadTotalSec = remember { fields.remainingSec }"))
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
    fun `the overlay managers and the walking gate are untouched by it`() {
        for (path in listOf(
            "app/src/main/java/dev/molasses/overlay/LeaseGateOverlayManager.kt",
            "app/src/main/java/dev/molasses/overlay/GateOverlayManager.kt",
            "app/src/main/java/dev/molasses/overlay/OverlayHost.kt",
            "app/src/main/java/dev/molasses/ui/gate/GateScreen.kt",
        )) {
            assertFalse(path, repoFile(path).readText().contains("Playhead"))
        }
        assertEquals(1, Regex("""GatePlayhead\.playhead\(""").findAll(screen).count())
    }
}
