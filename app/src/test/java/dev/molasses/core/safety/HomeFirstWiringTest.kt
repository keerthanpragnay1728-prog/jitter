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
            val firstDraw = body.indexOf("onFirstDraw = {")
            assertTrue("$name hands home to onFirstDraw, unconditionally", firstDraw >= 0)
            assertFalse("$name has no path that skips it", body.contains("onFirstDraw = if ("))
            val afterAttach = body.substring(body.indexOf("if (!h.isShowing) {"))
            assertFalse("$name must not send home straight after attaching", afterAttach.substringBefore("return true").contains("goHome()\n        onWindowsChanged"))
        }
        assertTrue(functionBody(overlay("LeaseGateOverlayManager"), "private fun sendHome(").contains("goHome()"))
    }

    @Test
    fun `leaveTarget leaves a showing overlay up and still closes the session`() {
        val leave = functionBody(service, "private fun leaveTarget(")
        assertTrue(leave.contains("if (gate.isShowing) logSurvived(\"walk gate\", pkg, reason)\n"))
        assertFalse("leaving no longer abandons the walking gate", leave.contains("gate.abandon("))
        assertTrue(leave.contains("if (leaseGate.isShowing) logSurvived(\"lease gate\", pkg, reason) else leaseGate.dismiss(\"left target\")"))
        assertTrue(leave.contains("if (lockOverlay.isShowing) logSurvived(\"lock overlay\", pkg, reason) else lockOverlay.dismiss(\"left target\")"))
        val skip = leave.indexOf("if (gate.isShowing) logSurvived(")
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
    fun `taking a lease grants, then relaunches, then dismisses, with no other path`() {
        val take = functionBody(overlay("LeaseGateOverlayManager"), "private fun takeLease(")
        val grant = take.indexOf("onLeaseTaken(pkg, durationMs)")
        val relaunch = take.indexOf("relaunch(pkg)")
        val dismiss = take.indexOf("dismissInternal()")
        assertTrue(grant in 0 until relaunch && relaunch < dismiss)
        assertEquals("one grant, one dismiss", 1, Regex("onLeaseTaken\\(").findAll(take).count())
        assertEquals(1, Regex("dismissInternal\\(\\)").findAll(take).count())
        assertFalse(take.contains("else"))
        assertTrue(take.contains("relaunch on lease: pkg=\$pkg success=\$launched"))
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
    fun `no overlay has a switch that could stop it going home`() {
        val main = File(dev.molasses.core.repoRoot(), "app/src/main/java")
        val offenders = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.filter {
            val t = it.readText()
            t.contains("homeFirstNow") || t.contains("sendsHome(") || t.contains("homeFirst:") ||
                t.contains("homeFirst =") || t.contains(".homeFirst")
        }.map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
        assertTrue(functionBody(overlay("LockOverlayManager"), "fun flash(").contains("val kind = OverlayKind.lock(atEntry)"))
    }

    @Test
    fun `the walking gate's lease panel is an ordinary lease gate, so its lease relaunches`() {
        assertTrue(service.contains("showLeaseGate(pkg, countdownMs = 0, expired = false) }"))
    }

    @Test
    fun `a sensitive app under a home-first overlay still takes every overlay down`() {
        val watch = functionBody(service, "private fun repaceHomeFirstWatch()")
        assertTrue(watch.contains("SensitivePackages.isSensitive(active, sensitivePrefixes)"))
        assertTrue(watch.contains("tearDownOverlays("))
        assertTrue(functionBody(service, "private fun onOverlayWindowsChanged()").contains("repaceHomeFirstWatch()"))
    }

    @Test
    fun `no focus and no mute, one pause key in one file, and the legacy restore runs on connect`() {
        val main = File(dev.molasses.core.repoRoot(), "app/src/main/java")
        val kt = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        fun filesWith(vararg s: String) = kt.filter { f -> val t = f.readText(); s.any { t.contains(it) } }.map { it.name }
        // Disproven on device and not to come back: focus, and the mute.
        assertEquals(emptyList<String>(), filesWith("requestAudioFocus(", "AudioFocusRequest", "ADJUST_MUTE"))
        // Never PLAY or PLAY_PAUSE: either would start the user's own music.
        assertEquals(emptyList<String>(), filesWith("KEYCODE_MEDIA_PLAY"))
        // The pause key lives in one file, and only it dispatches media keys.
        assertEquals(listOf("MediaPauseKey.kt"), filesWith("KEYCODE_MEDIA_PAUSE", "dispatchMediaKeyEvent("))
        val connect = functionBody(service, "override fun onServiceConnected()")
        assertTrue(connect.indexOf("LegacyMuteRestore.restoreOnConnect(this)") in 0 until connect.indexOf("ShutterOverlayManager("))
        val legacy = repoFile("app/src/main/java/dev/molasses/monitor/LegacyMuteRestore.kt").readText()
        assertTrue(legacy.contains("delete in the release after this one"))
        assertTrue(legacy.contains("AudioManager.ADJUST_UNMUTE") && legacy.contains(".remove(KEY_OURS)"))
    }

    @Test
    fun `the pause key is down then up, sent after home, on the two gates only`() {
        val key = repoFile("app/src/main/java/dev/molasses/monitor/MediaPauseKey.kt").readText()
        val down = key.indexOf("KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE")
        val up = key.indexOf("KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE")
        assertTrue(down in 0 until up)
        assertEquals(2, Regex("""dispatchMediaKeyEvent\(""").findAll(key).count())

        val lease = functionBody(overlay("LeaseGateOverlayManager"), "private fun sendHome(")
        assertTrue("after home", lease.indexOf("goHome()") in 0 until lease.indexOf("pauseMedia()"))
        assertTrue(lease.contains("MediaPause.sendsPause(kind, videoApp) && pauseMedia()"))
        assertTrue(lease.contains("overlay=\$kind pkg=\$pkg isVideoApp=\$videoApp home sent; pause sent=\$paused"))
        val walk = functionBody(overlay("GateOverlayManager"), "fun show(")
        assertTrue("after home", walk.indexOf("goHome()") in 0 until walk.indexOf("pauseMedia()"))
        assertTrue(walk.contains("MediaPause.sendsPause(OverlayKind.WALK_GATE, videoApp) && pauseMedia()"))
        assertTrue(walk.contains("pkg=\$pkg isVideoApp=\$videoApp home sent; pause sent=\$paused"))
        // Read once per show, before the window goes up.
        assertTrue(walk.indexOf("val videoApp = isVideoApp(pkg)") in 0 until walk.indexOf("h.show("))
        val leaseShow = functionBody(overlay("LeaseGateOverlayManager"), "fun show(")
        assertTrue(leaseShow.indexOf("val videoApp = isVideoApp(pkg)") in 0 until leaseShow.indexOf("h.show("))
        assertTrue(leaseShow.contains("onFirstDraw = { sendHome(pkg, kind, videoApp) },"))
        // The service reads the category, a failed lookup reads as none, and
        // the pure rule decides.
        val video = functionBody(service, "private fun isVideoApp(")
        assertTrue(video.contains("ApplicationInfo.CATEGORY_VIDEO"))
        assertTrue(video.contains(".getOrDefault(false)"))
        assertTrue(video.contains("MediaPause.isVideoApp(pkg, categoryVideo)"))
        assertEquals(2, Regex("""isVideoApp = ::isVideoApp,""").findAll(service).count())

        // Sent from the first draw and nowhere else: nothing on dismiss.
        assertEquals(1, Regex("""pauseMedia\(\)""").findAll(overlay("LeaseGateOverlayManager")).count())
        assertEquals(1, Regex("""pauseMedia\(\)""").findAll(overlay("GateOverlayManager")).count())
        // Never on a lock, and wired into exactly the two gates.
        assertFalse(overlay("LockOverlayManager").contains("pauseMedia"))
        assertFalse(overlay("LockOverlayManager").contains("MediaPause"))
        assertEquals(2, Regex("""pauseMedia = \{ MediaPauseKey\.send\(this\) \},""").findAll(service).count())
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
        val exitControl = body.indexOf("OverlayExit.shown(OverlayKind.WALK_GATE")
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
        val gated = service.substring(service.indexOf("private fun homeFirstGatedPkg()")).substringBefore("\n\n")
        assertTrue(gated.contains("leaseGate.showingFor ?: gate.showingFor ?: lockOverlay.showingFor"))
    }
}
