package dev.molasses.core.safety

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The home-first overlays, as wired. Read as text: the overlays and the
 * service compile nowhere here.
 */
class HomeFirstWiringTest {

    private fun overlay(name: String) = repoFile("app/src/main/java/dev/molasses/overlay/$name.kt").readText()
    private val service by lazy { repoFile("app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt").readText() }
    private val screen by lazy { repoFile("app/src/main/java/dev/molasses/ui/gate/LeaseGateScreen.kt").readText() }

    @Test
    fun `home is sent after the first draw, never from the call that adds the window`() {
        for ((name, fn) in listOf(
            "LeaseGateOverlayManager" to "fun show(",
            "LockOverlayManager" to "fun flash(",
            "GateOverlayManager" to "fun show(",
        )) {
            val body = functionBody(overlay(name), fn)
            val firstDraw = body.indexOf("onFirstDraw = if (")
            assertTrue("$name hands home to onFirstDraw", firstDraw >= 0)
            val afterAttach = body.substring(body.indexOf("if (!h.isShowing) {"))
            assertFalse("$name must not send home straight after attaching", afterAttach.substringBefore("return true").contains("goHome()\n        onWindowsChanged"))
        }
        assertTrue(functionBody(overlay("LeaseGateOverlayManager"), "private fun sendHome(").contains("goHome()"))
    }

    @Test
    fun `leaveTarget skips home-first overlays and still closes the session`() {
        val leave = functionBody(service, "private fun leaveTarget(")
        assertTrue(leave.contains("if (gate.homeFirst) logSurvived(\"walk gate\", pkg, reason) else gate.abandon(\"left target\")"))
        assertTrue(leave.contains("if (leaseGate.homeFirst) logSurvived(\"lease gate\", pkg, reason) else leaseGate.dismiss(\"left target\")"))
        assertTrue(leave.contains("if (lockOverlay.homeFirst) logSurvived(\"lock overlay\", pkg, reason) else lockOverlay.dismiss(\"left target\")"))
        val skip = leave.indexOf("gate.homeFirst")
        for (step in listOf("sessions.close(id) ?: return", "engine.onForegroundExit(pkg, now())", "shutter.release(")) {
            assertTrue("$step still happens, before the skip", leave.indexOf(step) in 0 until skip)
        }
    }

    @Test
    fun `ARCHITECT'S SPACE and back share one handler on both lease gates`() {
        val show = functionBody(overlay("LeaseGateOverlayManager"), "fun show(")
        val back = show.substring(show.indexOf("onBackPressed = {")).substringBefore("},")
        assertTrue(back.contains("else -> exit()"))
        assertFalse("back no longer has an entry-gate answer of its own", back.contains("decline(\"back\")") || back.contains("expired ->"))
        assertTrue(show.contains("onExit = { exit() },"))
        assertFalse(show.contains("onTakeMeOut"))
        val exit = functionBody(overlay("LeaseGateOverlayManager"), "private fun exit()")
        assertTrue("ledger reason=exit, through the one decline path", exit.contains("decline(\"exit\")"))
        val decline = functionBody(overlay("LeaseGateOverlayManager"), "private fun decline(")
        assertTrue(decline.contains("EventType.LEASE_DECLINED, \"reason=\$reason\""))
        assertFalse("leaving grants nothing", decline.contains("onLeaseTaken("))
    }

    @Test
    fun `taking a lease on a home-first gate grants, then relaunches, then dismisses`() {
        val take = functionBody(overlay("LeaseGateOverlayManager"), "private fun takeLease(")
        val branch = take.substring(take.indexOf("if (homeFirstNow) {"))
        val grant = branch.indexOf("onLeaseTaken(pkg, durationMs)")
        val relaunch = branch.indexOf("relaunch(pkg)")
        val dismiss = branch.indexOf("dismissInternal()")
        assertTrue(grant in 0 until relaunch && relaunch < dismiss)
        assertTrue(branch.contains("relaunch on lease: pkg=\$pkg success=\$launched"))
        val relaunchFn = functionBody(service, "private fun relaunchTarget(")
        assertTrue(relaunchFn.contains("packageManager.getLaunchIntentForPackage(pkg)"))
        assertTrue(service.contains("relaunch = ::relaunchTarget,"))
    }

    @Test
    fun `TAKE ME OUT is gone from both gates, and the exit sits outside the lease panel`() {
        val body = functionBody(screen, "fun LeaseGateScreen(")
        val exit = body.indexOf("if (controls.exit == GateControls.Exit.ARCHITECTS_SPACE) {")
        assertTrue(exit >= 0 && body.indexOf("R.string.lock_exit", exit) > exit)
        // After the leases branch has closed, so it is up before zero.
        val leases = body.indexOf("if (controls.leases) {")
        val between = body.substring(leases, exit)
        assertEquals("the exit is outside the leases branch", between.count { it == '{' }, between.count { it == '}' })
        assertFalse(screen.contains("onTakeMeOut"))
        val strings = repoFile("app/src/main/res/values/strings.xml").readText()
        assertFalse(strings.contains("name=\"lease_gate_out\""))
    }

