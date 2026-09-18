package dev.molasses.core.ui

import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every `MolassesTheme` call site, asserted as text.
 *
 * ## Why a test and not a code review
 * The font scale shipped broken in the most invisible way available: the
 * selector persisted, `FontScale` was correct and covered, `MolassesTheme`
 * read its parameter and rebuilt the typography from it, and the pure layer
 * had nothing wrong with it at all. Four of the five call sites simply did not
 * pass the argument, and `fontScale: Float = 1.0f` meant that compiled, ran,
 * and looked exactly like a working feature.
 *
 * A default parameter is the right shape for that function and the wrong shape
 * for its call sites, and nothing in the type system can tell them apart. So
 * this reads the sources and asserts the argument is present, which is the one
 * check that fails when someone adds a sixth window and forgets.
 *
 * ## Why it reads files rather than composing
 * It runs in `tools/pure-verify`, which has no Android toolchain and no
 * Compose. The file is read from disk as text, which is also the honest level:
 * the thing that ships is the call site.
 *
 * Pure. The path walk means it works from the repository root and from
 * `tools/pure-verify` alike.
 */
class FontScaleWiringTest {

    /**
     * Every file that composes a window of ours.
     *
     * Named individually rather than globbed, so adding a window is a failure
     * here rather than a silent omission. The shutter is deliberately absent:
     * its sink is a plain `View` and draws no text at all.
     */
    private val hosts = listOf(
        "app/src/main/java/dev/molasses/ui/launcher/LauncherActivity.kt",
        "app/src/main/java/dev/molasses/ui/settings/SettingsActivity.kt",
        "app/src/main/java/dev/molasses/overlay/LockOverlayManager.kt",
        "app/src/main/java/dev/molasses/overlay/GateOverlayManager.kt",
        "app/src/main/java/dev/molasses/overlay/LeaseGateOverlayManager.kt",
    )

    @Test
    fun `every window passes the font scale`() {
        for (path in hosts) {
            val text = repoFile(path).readText()
            val calls = Regex("""MolassesTheme\s*\(""").findAll(text).count()
            assertEquals(
                "$path should call MolassesTheme exactly once with arguments",
                1,
                calls,
            )
            assertTrue(
                "$path calls MolassesTheme without passing fontScale, which " +
                    "compiles and silently renders at 1.0",
                Regex("""MolassesTheme\s*\(\s*fontScale\s*=""").containsMatchIn(text),
            )
        }
    }

    @Test
    fun `nobody uses the bare form that caused this`() {
        // MolassesTheme { ... } with no argument list at all. It is the exact
        // shape four of the five call sites had, and it is indistinguishable
        // from correct code at a glance.
        for (path in hosts) {
            val text = repoFile(path).readText()
            assertTrue(
                "$path still has a bare MolassesTheme { call",
                !Regex("""MolassesTheme\s*\{""").containsMatchIn(text),
            )
        }
    }

    @Test
    fun `the overlays read the scale when shown rather than when constructed`() {
        // A captured Float would be whatever the setting was when the service
        // connected, which is the same bug one layer down: the managers are
        // built once at connect and shown for hours afterwards. The lambda is
        // what makes a change to the setting reach the next gate.
        for (path in hosts.filter { it.contains("/overlay/") }) {
            val text = repoFile(path).readText()
            assertTrue(
                "$path should hold fontScale as a lambda, not a value",
                text.contains("private val fontScale: () -> Float"),
            )
            assertTrue(
                "$path should invoke it at composition",
                text.contains("MolassesTheme(fontScale = fontScale())"),
            )
        }
    }

    @Test
    fun `the service feeds the overlays and reads the store to do it`() {
        val service = repoFile(
            "app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt",
        ).readText()
        assertEquals(
            "each Compose-hosting overlay manager needs the scale passed",
            3,
            Regex("""fontScale\s*=\s*\{\s*fontScaleMultiplier\s*\}""").findAll(service).count(),
        )
        assertTrue(
            "the service must read fontScaleOrdinal off the store, or the " +
                "lambda always answers with the seeded default",
            service.contains("FontScale.fromOrdinal(state.fontScaleOrdinal)"),
        )
    }

    @Test
    fun `MolassesTheme still defaults, because the parameter is not the fault`() {
        // Removing the default would make this uncallable from a preview or a
        // test, and the defect was never the default. It was five call sites
        // and no way to tell which had opted out on purpose.
        val theme = repoFile("app/src/main/java/dev/molasses/ui/theme/Theme.kt").readText()
        assertTrue(theme.contains("fontScale: Float = 1.0f"))
    }

}
