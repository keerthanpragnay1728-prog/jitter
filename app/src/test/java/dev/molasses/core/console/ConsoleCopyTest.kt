package dev.molasses.core.console

import dev.molasses.core.repoFile
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every line Bit can say has copy.
 *
 * A line whose id has no string renders as an empty row, and because the caps
 * count renders it would spend one of three an hour on nothing. The mapping
 * from id to resource lives in the Android layer and cannot run here, so this
 * asserts the resource exists by name instead.
 *
 * Pure; no Android imports. Reads the shipped file.
 */
class ConsoleCopyTest {

    private val strings: String by lazy {
        repoFile("app/src/main/res/values/strings.xml").readText()
    }

    @Test
    fun `every declared line has copy`() {
        assertTrue("ConsoleIds.ALL is empty", ConsoleIds.ALL.isNotEmpty())
        for (id in ConsoleIds.ALL) {
            assertTrue(
                "no console_$id string; that line would render as an empty row " +
                    "and still spend one of three an hour",
                Regex("""<string name="console_$id"[^>]*>""").containsMatchIn(strings),
            )
        }
    }

    @Test
    fun `the prompt's two answers have copy`() {
        for (name in listOf("console_do_it", "console_nah")) {
            assertTrue(name, Regex("""<string name="$name"[^>]*>""").containsMatchIn(strings))
        }
    }

    @Test
    fun `ids are stable identifiers, not resource names`() {
        // A stored resource id would point at unrelated copy after a rebuild,
        // and the once-per-cycle rule would be keyed on a number that
        // silently changed meaning.
        for (id in ConsoleIds.ALL) {
            assertTrue(id, id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '_' })
        }
    }

}
