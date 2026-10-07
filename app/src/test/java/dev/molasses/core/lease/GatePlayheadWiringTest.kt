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
    fun `the playhead is first and the numeral under it, from the same remaining value`() {
        val call = gate.indexOf("Playhead(\n                    remainingSec = fields.remainingSec,\n                    totalSec = countdownTotalSec,")
        val numeral = gate.indexOf("text = fields.countdown,")
        assertTrue("the playhead is drawn from fields", call >= 0)
        assertTrue("the numeral is under it, in the same branch", numeral > call)
        // Both from one Fields, built once per tick by GateReadout.fields.
        val countdownBranch = gate.substring(gate.indexOf("} else {", gate.indexOf("if (controls.leases) {")))
        assertTrue(countdownBranch.indexOf("Playhead(") < countdownBranch.indexOf("text = fields.countdown,"))
        assertTrue(countdownBranch.indexOf("text = fields.countdown,") < countdownBranch.indexOf("\n            }\n"))
        assertTrue(playhead.contains("GatePlayhead.playhead(remainingSec, totalSec)"))
        // Both centred.
        val numeralBlock = countdownBranch.substring(countdownBranch.indexOf("text = fields.countdown,")).substringBefore(")")
        assertTrue(numeralBlock.contains("textAlign = TextAlign.Center,"))
        assertTrue(playhead.contains("textAlign = TextAlign.Center,"))
    }

    @Test
    fun `the track is sized once from the gate's width, never wraps, and the numeral is about half its old size`() {
        assertTrue(gate.contains("BoxWithConstraints("))
        assertTrue(gate.contains("val gateWidth = maxWidth"))
        assertTrue(gate.contains("val playheadSp = remember(gateWidth, countdownTotalSec, systemFontScale) {"))
        assertTrue(gate.contains("GatePlayhead.fontSizeSp(gateWidth.value, countdownTotalSec + 1, systemFontScale)"))
        assertTrue(gate.contains("val systemFontScale = LocalDensity.current.fontScale"))
        assertTrue(gate.contains("width = gateWidth - (GatePlayhead.GUTTER_DP * 2).dp,"))
        assertTrue(playhead.contains("fontSize = fontSizeSp.sp,"))
        assertTrue(playhead.contains(".requiredWidth(width)"))
        assertTrue(playhead.contains("maxLines = 1,"))
        assertTrue(playhead.contains("softWrap = false,"))
        // The numeral: about half of 44 sp, and larger than the track's cap
        // and every other text on the gate but Bit's face.
        val numeralSp = Regex("""text = fields\.countdown,[\s\S]*?fontSize = (\d+)\.sp""").find(gate)!!.groupValues[1].toInt()
        assertEquals(24, numeralSp)
        assertTrue(numeralSp > GatePlayhead.MAX_SP)
        val others = Regex("""fontSize = (\d+)\.sp""").findAll(screen).map { it.groupValues[1].toInt() }.toList()
        val face = Regex("""text = fields\.face,[\s\S]*?fontSize = (\d+)\.sp""").find(gate)!!.groupValues[1].toInt()
        assertEquals("the largest but the face", numeralSp, (others - face).maxOrNull())
    }

    @Test
    fun `TalkBack reads the numeral, not the track`() {
        val countdownBranch = gate.substring(gate.indexOf("} else {", gate.indexOf("if (controls.leases) {")))
        val numeralBlock = countdownBranch.substring(countdownBranch.indexOf("text = fields.countdown,")).substringBefore("\n                )")
        assertFalse(numeralBlock.contains("clearAndSetSemantics"))
        assertFalse(numeralBlock.contains("semantics"))
        assertTrue(playhead.contains(".clearAndSetSemantics { },"))
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
        assertTrue(playhead.contains(".clearAndSetSemantics { },"))
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
