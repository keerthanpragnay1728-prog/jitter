package dev.molasses.core.config

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every exit of the accessibility service takes every overlay down.
 *
 * ## Why this is asserted as text
 * The service is in the set nothing here compiles, and the fault this guards
 * is a list with an item missing, which compiles and runs. Teardown's list had
 * dropped the lease gate, leaving a focusable full-screen window and its audio
 * focus behind a dead service; onInterrupt's had dropped the lock overlay.
 *
 * So there is one list, `tearDownOverlays`, and this test holds it against the
 * manager fields rather than against a list of its own: a new overlay manager
 * added to the service fails here until it is added to the teardown.
 */
class OverlayTeardownWiringTest {

    private val service = "app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt"

    private fun text() = repoFile(service).readText()

    /** Every `lateinit var x: SomethingOverlayManager` the service declares. */
    private fun overlayFields(text: String): List<String> =
        Regex("""lateinit var (\w+): \w*OverlayManager\b""").findAll(text).map { it.groupValues[1] }.toList()

    @Test
    fun `the service owns the four overlays this test knows about`() {
        // A change here is a new window. Add it to tearDownOverlays and to
        // this list together.
        assertEquals(
            listOf("shutter", "gate", "lockOverlay", "leaseGate"),
            overlayFields(text()),
        )
    }

    @Test
    fun `tearDownOverlays dismisses every overlay the service owns`() {
        val text = text()
        val body = functionBody(text, "private fun tearDownOverlays(")
        for (field in overlayFields(text)) {
            assertTrue("tearDownOverlays never touches $field", body.contains("$field."))
            assertTrue(
                "$field must be checked for initialisation, so an exit before connect finished cannot throw",
                body.contains("::$field.isInitialized"),
            )
        }
        assertTrue("the shutter must be detached, not only released", body.contains("shutter.detach()"))
        assertTrue("the lease gate must be dismissed", body.contains("leaseGate.dismiss("))
        assertTrue("the lock overlay must be dismissed", body.contains("lockOverlay.dismiss("))
    }

    @Test
    fun `each overlay comes down in its own caught step`() {
        // One throwing removeView must not leave the next window up.
        val body = functionBody(text(), "private fun tearDownOverlays(")
        val steps = Regex("""overlayStep\(""").findAll(body).count()
        assertTrue("expected a separate step per overlay action, found $steps", steps >= 5)
        val helper = functionBody(text(), "private inline fun overlayStep(")
        assertTrue("overlayStep must catch", helper.contains("catch (e: Exception)"))
    }

    @Test
    fun `onInterrupt, onUnbind and onDestroy all reach tearDownOverlays`() {
        val text = text()
        assertTrue(
            "onInterrupt must go through tearDownOverlays",
            functionBody(text, "override fun onInterrupt()").contains("tearDownOverlays("),
        )
        assertTrue(
            "teardown must go through tearDownOverlays",
            functionBody(text, "private fun teardown()").contains("tearDownOverlays("),
        )
        for (exit in listOf("override fun onUnbind(", "override fun onDestroy()")) {
            assertTrue("$exit must call teardown", functionBody(text, exit).contains("teardown()"))
        }
    }

    @Test
    fun `teardown dismisses overlays before the scope that would run their callbacks is cancelled`() {
        val body = functionBody(text(), "private fun teardown()")
        val down = body.indexOf("tearDownOverlays(")
        val cancel = body.indexOf("scope.cancel()")
        assertTrue(down >= 0 && cancel >= 0)
        assertTrue("overlays must come down before scope.cancel()", down < cancel)
    }

    @Test
    fun `no exit path keeps its own list of overlays`() {
        // The bug was two lists that had drifted. Only tearDownOverlays may
        // name more than one overlay's dismiss.
        val text = text()
        for (exit in listOf("override fun onInterrupt()", "private fun teardown()")) {
            val body = functionBody(text, exit)
            assertTrue(
                "$exit dismisses an overlay directly instead of through tearDownOverlays",
                !body.contains("leaseGate.dismiss(") && !body.contains("lockOverlay.dismiss(") &&
                    !body.contains("shutter.detach("),
            )
        }
    }
}
