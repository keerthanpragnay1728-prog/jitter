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

    // ------------------------------------------------ the scroll proxy

    @Test
    fun `a content change in a target routes to the proxy, and ours is dropped`() {
        val targets = setOf(ig, yt)
        assertEquals(EventRoute.ContentChanged(yt), router.route(ev(yt, WindowEvent.Kind.CONTENT_CHANGED), targets))
        assertEquals(EventRoute.Ignore(IgnoreReason.OWN_PACKAGE), router.route(ev("dev.molasses", WindowEvent.Kind.CONTENT_CHANGED), targets))
        assertEquals(EventRoute.Ignore(IgnoreReason.NOT_A_TARGET), router.route(ev("com.x", WindowEvent.Kind.CONTENT_CHANGED), targets))
    }

    @Test
    fun `the proxy goes through the one scroll path, rate-limited, and never reads a node`() {
        val service = repoFile("app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt").readText()
        val proxy = functionBody(service, "private fun onContentChanged(")
        val qualifies = proxy.indexOf("ScrollProxy.qualifies(event.contentChangeTypes)")
        val gate = proxy.indexOf("ScrollProxy.mayPass(state, nowMs)")
        val scroll = proxy.indexOf("onScroll(pkg, event.eventTime, callbackEntryUptimeMs, viaProxy = true)")
        val stamp = proxy.indexOf("ScrollProxy.onPassed(state, nowMs, armedMs)")
        assertTrue(qualifies in 0 until gate && gate < scroll && scroll < stamp)
        for (forbidden in listOf("getSource", ".source", "AccessibilityNodeInfo", "rootInActiveWindow", ".text")) {
            assertTrue(forbidden, !proxy.contains(forbidden))
        }
        assertTrue(!service.contains("getSource(") && !service.contains("rootInActiveWindow"))
        // A real scroll switches it off for the session; leaving resets it.
        assertTrue(service.contains("scrollProxy[route.pkg] = ScrollProxy.onRealScroll("))
        assertTrue(functionBody(service, "private fun leaveTarget(").contains("scrollProxy.remove(pkg)"))
    }
}
