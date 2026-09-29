package dev.molasses.core.ui

import dev.molasses.core.repoFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The status bar is hidden on the console and nowhere else.
 *
 * ## Why the scope is worth a test
 * Hiding the bars is one line and it is tempting to put it in a shared place,
 * which is exactly where it would be wrong. The settings activity is a screen
 * for reading and typing, and a user who swipes for the shade while editing a
 * package prefix should get the shade rather than a bar. Immersive is a
 * property of the console's purpose, not of this app's chrome.
 *
 * ## And why it is asserted rather than left to review
 * There is a second reason and it is the one CLAUDE.md cares about. Hiding the
 * bars conceals the hardcoded 44dp inset fault on whichever screen does it. If
 * that spread quietly to every activity, the fault would be invisible
 * everywhere at once, and the field reports it is waiting on would never
 * arrive. A test that pins the scope keeps the concealment to one screen and
 * keeps it deliberate.
 *
 * Pure: reads the sources, the same way `FontScaleWiringTest` does.
 */
class ImmersiveScopeTest {

    private val launcher = "app/src/main/java/dev/molasses/ui/launcher/LauncherActivity.kt"
    private val settings = "app/src/main/java/dev/molasses/ui/settings/SettingsActivity.kt"

    @Test
    fun `the console hides the status bar and nothing else`() {
        val text = repoFile(launcher).readText()
        assertTrue(
            "the launcher should hide the status bar",
            text.contains("controller.hide(WindowInsetsCompat.Type.statusBars())"),
        )
        assertTrue(
            "the bar must come back on a swipe, or the shade is unreachable " +
                "rather than two gestures away",
            text.contains("BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE"),
        )
    }

    @Test
    fun `the hide is not requested from onCreate`() {
        // The bug. onCreate runs before the decor view is attached, the
        // controller resolves through ViewRootImpl, and a request made before
        // that exists is dropped with no error. The status bar never hid on
        // either device at any point, which is what a lifecycle fault looks
        // like and is not what a shell quirk looks like.
        val text = repoFile(launcher).readText()
        val onCreate = text.indexOf("override fun onCreate(")
        val setContent = text.indexOf("setContent {", onCreate)
        assertTrue("onCreate has moved", onCreate >= 0 && setContent > onCreate)
        val body = text.substring(onCreate, setContent)
        assertTrue(
            "hideStatusBar must not be called from onCreate; it is dropped there",
            !body.contains("hideStatusBar()"),
        )
        assertTrue(
            "edge to edge belongs in onCreate and is not the thing that failed",
            body.contains("setDecorFitsSystemWindows(window, false)"),
        )
    }

    @Test
    fun `the hide is re-asserted every time the window takes focus`() {
        // Focus rather than attach, because attach fixes the first call and
        // nothing else. This activity is singleTask, so onCreate does not run
        // again when it comes back from a target app, and a one-shot fix
        // would leave the bar restored for the rest of the process.
        val text = repoFile(launcher).readText()
        val focus = text.indexOf("override fun onWindowFocusChanged(")
        assertTrue("no focus override; the hide is one-shot again", focus >= 0)
        val end = text.indexOf("\n    /**", focus)
        val body = text.substring(focus, if (end > focus) end else text.length)
        assertTrue("focus handler must re-hide", body.contains("hideStatusBar()"))
        assertTrue("and only when focus is gained", body.contains("if (hasFocus)"))
    }

    @Test
    fun `a dropped request is diagnosable from logcat alone`() {
        // The failure mode is silence: the controller is obtained, the call
        // returns, nothing happens. Establishing that cost a build and a
        // device round trip once, and these lines are what make a second
        // occurrence readable without another one.
        val text = repoFile(launcher).readText()
        assertTrue("attachment state must be logged", text.contains("attached="))
        assertTrue("the before reading must be logged", text.contains("before="))
        assertTrue(
            "and a reading after the request has had a frame to land",
            text.contains("after one frame"),
        )
    }

    @Test
    fun `the navigation bar stays`() {
        // It carries nobody's notifications, and hiding it takes the back
        // gesture's affordance with it on a three-button device.
        val text = repoFile(launcher).readText()
        assertFalse(
            "the navigation bar must not be hidden",
            text.contains("Type.navigationBars()") || text.contains("Type.systemBars()"),
        )
    }

    @Test
    fun `settings does not hide anything`() {
        // A screen for reading and typing. A swipe for the shade while
        // editing a prefix should get the shade.
        val text = repoFile(settings).readText()
        assertFalse(
            "the settings activity must not go immersive",
            text.contains("WindowInsetsControllerCompat"),
        )
    }

    @Test
    fun `the inset note in CLAUDE_md still says the fault is open`() {
        // The thing this is really guarding. Immersive makes the 44dp
        // constant invisible on the console, and an invisible fault is one
        // nobody reports. If someone later reads the concealment as a fix and
        // deletes the section, this fails.
        val doc = repoFile("CLAUDE.md").readText()
        assertTrue(
            "CLAUDE.md must still carry the inset fault section",
            doc.contains("Window insets are a constant, and that is a known fault"),
        )
        assertTrue(
            "and must still say immersive conceals it rather than fixes it",
            doc.contains("conceals this fault rather than fixing it"),
        )
    }
}