    @Test
    fun `the entry gate and a lock at entry take their home-first answer from HomeFirst, and nothing overrides it`() {
        val manager = overlay("LeaseGateOverlayManager")
        assertTrue(manager.contains("homeFirst: Boolean = HomeFirst.sendsHome(HomeFirst.leaseGate(expired)),"))
        assertTrue(service.contains("homeFirst: Boolean = HomeFirst.sendsHome(HomeFirst.leaseGate(expired)),"))
        val flash = functionBody(overlay("LockOverlayManager"), "fun flash(")
        assertTrue(flash.contains("homeFirstNow = HomeFirst.sendsHome(kind)"))
        assertTrue(flash.contains("onFirstDraw = if (homeFirstNow) {"))
        val main = File(dev.molasses.core.repoRoot(), "app/src/main/java")
        val overrides = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("homeFirst = false") }.map { it.name }.toList()
        assertEquals(emptyList<String>(), overrides)
    }

    @Test
    fun `the walking gate's lease panel runs home-first too, so its lease relaunches`() {
        assertTrue(service.contains("showLeaseGate(pkg, countdownMs = 0, expired = false, homeFirst = true)"))
    }

    @Test
    fun `a sensitive app under a home-first overlay still takes every overlay down`() {
        val watch = functionBody(service, "private fun repaceHomeFirstWatch()")
        assertTrue(watch.contains("SensitivePackages.isSensitive(active, sensitivePrefixes)"))
        assertTrue(watch.contains("tearDownOverlays("))
        assertTrue(functionBody(service, "private fun onOverlayWindowsChanged()").contains("repaceHomeFirstWatch()"))
    }

    @Test
    fun `no audio lever is left, and the legacy restore runs on connect`() {
        val main = File(dev.molasses.core.repoRoot(), "app/src/main/java")
        val offenders = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter {
            val t = it.readText()
            t.contains("requestAudioFocus(") || t.contains("KEYCODE_MEDIA_PAUSE") || t.contains("ADJUST_MUTE")
        }.map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
        val connect = functionBody(service, "override fun onServiceConnected()")
        assertTrue(connect.indexOf("LegacyMuteRestore.restoreOnConnect(this)") in 0 until connect.indexOf("ShutterOverlayManager("))
        val legacy = repoFile("app/src/main/java/dev/molasses/monitor/LegacyMuteRestore.kt").readText()
        assertTrue(legacy.contains("delete in the release after this one"))
        assertTrue(legacy.contains("AudioManager.ADJUST_UNMUTE") && legacy.contains(".remove(KEY_OURS)"))
    }

    @Test
    fun `the walking gate has ARCHITECT'S SPACE from the first frame, sharing its handler with back`() {
        val show = functionBody(overlay("GateOverlayManager"), "fun show(")
        assertTrue(show.contains("onBackPressed = { exit() },"))
        assertTrue(show.contains("onExit = { exit() },"))
        val exit = functionBody(overlay("GateOverlayManager"), "private fun exit()")
        assertTrue(exit.contains("EventType.LEASE_DECLINED, \"reason=exit\""))
        assertFalse("an exit is not an abandon", exit.contains("GATE_ABANDONED") || exit.contains("onAbandoned("))
        assertFalse("and grants nothing", exit.contains("onCleared("))
        val gateScreen = repoFile("app/src/main/java/dev/molasses/ui/gate/GateScreen.kt").readText()
        // Sliced by hand: functionBody would stop at the `= {}` default in
        // the parameter list.
        val body = gateScreen.substring(gateScreen.indexOf("fun GateScreen(")).substringBefore("\n}\n")
        val exitControl = body.indexOf("OverlayExit.shown(HomeFirst.Overlay.WALK_GATE")
        assertTrue(exitControl >= 0 && body.indexOf("R.string.lock_exit", exitControl) > exitControl)
        // Not inside the alternative-challenge branch or the progress branch.
        val branch = body.indexOf("if (alternativeChallenge) {")
        val between = body.substring(branch, exitControl)
        assertEquals("the exit sits outside both challenge branches", between.count { it == '{' }, between.count { it == '}' })
    }

    @Test
    fun `the foreground watch re-homes the gated app through the pure decision, on main`() {
        val watch = functionBody(service, "private fun repaceHomeFirstWatch()")
        assertTrue(watch.contains("HomeFirstWatch.onForeground("))
        assertTrue(watch.contains("gatedPkg = gated,") && watch.contains("lastRehomeMs = lastRehomeMs,"))
        val rehome = watch.substring(watch.indexOf("HomeFirstWatch.Action.Rehome -> {"))
        assertTrue("stamped before home is sent", rehome.indexOf("lastRehomeMs = nowMs") in 0 until rehome.indexOf("goHomeQuietly()"))
        assertTrue(watch.contains("re-home for \$gated skipped: debounced"))
        assertTrue(watch.contains("withContext(Dispatchers.Main.immediate)"))
        val gated = functionBody(service, "private fun homeFirstGatedPkg()")
        for (m in listOf("leaseGate", "gate", "lockOverlay")) assertTrue(m, gated.contains("$m.homeFirst -> $m.showingFor"))
    }
}
