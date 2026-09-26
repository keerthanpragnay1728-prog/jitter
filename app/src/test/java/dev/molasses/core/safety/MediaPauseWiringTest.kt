package dev.molasses.core.safety

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every full-screen overlay still takes focus, and each names its overlay
 * kind for the pause key. Read as text: the overlays compile nowhere here.
 */
class MediaPauseWiringTest {

    private fun src(name: String) = repoFile("app/src/main/java/dev/molasses/overlay/$name.kt").readText()

    @Test
    fun `each overlay decides the key through MediaPause`() {
        assertTrue(src("LeaseGateOverlayManager").contains("sendPause = MediaPause.sendsPause(MediaPause.leaseGate(expired))"))
        assertTrue(src("GateOverlayManager").contains("sendPause = MediaPause.sendsPause(MediaPause.Overlay.WALK_GATE)"))
        assertTrue(src("LockOverlayManager").contains("sendPause = MediaPause.sendsPause(MediaPause.lock(atEntry))"))
    }

    @Test
    fun `the key is sent only when asked, and focus is taken either way`() {
        val take = functionBody(src("AudioFocusHold"), "fun take(")
        val request = take.indexOf("am.requestAudioFocus(request)")
        val gate = take.indexOf("if (sendPause) {")
        assertTrue("focus is requested before and regardless of the key decision", request in 0 until gate)
        assertTrue(take.contains("media pause not sent for"))
    }

    @Test
    fun `only the entry path raises the lock screen as at entry`() {
        val service = repoFile("app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt").readText()
        assertEquals(1, Regex("""enforceLockIfNeeded\(pkg, atEntry = true\)""").findAll(service).count())
        assertTrue(functionBody(service, "private fun enterTarget(").contains("enforceLockIfNeeded(pkg, atEntry = true)"))
        assertEquals(3, Regex("""enforceLockIfNeeded\(pkg, atEntry = false\)""").findAll(service).count())
    }
}
