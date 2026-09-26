package dev.molasses.core.lease

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service asks [GateHandover] both of its questions, in the right places.
 * Read as text because the service is compiled by nothing here.
 */
class GateHandoverWiringTest {

    private val text by lazy {
        repoFile("app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt").readText()
    }

    @Test
    fun `entering a target closes another target's session before opening its own`() {
        val body = functionBody(text, "private fun enterTarget(")
        val check = body.indexOf("GateHandover.mustCloseFirst(")
        val leave = body.indexOf("leaveTarget(")
        val open = body.indexOf("sessions.open(pkg)")
        assertTrue("enterTarget no longer asks mustCloseFirst", check >= 0)
        assertTrue("the other session must be left before this one opens", check < leave && leave < open)
    }

    @Test
    fun `a showing gate counts only when it is this package's`() {
        val body = functionBody(text, "private fun maybeLaunchGate(")
        assertFalse(
            "the any-gate-showing early return is the stand-in bug",
            body.contains("if (leaseGate.isShowing || gate.isShowing || lockOverlay.isShowing) return true"),
        )
        val handover = body.indexOf("GateHandover.forGate(")
        val decide = body.indexOf("LaunchGate.decide(")
        assertTrue("maybeLaunchGate must ask forGate before deciding", handover in 0 until decide)
        assertTrue("a foreign gate must be taken down", body.contains("dismissGateWindows("))
    }

    @Test
    fun `every full-screen manager reports whose window it is`() {
        for (m in listOf("LeaseGateOverlayManager", "GateOverlayManager", "LockOverlayManager")) {
            val src = repoFile("app/src/main/java/dev/molasses/overlay/$m.kt").readText()
            assertTrue("$m has no showingFor", src.contains("val showingFor: String? get() = if (isShowing) currentPkg else null"))
        }
    }
}
