package dev.molasses.debug

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The debug entry points must not exist in a release variant.
 *
 * They are split by source set rather than guarded with `if (BuildConfig.DEBUG)`,
 * so their absence is structural: the release variant compiles a different
 * file. This test checks that the split is intact and that nothing in `main`
 * reaches around it.
 *
 * What this does not do is inspect an assembled release APK. That needs
 * `assembleRelease`, which cannot run here because the Android toolchain is
 * unreachable. This is a source-level guarantee, and it is the strongest one
 * available without a compiler.
 */
class DebugSurfaceTest {

    private fun repoRoot(): File? = listOf(File("."), File(".."), File("/home/user/visceral"))
        .firstOrNull { File(it, "app/src/main/java/dev/molasses").isDirectory }

    private fun read(root: File, path: String) = File(root, path).readText()

    private val debugPath = "app/src/debug/java/dev/molasses/debug/DebugSurface.kt"
    private val releasePath = "app/src/release/java/dev/molasses/debug/DebugSurface.kt"

    @Test
    fun `both variants supply the surface`() {
        val root = repoRoot()
        assumeTrue("repo root not locatable", root != null)
        assertTrue("missing $debugPath", File(root, debugPath).isFile)
        assertTrue("missing $releasePath", File(root, releasePath).isFile)
    }

    @Test
    fun `release disables the surface and debug enables it`() {
        val root = repoRoot()
        assumeTrue("repo root not locatable", root != null)
        assertTrue(
            "debug variant must enable the surface",
            read(root!!, debugPath).contains("ENABLED: Boolean = true"),
        )
        assertTrue(
            "release variant must disable the surface",
            read(root, releasePath).contains("ENABLED: Boolean = false"),
        )
    }

    @Test
    fun `the release gesture is a no-op and contains no pointer handling`() {
        val root = repoRoot()
        assumeTrue("repo root not locatable", root != null)
        val release = read(root!!, releasePath)
        for (forbidden in listOf("pointerInput", "awaitFirstDown", "withTimeoutOrNull", "awaitEachGesture")) {
            assertFalse(
                "release DebugSurface must not contain $forbidden",
                release.contains(forbidden),
            )
        }
        assertTrue(
            "release gesture must return the receiver unchanged",
            release.contains("debugBypassGesture(onBypass: () -> Unit): Modifier = this"),
        )
    }

    @Test
    fun `the two variants declare the same surface`() {
        // A signature drift would only show up as a release build failure,
        // which is the variant nobody compiles while iterating.
        val root = repoRoot()
        assumeTrue("repo root not locatable", root != null)
        fun symbols(text: String) = Regex("""\b(?:const val|fun)\s+(\w+)""")
            .findAll(text).map { it.groupValues[1] }.toSet()
        assertEquals(
            "debug and release DebugSurface must declare the same members",
            symbols(read(root!!, debugPath)),
            symbols(read(root, releasePath)),
        )
    }

    @Test
    fun `main never implements a bypass of its own`() {
        val root = repoRoot()
        assumeTrue("repo root not locatable", root != null)
        val main = File(root, "app/src/main/java/dev/molasses")
        val offenders = main.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { f ->
                val t = f.readText()
                // main may call through DebugSurface, never hand-roll a gesture.
                t.contains("awaitEachGesture") || t.contains("awaitFirstDown")
            }
            .map { it.name }
            .toList()
        assertTrue("main must not implement the bypass gesture: $offenders", offenders.isEmpty())
    }

    @Test
    fun `every debug-only entry point in main is gated on DebugSurface`() {
        val root = repoRoot()
        assumeTrue("repo root not locatable", root != null)

        // The gate bypass reaches the detector only through the composable
        // parameter, which is inert unless the debug gesture fires.
        val gate = read(root!!, "app/src/main/java/dev/molasses/ui/gate/GateScreen.kt")
        assertTrue(
            "the bypass must be routed through DebugSurface.debugBypassGesture",
            gate.contains("Modifier.debugBypassGesture(onDebugBypass)"),
        )

        // The state editor is a whole UI block and must sit behind the flag.
        val debugScreen = read(root, "app/src/main/java/dev/molasses/ui/settings/DebugScreen.kt")
        val editorIndex = debugScreen.indexOf("StateEditor(")
        assertTrue("state editor not found", editorIndex > 0)
        val guardIndex = debugScreen.indexOf("if (DebugSurface.ENABLED) {")
        assertTrue("state editor must be behind DebugSurface.ENABLED", guardIndex in 1 until editorIndex)
    }

    @Test
    fun `a bypassed gate is ledgered distinctly from a cleared one`() {
        // A capture from a device must never let a bypassed session be read
        // back as a pass.
        val root = repoRoot()
        assumeTrue("repo root not locatable", root != null)
        val manager = read(root!!, "app/src/main/java/dev/molasses/overlay/GateOverlayManager.kt")
        assertTrue(
            "a debug bypass must write GATE_BYPASSED_DEBUG",
            manager.contains("EventType.GATE_BYPASSED_DEBUG"),
        )
    }
}
