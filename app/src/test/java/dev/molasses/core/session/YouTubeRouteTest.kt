package dev.molasses.core.session

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nothing between a YouTube scroll event and the arm decision treats it
 * differently from an Instagram one.
 */
class YouTubeRouteTest {

    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"
    private val router = ForegroundEventRouter(
        ownPackage = "dev.molasses",
        launcherClassName = ForegroundEventRouter.LAUNCHER_CLASS_NAME,
    )

    private fun ev(pkg: String, kind: WindowEvent.Kind) = WindowEvent(pkg, className = null, windowId = -1, kind = kind)

    @Test
    fun `a tracked YouTube scroll routes exactly as an Instagram one`() {
        val targets = setOf(ig, yt)
        for (kind in WindowEvent.Kind.entries) {
            val a = router.route(ev(ig, kind), targets)
            val b = router.route(ev(yt, kind), targets)
            assertEquals(kind.toString(), a::class, b::class)
        }
        assertEquals(EventRoute.Scroll(yt), router.route(ev(yt, WindowEvent.Kind.VIEW_SCROLLED), targets))
    }

    @Test
    fun `the service's scroll path has no per-package branch beyond the diagnostic log`() {
        val service = repoFile("app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt").readText()
        val onScroll = functionBody(service, "private fun onScroll(")
        // DIAG_PACKAGE decides only whether a line is logged.
        assertEquals(1, Regex("""DIAG_PACKAGE""").findAll(onScroll).count())
        assertTrue(onScroll.contains("val diag = pkg == DIAG_PACKAGE"))
        for (use in Regex("""\bdiag\b""").findAll(onScroll).map { onScroll.substring(it.range.first, it.range.first + 30) }) {
            assertTrue(use, use.startsWith("diag = ") || use.startsWith("diag) logScroll(") || use.startsWith("diag) {"))
        }
        assertTrue(onScroll.contains("val armed = shutter.arm("))
    }
}
