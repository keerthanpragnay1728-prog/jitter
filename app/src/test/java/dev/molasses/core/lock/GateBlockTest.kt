package dev.molasses.core.lock

import dev.molasses.core.command.CommandRegistry
import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateBlockTest {

    private val hour = 60L * 60 * 1000

    @Test
    fun `the rungs are 1h, 4h, 12h and 1d, cut from the one ladder`() {
        assertEquals(listOf(1 * hour, 4 * hour, 12 * hour, 24 * hour), GateBlock.RUNGS_MS)
        assertTrue(LockLadder.STEPS_MS.containsAll(GateBlock.RUNGS_MS))
    }

    @Test
    fun `the cap is a day and never above the confirmation threshold`() {
        assertEquals(24 * hour, GateBlock.CAP_MS)
        assertTrue(GateBlock.CAP_MS <= CommandRegistry.CONFIRM_ABOVE_MS)
    }

    @Test
    fun `every rung arms in one step`() {
        for (ms in GateBlock.RUNGS_MS) {
            val v = GateBlock.evaluate(ms, standingMs = 0)
            assertEquals("$ms", LockRequest.Verdict.Arm(ms), v)
        }
    }

    @Test
    fun `a longer standing lock is not shortened`() {
        assertTrue(GateBlock.evaluate(4 * hour, standingMs = 20 * hour) is LockRequest.Verdict.TooShort)
    }

    @Test
    fun `offered on the lease expired gate only`() {
        assertTrue(GateBlock.offered(expired = true))
        assertFalse(GateBlock.offered(expired = false))
    }

    // ---------------------------------------------------------- wiring, as text

    @Test
    fun `the screen offers it outside the panel, so from the first frame`() {
        val screen = repoFile("app/src/main/java/dev/molasses/ui/gate/LeaseGateScreen.kt").readText()
        val body = functionBody(screen, "fun LeaseGateScreen(")
        val panelBranch = body.indexOf("if (panelUp) {")
        val block = body.indexOf("if (blockOffered) {")
        assertTrue(panelBranch >= 0 && block > panelBranch)
        // The panel branch closes before the block control opens.
        val between = body.substring(panelBranch, block)
        assertEquals("the block control must not sit inside the panelUp branch",
            between.count { it == '{' }, between.count { it == '}' })
        val control = functionBody(screen, "private fun BlockControl(")
        assertTrue(control.contains("GateBlock.RUNGS_MS.forEach"))
    }

    @Test
    fun `back from the rungs returns to the gate`() {
        val manager = repoFile("app/src/main/java/dev/molasses/overlay/LeaseGateOverlayManager.kt").readText()
        assertTrue(manager.contains("if (blockPickerOpen) blockPickerOpen = false else decline(\"back\")"))
        assertTrue(manager.contains("blockOffered = GateBlock.offered(expired),"))
    }

    @Test
    fun `the service arms through LockRequest and the shared write, then shows the lock screen`() {
        val service = repoFile("app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt").readText()
        val body = functionBody(service, "private fun blockFromGate(")
        val eval = body.indexOf("GateBlock.evaluate(")
        val memory = body.indexOf("locks = locks.arm(pkg, now, verdict.durationMs, LockReason.BLOCK)")
        val store = body.indexOf("cycleStore.armLock(pkg, now, verdict.durationMs, LockReason.BLOCK)")
        val screen = body.indexOf("enforceLockIfNeeded(pkg, atEntry = false)")
        assertTrue("order: evaluate, memory, store, lock screen: $eval $memory $store $screen",
            eval in 0 until memory && memory < store && store < screen)
    }
}
