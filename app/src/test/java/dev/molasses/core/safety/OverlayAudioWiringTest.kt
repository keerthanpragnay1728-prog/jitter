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
        assertTrue(src("LeaseGateOverlayManager").contains("overlay = OverlayAudio.leaseGate(expired),"))
        assertTrue(src("GateOverlayManager").contains("overlay = OverlayAudio.Overlay.WALK_GATE"))
        assertTrue(src("LockOverlayManager").contains("overlay = OverlayAudio.lock(atEntry)"))
        // The kind is decided once, in AudioFocusHold, from the kind.
        assertTrue(functionBody(src("AudioFocusHold"), "fun take(").contains("val silence = OverlayAudio.silences(overlay)"))
    }

    @Test
    fun `an unsilenced overlay returns before any focus request or key`() {
        val take = functionBody(src("AudioFocusHold"), "fun take(")
        val gate = take.indexOf("if (!silence) {")
        val early = take.indexOf("return", gate)
        val request = take.indexOf("am.requestAudioFocus(request)")
        val pause = take.indexOf("pausePlayback(am, reason)")
        assertTrue(gate >= 0 && early > gate && request > early && pause > request)
        assertFalse("no second decision for the key alone", take.contains("sendPause"))
    }

    @Test
    fun `every take logs the kind, the classification, the focus result and the key`() {
        val take = functionBody(src("AudioFocusHold"), "fun take(")
        assertTrue(take.contains("overlay=\$overlay silence=false focus=not requested pause=not sent"))
        assertTrue(take.contains("overlay=\$overlay silence=true focus=already held pause=not re-sent"))
        assertTrue(take.contains("overlay=\$overlay silence=true focus=\$focus pause=\$pause"))
        assertTrue(take.contains("\"granted (result=\$result)\"") && take.contains("\"refused (result=\$result)\""))
    }

    @Test
    fun `the expired gate is classified from the same flag that titles it`() {
        // The screen reads LEASE EXPIRED from `expired`, and the audio kind
        // comes from the same parameter in the same function. A gate that
        // says LEASE EXPIRED cannot be classified as the entry gate.
        val show = functionBody(src("LeaseGateOverlayManager"), "fun show(")
        assertTrue(show.contains("expired = expired,"))
        assertTrue(show.contains("overlay = OverlayAudio.leaseGate(expired),"))
    }

    @Test
    fun `only the entry path raises the lock screen as at entry`() {
        val service = repoFile("app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt").readText()
        assertEquals(1, Regex("""enforceLockIfNeeded\(pkg, atEntry = true\)""").findAll(service).count())
        assertTrue(functionBody(service, "private fun enterTarget(").contains("enforceLockIfNeeded(pkg, atEntry = true)"))
        assertEquals(3, Regex("""enforceLockIfNeeded\(pkg, atEntry = false\)""").findAll(service).count())
    }
}
