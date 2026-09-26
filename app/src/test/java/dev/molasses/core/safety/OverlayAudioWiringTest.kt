package dev.molasses.core.safety

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each full-screen overlay names its kind, and AudioFocusHold takes focus and
 * sends the key on that one answer. Read as text: the overlays compile
 * nowhere here.
 */
class OverlayAudioWiringTest {

    private fun src(name: String) = repoFile("app/src/main/java/dev/molasses/overlay/$name.kt").readText()

    @Test
    fun `each overlay decides through OverlayAudio`() {
        assertTrue(src("LeaseGateOverlayManager").contains("silence = OverlayAudio.silences(OverlayAudio.leaseGate(expired))"))
        assertTrue(src("GateOverlayManager").contains("silence = OverlayAudio.silences(OverlayAudio.Overlay.WALK_GATE)"))
        assertTrue(src("LockOverlayManager").contains("silence = OverlayAudio.silences(OverlayAudio.lock(atEntry))"))
    }

    @Test
    fun `an unsilenced overlay returns before any focus request or key`() {
        val take = functionBody(src("AudioFocusHold"), "fun take(")
        val gate = take.indexOf("if (!silence) {")
        val early = take.indexOf("return", gate)
        val request = take.indexOf("am.requestAudioFocus(request)")
        val pause = take.indexOf("pausePlayback(am, reason)")
        assertTrue(gate >= 0 && early > gate && request > early && pause > request)
        assertTrue(take.contains("audio focus not taken and media pause not sent for"))
        assertFalse("no second decision for the key alone", take.contains("sendPause"))
    }

    @Test
    fun `only the entry path raises the lock screen as at entry`() {
        val service = repoFile("app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt").readText()
        assertEquals(1, Regex("""enforceLockIfNeeded\(pkg, atEntry = true\)""").findAll(service).count())
        assertTrue(functionBody(service, "private fun enterTarget(").contains("enforceLockIfNeeded(pkg, atEntry = true)"))
        assertEquals(3, Regex("""enforceLockIfNeeded\(pkg, atEntry = false\)""").findAll(service).count())
    }
}
