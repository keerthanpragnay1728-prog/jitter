package dev.molasses.core.safety

import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the two full-screen windows take and give back audio focus.
 *
 * ## Why this is asserted as text
 * Both managers are in the set no tool in this environment compiles, and both
 * invariants here are positional rather than behavioural. A compiler would not
 * catch either of them and neither would a unit test of `AudioFocusHold`,
 * because the class is correct in isolation in both broken arrangements.
 *
 * ## The two positions
 * **Take after the addView check.** Both managers return early when the window
 * fails to attach, and that early return does not run their teardown. A
 * request made before the check would be held by a window that never appeared,
 * and nothing would ever give it back: the device goes silent with nothing on
 * screen to explain it, which is the worst outcome available here and is
 * invisible from inside the app.
 *
 * **Release at the choke point.** The lease gate has four ways out (a lease
 * taken, a decline after its home settle, a dismiss from the service, and
 * teardown) and all four run through `dismissInternal`. Releasing in
 * `dismiss` instead would cover one of the four and leak the other three.
 *
 * Pure: reads the sources from disk, the same way `FontScaleWiringTest` reads
 * its call sites and for the same reason.
 */
class AudioFocusWiringTest {

    private val gate = "app/src/main/java/dev/molasses/overlay/LeaseGateOverlayManager.kt"
    private val lock = "app/src/main/java/dev/molasses/overlay/LockOverlayManager.kt"
    private val walk = "app/src/main/java/dev/molasses/overlay/GateOverlayManager.kt"
    private val hold = "app/src/main/java/dev/molasses/overlay/AudioFocusHold.kt"

    @Test
    fun `every full-screen window holds audio focus`() {
        // The lock overlay was the one expected to be forgotten, because it
        // was a 1.8 second flash when it was written and is now unbounded.
        // The one actually forgotten was the walking gate, which never took
        // focus at all and let media play on under it on a device.
        for (path in listOf(gate, lock, walk)) {
            val text = repoFile(path).readText()
            assertTrue(
                "$path should hold an AudioFocusHold",
                text.contains("AudioFocusHold(service)"),
            )
            assertTrue("$path never takes focus", text.contains("focus.take("))
            assertTrue("$path never releases focus", text.contains("focus.release("))
        }
    }

    @Test
    fun `focus is taken only after the window is known to have attached`() {
        for (path in listOf(gate, lock, walk)) {
            val text = repoFile(path).readText()
            val guard = text.indexOf("if (!h.isShowing)")
            val take = text.indexOf("focus.take(")
            assertTrue("$path no longer checks whether the window attached", guard >= 0)
            assertTrue("$path does not take focus", take >= 0)
            assertTrue(
                "$path takes audio focus before knowing the window attached. The " +
                    "failure path returns without running teardown, so nothing " +
                    "would ever give it back and the device would be silent with " +
                    "no window on screen.",
                guard < take,
            )
        }
    }

    @Test
    fun `the lease gate releases in the choke point every exit runs through`() {
        val text = repoFile(gate).readText()
        val body = slice(text, "private fun dismissInternal()", "private companion object")
        assertTrue(
            "the lease gate must release audio focus in dismissInternal. It has " +
                "four ways out and that is the only one all four reach; " +
                "releasing in dismiss() would cover one and leak three.",
            body.contains("focus.release("),
        )
    }

    @Test
    fun `the lock overlay releases in dismiss, after it knows it had a window`() {
        val text = repoFile(lock).readText()
        val body = slice(text, "fun dismiss(reason: String)", "private companion object")
        assertTrue("the lock overlay must release audio focus in dismiss", body.contains("focus.release("))
        val hostCheck = body.indexOf("val h = host ?: return")
        val release = body.indexOf("focus.release(")
        assertTrue("the host null check has moved", hostCheck >= 0)
        assertTrue(
            "release belongs after the null check: focus is only taken once a " +
                "window attached, so a null host means nothing is held",
            hostCheck < release,
        )
    }

    @Test
    fun `the walking gate releases in the choke point every exit runs through`() {
        val body = slice(repoFile(walk).readText(), "private fun dismissInternal()", "companion object")
        assertTrue("the walking gate must release audio focus in dismissInternal", body.contains("focus.release("))
    }

    @Test
    fun `every full-screen manager is one the service owns, so none is missed`() {
        // A fourth full-screen window would be a fourth place to forget focus.
        val service = repoFile("app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt").readText()
        val managers = Regex("""lateinit var \w+: (\w+OverlayManager)\b""").findAll(service)
            .map { it.groupValues[1] }.filter { it != "ShutterOverlayManager" }.toSet()
        assertEquals(setOf("GateOverlayManager", "LockOverlayManager", "LeaseGateOverlayManager"), managers)
    }

    @Test
    fun `taking focus also sends a media pause, down and up`() {
        val text = repoFile(hold).readText()
        val take = slice(text, "fun take(", "fun release(")
        assertTrue("take must dispatch the pause", take.contains("pausePlayback("))
        val pause = slice(text, "private fun pausePlayback(", "private companion object")
        assertTrue(pause.contains("KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE"))
        assertTrue(pause.contains("KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE"))
        assertTrue(pause.contains("dispatchMediaKeyEvent("))
    }

    @Test
    fun `nothing ever sends play, and release sends no key at all`() {
        // PLAY_PAUSE would start music that was stopped; PLAY on dismiss
        // would resume what we interrupted. Neither is ours to do.
        val text = repoFile(hold).readText()
        for (forbidden in listOf("KEYCODE_MEDIA_PLAY", "KEYCODE_MEDIA_PLAY_PAUSE")) {
            assertFalse("$forbidden must not appear in AudioFocusHold", Regex("""\b$forbidden\b""").containsMatchIn(text))
        }
        val release = slice(text, "fun release(", "private fun pausePlayback(")
        assertFalse("release must not dispatch a key", release.contains("dispatchMediaKeyEvent"))
    }

    @Test
    fun `the gain is transient and does not duck`() {
        // Ducking leaves the audio audible at a lower volume, which over a
        // thirty second countdown says the same thing about the session still
        // running, more quietly, and leaves the countdown competing with
        // speech. The two constants differ by a suffix, so this checks for the
        // suffix rather than for the prefix both of them share.
        val text = repoFile("app/src/main/java/dev/molasses/overlay/AudioFocusHold.kt").readText()
        assertTrue(
            "ducking is not silence; see the class doc",
            !text.contains("MAY_DUCK"),
        )
        assertTrue(
            "the gain should be AUDIOFOCUS_GAIN_TRANSIENT",
            text.contains("AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)"),
        )
    }

    @Test
    fun `the request instance is kept, because abandoning matches on it`() {
        // A rebuilt AudioFocusRequest abandons nothing. The device stays muted
        // with no window on screen, and the log says the abandon succeeded.
        val text = repoFile("app/src/main/java/dev/molasses/overlay/AudioFocusHold.kt").readText()
        assertTrue(
            "AudioFocusHold must abandon the instance it holds",
            Regex("""abandonAudioFocusRequest\(request\)""").containsMatchIn(text),
        )
        assertTrue(
            "the held request must come from the stored field",
            Regex("""val request = held \?: return""").containsMatchIn(text),
        )
    }

    private fun slice(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("could not find `$from`", start >= 0)
        val end = text.indexOf(to, start)
        return text.substring(start, if (end > start) end else text.length)
    }

}
