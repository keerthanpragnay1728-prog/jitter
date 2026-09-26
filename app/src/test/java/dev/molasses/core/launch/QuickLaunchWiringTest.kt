package dev.molasses.core.launch

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quick-launch rows are stored, edited and drawn through [QuickLaunch].
 * Read as text, because none of the three layers compiles here.
 */
class QuickLaunchWiringTest {

    @Test
    fun `the proto carries the list and its chosen flag on the next free numbers`() {
        val proto = repoFile("app/src/main/proto/cycle_state.proto").readText()
        assertTrue(proto.contains("repeated string quick_launch_packages = 37;"))
        assertTrue(proto.contains("bool quick_launch_chosen = 38;"))
    }

    @Test
    fun `the store edits inside one transaction, prunes, and always sets the flag`() {
        val store = repoFile("app/src/main/java/dev/molasses/data/datastore/CycleStateStore.kt").readText()
        val body = functionBody(store, "suspend fun editQuickLaunch(")
        assertTrue(body.contains("store.updateData"))
        assertTrue(body.contains("QuickLaunch.resolve("))
        assertTrue(body.contains("QuickLaunch.pruned(next, isLaunchable)"))
        assertTrue(body.contains(".setQuickLaunchChosen(true)"))
        assertTrue("a refusal must leave the state untouched", body.contains("?: return@updateData state"))
    }

    @Test
    fun `nothing else writes the list`() {
        val store = repoFile("app/src/main/java/dev/molasses/data/datastore/CycleStateStore.kt").readText()
        assertTrue(Regex("""addAllQuickLaunchPackages\(""").findAll(store).count() == 1)
    }

    @Test
    fun `the console draws only visible rows, and the messages row keeps its selector`() {
        val launcher = repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherActivity.kt").readText()
        assertTrue(launcher.contains("QuickLaunch.visible(quickLaunch) { it in appLabels }"))
        assertTrue(launcher.contains("QuickLaunch.BuiltIn.MESSAGES -> Favourite(R.string.launcher_fav_messages)"))
        assertTrue(launcher.contains("else -> messagingChoices = clients"))
        assertFalse("the fixed five-row list is gone", launcher.contains("            Favourite(R.string.launcher_fav_phone, onDialer)\n            // [messages]"))
    }

    @Test
    fun `an app row is its label, bracketed and lowercase`() {
        val launcher = repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherActivity.kt").readText()
        assertTrue(launcher.contains("R.string.launcher_fav_app_fmt, appLabels.getValue(entry.pkg).lowercase()"))
        val strings = repoFile("app/src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("""<string name="launcher_fav_app_fmt" translatable="false">[%1${'$'}s]</string>"""))
    }
}
