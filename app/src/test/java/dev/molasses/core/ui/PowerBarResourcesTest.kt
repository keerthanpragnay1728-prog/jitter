package dev.molasses.core.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two ends of the battery line, asserted against the formatter.
 *
 * The composable assembles the bar one character at a time so a single cell
 * can brighten while charging, which means it cannot call `PowerBar.readout`
 * and render the result. It renders a label resource, then the cells, then a
 * suffix resource. This is what stops those two resources drifting from the
 * formatter that is actually tested.
 *
 * Pure; no Android imports. Reads the shipped file.
 */
class PowerBarResourcesTest {

    private val strings: String by lazy {
        repoFile("app/src/main/res/values/strings.xml").readText()
    }

    @Test
    fun `the label resource is the formatter's label`() {
        assertEquals(PowerBar.LABEL, valueOf("launcher_bat_label"))
    }

    @Test
    fun `the suffix resource formats to the formatter's suffix`() {
        // The resource carries a positional argument and an escaped percent;
        // rendered with the level it must equal what readout appends.
        val template = valueOf("launcher_bat_suffix_fmt")
        for (p in listOf(0, 5, 56, 86, 100)) {
            assertEquals(
                "at $p%",
                PowerBar.suffix(p),
                template.replace("%1\$s", p.toString()).replace("%%", "%"),
            )
        }
    }

    @Test
    fun `the old hex field is gone from the copy`() {
        // It was a second rendering of the same number with no separator
        // before it. Asserted absent so it cannot come back by a revert.
        assertEquals(null, valueOfOrNull("launcher_pwr_label"))
        assertEquals(null, valueOfOrNull("launcher_pwr_suffix_fmt"))
    }

    private fun valueOf(name: String): String =
        valueOfOrNull(name) ?: error("no string resource '$name'")

    private fun valueOfOrNull(name: String): String? =
        Regex("""<string name="$name"[^>]*>(.*?)</string>""")
            .find(strings)
            ?.groupValues
            ?.get(1)

    private fun repoFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("could not find $relative above ${System.getProperty("user.dir")}")
    }
}
