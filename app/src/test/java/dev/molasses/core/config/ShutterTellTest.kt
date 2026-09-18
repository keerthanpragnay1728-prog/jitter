package dev.molasses.core.config

import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stall marker's two colours, asserted across the three files that spell
 * them out.
 *
 * ## Why this cannot be one constant
 * The palette is Compose `Color` values in `ui/theme/Color.kt`. The marker is
 * an Android colour int on a `Paint`, inside a `View` that deliberately hosts
 * no composition because its whole job is to consume a `MotionEvent` in as
 * few microseconds as possible. And `colors.xml` carries the themeable copy.
 * Three representations of the same two colours, none of which can import the
 * others.
 *
 * `tools/check-colors.sh` does not reach either the overlay package or
 * `colors.xml`, so nothing else in the repo would notice them drifting. The
 * terminal one matters most: `TerminalAlert` is the single hue break in the
 * app and it is reserved for the terminal tier, which is a rule that means
 * nothing if two files disagree about what the colour is.
 *
 * Pure; no Android imports. Reads the shipped files.
 */
class ShutterTellTest {

    private val shutter: String by lazy {
        repoFile("app/src/main/java/dev/molasses/overlay/ShutterOverlayManager.kt").readText()
    }

    private val palette: String by lazy {
        repoFile("app/src/main/java/dev/molasses/ui/theme/Color.kt").readText()
    }

    private val colorsXml: String by lazy {
        repoFile("app/src/main/res/values/colors.xml").readText()
    }

    @Test
    fun `the terminal marker is the palette's terminal colour`() {
        assertEquals(
            "TELL_COLOR_TERMINAL must equal TerminalAlert",
            hexOf(palette, "TerminalAlert"),
            constHexOf(shutter, "TELL_COLOR_TERMINAL"),
        )
    }

    @Test
    fun `the terminal marker matches its themeable copy`() {
        assertEquals(
            "molasses_tell_terminal must equal TELL_COLOR_TERMINAL",
            constHexOf(shutter, "TELL_COLOR_TERMINAL"),
            xmlHexOf("molasses_tell_terminal"),
        )
    }

    @Test
    fun `the ordinary marker matches its themeable copy`() {
        assertEquals(constHexOf(shutter, "TELL_COLOR"), xmlHexOf("molasses_tell"))
    }

    @Test
    fun `the two markers are different colours`() {
        // The whole point of the terminal one. If these ever collapsed, the
        // terminal tier would look like every other stall.
        assertTrue(
            constHexOf(shutter, "TELL_COLOR") != constHexOf(shutter, "TELL_COLOR_TERMINAL"),
        )
    }

    @Test
    fun `the terminal colour is used nowhere else in the palette`() {
        // The reserved-hue rule, asserted rather than documented. One
        // definition, one marker, one tier.
        val hex = hexOf(palette, "TerminalAlert")
        val occurrences = Regex(Regex.escape(hex), RegexOption.IGNORE_CASE)
            .findAll(palette)
            .count()
        assertEquals("TerminalAlert's hex appears more than once in Color.kt", 1, occurrences)
    }

    // -------------------------------------------------------------- helpers

    /** `val TerminalAlert = Color(0xFFFF5555)` to `FFFF5555`. */
    private fun hexOf(source: String, name: String): String =
        Regex("""val\s+$name\s*=\s*Color\(0[xX]([0-9A-Fa-f]{8})\)""")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?.uppercase()
            ?: error("no palette entry '$name'")

    /** `const val TELL_COLOR_TERMINAL = 0xFFFF5555.toInt()` to `FFFF5555`. */
    private fun constHexOf(source: String, name: String): String =
        Regex("""const\s+val\s+$name\s*=\s*0[xX]([0-9A-Fa-f]{8})""")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?.uppercase()
            ?: error("no constant '$name'")

    /** `<color name="molasses_tell">#FF8C8C96</color>` to `FF8C8C96`. */
    private fun xmlHexOf(name: String): String =
        Regex("""<color name="$name">#([0-9A-Fa-f]{8})</color>""")
            .find(colorsXml)
            ?.groupValues
            ?.get(1)
            ?.uppercase()
            ?: error("no colour resource '$name'")

}
