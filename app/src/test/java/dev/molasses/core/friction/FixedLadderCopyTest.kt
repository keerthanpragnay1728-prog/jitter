package dev.molasses.core.friction

import dev.molasses.core.repoFile
import dev.molasses.core.safety.PauseWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixed 5/10/15/20 minute ladder is gone from everything a user sees.
 *
 * It outlived the curve that replaced it in four shipped places: CFG's LADDER
 * section, the walking gate's "Tier N, M minutes used", the ledger's TIER
 * field and the debug screen's stall column. Each looked like a description
 * of the app and none was one. This pins the copy and the call sites.
 */
class FixedLadderCopyTest {

    private val strings = repoFile("app/src/main/res/values/strings.xml").readText()

    /** Each `<string name="...">value</string>`, as name to value. */
    private fun values(): Map<String, String> =
        Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(strings).associate { it.groupValues[1] to it.groupValues[2] }

    /** 5, 10, 15 or 20 minutes, in digits or words, and the old "0-5" band. */
    private val boundary = Regex(
        """(?i)\b(5|10|15|20)\s*(-|to)?\s*(m|min|mins|minutes?)\b|\b(five|ten|fifteen|twenty)[\s-]+(minutes?|min)\b|\b0-5\b""",
    )

    /**
     * Minutes that are not friction boundaries, with the constant each one is
     * true of. A new entry needs a reason as specific as these.
     */
    private val notBoundaries = mapOf(
        // The pause is relief, fifteen minutes long, and not a point on any
        // friction curve.
        "settings_pause_start" to PauseWindow.DURATION_MS,
        "settings_pause_hint" to PauseWindow.DURATION_MS,
    )

    @Test
    fun `no user-visible string hardcodes 5, 10, 15 or 20 minutes as a friction boundary`() {
        val hits = values().filter { (name, value) -> name !in notBoundaries && boundary.containsMatchIn(value) }
        assertTrue("strings naming a fixed friction boundary: ${hits.keys}", hits.isEmpty())
    }

    @Test
    fun `the allowed minutes are what their constant says`() {
        assertEquals(15L * 60_000, PauseWindow.DURATION_MS)
        for ((name, _) in notBoundaries) assertTrue("$name no longer exists", name in values())
    }

    @Test
    fun `the tier and ladder strings are gone`() {
        for (name in listOf(
            "gate_tier", "gate_tier_terminal", "gate_why_next",
            "settings_ladder_normal", "settings_ladder_terminal", "settings_ladder_tier", "settings_ladder_note",
            "debug_tier_terminal",
        )) {
            assertFalse("$name is back", name in values())
        }
        assertFalse(values().getValue("ledger_cycle_fmt").contains("TIER"))
        assertFalse(values().getValue("ledger_cycle_penalty_fmt").contains("TIER"))
    }

    @Test
    fun `no screen reads TierPolicy`() {
        for (path in listOf(
            "app/src/main/java/dev/molasses/ui/gate/GateScreen.kt",
            "app/src/main/java/dev/molasses/ui/settings/SettingsScreen.kt",
            "app/src/main/java/dev/molasses/ui/launcher/LauncherActivity.kt",
            "app/src/main/java/dev/molasses/core/ui/CycleLine.kt",
            "app/src/main/java/dev/molasses/overlay/GateOverlayManager.kt",
        )) {
            assertFalse("$path reads TierPolicy", repoFile(path).readText().contains("TierPolicy"))
        }
        // The debug screen keeps one use: deriving an index in the state
        // editor. It must not show a ladder stall or terminal rung again.
        val debug = repoFile("app/src/main/java/dev/molasses/ui/settings/DebugScreen.kt").readText()
        assertEquals(1, Regex("""TierPolicy\.""").findAll(debug).count())
        assertTrue(debug.contains("TierPolicy.indexFor(ms)"))
    }

    @Test
    fun `the walking gate shows the engine's own reading, so its label agrees with isTerminal`() {
        val engine = repoFile("app/src/main/java/dev/molasses/engine/FrictionEngine.kt").readText()
        assertTrue(engine.contains("fun isTerminal(pkg: String, nowMs: Long): Boolean = horizonReading(pkg, nowMs).terminal"))
        val service = repoFile("app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt").readText()
        assertEquals(2, Regex("""gate\.show\(pkg, engine\.horizonReading\(pkg, now\(\)\), (true|false)\)""").findAll(service).count())
        assertFalse(service.contains("perApp[pkg]?.tierIndex"))
        val gate = repoFile("app/src/main/java/dev/molasses/ui/gate/GateScreen.kt").readText()
        assertTrue(gate.contains("horizonLabel(reading.terminal)"))
    }
}
