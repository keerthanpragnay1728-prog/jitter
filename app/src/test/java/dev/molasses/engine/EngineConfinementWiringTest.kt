package dev.molasses.engine

import dev.molasses.core.functionBody
import dev.molasses.core.insideOpenBlock
import dev.molasses.core.repoFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service keeps [FrictionEngine] on the main thread.
 *
 * The engine's map is unsynchronised, and it was written from the
 * accessibility callback on main and from `Dispatchers.Default` (the
 * checkpoint tick, the settings observer, the connect coroutine). The fix is
 * confinement: every call made from a coroutine hops to main first. The
 * service is compiled by nothing here, so the hops are asserted as text, and
 * the debug-build assertion catches anything this misses at runtime.
 */
class EngineConfinementWiringTest {

    private val text by lazy {
        repoFile("app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt").readText()
    }

    private fun assertOnMain(body: String, call: String, where: String) {
        val at = body.indexOf(call)
        assertTrue("$where no longer contains $call", at >= 0)
        assertTrue(
            "$call in $where is not inside a hop to Dispatchers.Main.immediate",
            insideOpenBlock(body, at, "withContext(Dispatchers.Main.immediate) {"),
        )
    }

    @Test
    fun `the checkpoint tick calls the engine on main`() {
        assertOnMain(functionBody(text, "private fun startCheckpointing()"), "engine.checkpoint(", "startCheckpointing")
    }

    @Test
    fun `the settings observer calls the engine on main`() {
        val body = functionBody(text, "private fun observeSettings()")
        assertOnMain(body, "engine.setHorizon(", "observeSettings")
        assertOnMain(body, "buildEngine(", "observeSettings")
    }

    @Test
    fun `the engine is built on main at connect, before ready`() {
        val body = functionBody(text, "override fun onServiceConnected()")
        assertOnMain(body, "buildEngine(outcome.snapshot", "onServiceConnected")
        val build = body.indexOf("buildEngine(outcome.snapshot")
        val ready = body.indexOf("ready = true")
        assertTrue("ready must be set after the engine exists", build in 0 until ready)
    }

    @Test
    fun `the engine is given the debug main-thread assertion`() {
        assertTrue(text.contains("confinement = engineConfinement,"))
        assertTrue(
            "the assertion must be debug only",
            Regex("""engineConfinement: \(\) -> Unit =\s*if \(BuildConfig\.DEBUG\)""").containsMatchIn(text),
        )
        assertTrue(text.contains("Looper.getMainLooper().isCurrentThread"))
    }
}
