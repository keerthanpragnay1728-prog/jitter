package dev.molasses.core.launch

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutLadderTest {

    @Test
    fun `every probed action is declared in the manifest queries block`() {
        // The whole reason PROBED_ACTIONS exists. On API 30+ resolveActivity
        // returns null for an undeclared action on a device that handles it,
        // so a ladder the manifest does not know about collapses to nothing
        // silently. That is how the Wellbeing link failed on every device for
        // as long as it existed.
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        val queries = Regex("""<queries>(.*?)</queries>""", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)?.groupValues?.get(1)
            ?: error("no queries block in the manifest")

        assertTrue("PROBED_ACTIONS is empty; the derivation has rotted", ShortcutLadder.PROBED_ACTIONS.isNotEmpty())
        for (action in ShortcutLadder.PROBED_ACTIONS) {
            assertTrue(
                "$action is probed but not declared in <queries>",
                queries.contains("""android:name="$action""""),
            )
        }
    }

    @Test
    fun `every probed category is declared too`() {
        // An action entry without the category is not the same query. MAIN on
        // its own matches almost everything and is not what these rungs ask.
        val manifest = repoFile("app/src/main/AndroidManifest.xml").readText()
        val queries = Regex("""<queries>(.*?)</queries>""", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)?.groupValues?.get(1)!!
        val categories = ShortcutLadder.ALL.flatten().mapNotNull { it.category }.toSet() +
            ShortcutLadder.MESSAGING_CATEGORY
        for (category in categories) {
            assertTrue(
                "$category is probed but not declared in <queries>",
                queries.contains("""android:name="$category""""),
            )
        }
    }

    @Test
    fun `a rung is an action or a package, never both and never neither`() {
        for (ladder in ShortcutLadder.ALL) {
            for (rung in ladder) {
                assertTrue(
                    rung.toString(),
                    (rung.action != null) != (rung.pkg != null),
                )
            }
        }
    }

    @Test
    fun `a category only ever rides on an action`() {
        // A category on a package rung would be silently ignored, which is a
        // rung that does not do what it reads as doing.
        for (rung in ShortcutLadder.ALL.flatten()) {
            if (rung.category != null) assertTrue(rung.toString(), rung.action != null)
        }
    }

    @Test
    fun `the specific rungs come before the generic ones`() {
        // The order is the point. SHOW_ALARMS lands on the alarm list, which
        // is what [clock] means; APP_CLOCK opens whatever screen the clock app
        // opens to. A ladder that tried them the other way round would work
        // everywhere and be wrong everywhere.
        assertEquals("android.intent.action.SHOW_ALARMS", ShortcutLadder.CLOCK.first().action)
        for (ladder in ShortcutLadder.ALL) {
            val firstPkg = ladder.indexOfFirst { it.pkg != null }
            val lastAction = ladder.indexOfLast { it.action != null }
            if (firstPkg >= 0) {
                assertTrue(
                    "a package rung precedes an action rung in $ladder",
                    firstPkg > lastAction,
                )
            }
        }
    }

    @Test
    fun `no ladder is empty, and none repeats a rung`() {
        for (ladder in ShortcutLadder.ALL) {
            assertTrue(ladder.isNotEmpty())
            assertEquals(ladder.size, ladder.distinct().size)
        }
    }

    @Test
    fun `the chat list has no duplicates and names no package twice`() {
        assertEquals(
            ShortcutLadder.CHAT_PACKAGES.size,
            ShortcutLadder.CHAT_PACKAGES.distinct().size,
        )
    }

    @Test
    fun `the chat list and the clock list share nothing`() {
        // A crossover would mean an app offered under [messages] that the
        // clock row also launches, which is a copy-paste fault rather than a
        // decision.
        val ladderPackages = ShortcutLadder.ALL.flatten().mapNotNull { it.pkg }.toSet()
        assertEquals(emptySet<String>(), ladderPackages intersect ShortcutLadder.CHAT_PACKAGES.toSet())
    }

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
