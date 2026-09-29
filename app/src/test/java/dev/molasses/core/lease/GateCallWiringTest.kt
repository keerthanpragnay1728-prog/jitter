package dev.molasses.core.lease

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The call check is a precondition of attaching a gate, in one place.
 *
 * `LaunchGate.decide` is pure and tested directly. What no pure test can say
 * is that the service feeds it the live call state, so that is asserted from
 * the source, the same way `LeaseGrantVisibilityTest` reads its call sites.
 */
class GateCallWiringTest {

    private val service = "app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt"
    private val leaseGate = "app/src/main/java/dev/molasses/overlay/LeaseGateOverlayManager.kt"

    @Test
    fun `maybeLaunchGate asks the call detector before deciding`() {
        val body = functionBody(repoFile(service).readText(), "private fun maybeLaunchGate(")
        val decide = body.indexOf("LaunchGate.decide(")
        val call = body.indexOf("inCall = calls.inProgress(whenUnknown = false)")
        assertTrue("maybeLaunchGate no longer calls LaunchGate.decide", decide >= 0)
        assertTrue("the live call state must be passed as inCall, with whenUnknown = false", call > decide)
        val show = body.indexOf("showLeaseGate(")
        assertTrue("the decision must come before any gate is shown", show < 0 || decide < show)
    }

    @Test
    fun `the lease gate keeps its own dismissal for a call that starts after it is up`() {
        val body = functionBody(repoFile(leaseGate).readText(), "fun show(")
        assertTrue(
            "the post-attach call dismissal is the other half and must stay",
            body.contains("calls.inProgress(whenUnknown = false)") && body.contains("dismiss(\"call in progress\")"),
        )
    }
}
