package dev.molasses.core.setup

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One home check, read on resume, shown in three places. Read as text: the
 * activities, the screens and the reader compile nowhere here.
 */
class HomeRoleWiringTest {

    /** Code only: a comment that names the API is not a second reader. */
    private fun code(text: String) = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        .replace(Regex("""//[^\n]*"""), "")

    private fun main(path: String) = repoFile("app/src/main/java/dev/molasses/$path").readText()
    private val reader by lazy { main("ui/setup/HomeRoleCheck.kt") }
    private val flow by lazy { main("ui/setup/SetupFlow.kt") }
    private val launcher by lazy { main("ui/launcher/LauncherActivity.kt") }
    private val settings by lazy { main("ui/settings/SettingsActivity.kt") }
    private val screen by lazy { main("ui/settings/SettingsScreen.kt") }

    @Test
    fun `the reader asks the role, then the resolver, through the pure decision`() {
        val fn = functionBody(reader, "fun Context.isDefaultHome()")
        assertTrue(fn.contains("rm.isRoleAvailable(RoleManager.ROLE_HOME)) rm.isRoleHeld(RoleManager.ROLE_HOME) else null"))
        assertTrue(fn.contains("HomeRole.isDefault(roleHeld, ownPackage = packageName) {"))
        assertTrue(fn.contains("Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)"))
        assertTrue(fn.contains("resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)"))
        // The resolver is inside the lambda, so it is not queried when the role answered.
        assertTrue(fn.indexOf("resolveActivity(") > fn.indexOf("HomeRole.isDefault("))
    }

    @Test
    fun `it is the only home check in the app`() {
        val root = File(dev.molasses.core.repoRoot(), "app/src/main/java")
        val kt = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val roleReaders = kt.filter { code(it.readText()).contains("isRoleHeld(") }.map { it.name }
        assertEquals(listOf("HomeRoleCheck.kt"), roleReaders)
        // A HOME resolution anywhere else would be a second answer.
        val homeResolvers = kt.filter {
            val t = code(it.readText())
            t.contains("CATEGORY_HOME") && t.contains("resolveActivity(")
        }.map { it.name }
        assertEquals(listOf("HomeRoleCheck.kt"), homeResolvers)
        val callers = kt.filter { it.name != "HomeRoleCheck.kt" && code(it.readText()).contains(".isDefaultHome()") }.map { it.name }
        assertEquals("one caller: the setup controller every screen shares", listOf("SetupFlow.kt"), callers)
        assertTrue(functionBody(flow, "fun refresh(").contains("defaultHome = activity.isDefaultHome(),"))
    }

    @Test
    fun `both activities refresh it on resume`() {
        for ((name, text) in listOf("LauncherActivity" to launcher, "SettingsActivity" to settings)) {
            assertTrue(name, functionBody(text, "override fun onResume()").contains("setup.onResume()"))
            assertTrue(name, text.contains("private val setup = SetupFlowController(this) { settingsRepository }"))
        }
        assertTrue(functionBody(flow, "fun onResume()").contains("refresh()"))
    }

    @Test
    fun `no poll, no listener, no notification`() {
        for (text in listOf(reader, launcher, settings)) {
            for (banned in listOf("OnRoleHoldersChangedListener", "addOnRoleHoldersChangedListener", "NotificationManager", "NotificationCompat")) {
                assertFalse(banned, text.contains(banned))
            }
        }
        // The first-run flow's poll exists for grants it is waiting on, and
        // runs only while the flow is showing; nothing else repeats the read.
        assertEquals(1, Regex("""controller\.refresh\(\)""").findAll(flow).count())
    }

    @Test
    fun `the console shows one line until the role is back, from the shared field`() {
        assertTrue(launcher.contains("homeLost = !setup.grants.defaultHome,"))
        assertTrue(launcher.contains("onOpenHomeSettings = setup::openHomeSettings,"))
        val workspace = functionBody(launcher, "fun MainLauncherWorkspace(")
        val line = workspace.indexOf("if (homeLost) {")
        assertTrue(line >= 0)
        assertEquals("one line", 1, Regex("""R\.string\.launcher_home_lost""").findAll(launcher).count())
        assertTrue(workspace.indexOf("R.string.launcher_home_lost", line) > line)
        assertTrue("above the pager, so both pages show it", line < workspace.indexOf("HorizontalPager("))
        assertTrue(workspace.substring(line).substringBefore("Spacer(").contains(".clickable { onOpenHomeSettings() }"))
    }

    @Test
    fun `CFG SETUP names a lost role and routes to the home settings`() {
        assertTrue(settings.contains("defaultHome = setup.grants.defaultHome,"))
        assertTrue(settings.contains("onOpenHomeSettings = setup::openHomeSettings,"))
        val row = screen.substring(screen.indexOf("item(CfgRowKey.body(Section.SETUP, \"home\")) {")).substringBefore("\n            }\n")
        assertTrue(row.contains("title = homeRowTitle(defaultHome),"))
        assertTrue(row.contains("subtitle = homeRowBody(defaultHome),"))
        assertTrue(row.contains("satisfied = defaultHome,"))
        assertTrue(row.contains("onClick = onOpenHomeSettings,"))
        assertTrue(screen.contains("if (defaultHome) R.string.settings_home_title else R.string.settings_home_lost_title"))
        assertTrue(screen.contains("if (defaultHome) R.string.settings_home_body else R.string.onboarding_home_settings"))
        val strings = repoFile("app/src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"settings_home_lost_title\">Jitter is not your home app</string>"))
        assertTrue(strings.contains("<string name=\"onboarding_home_settings\" translatable=\"false\">[ OPEN HOME SETTINGS ]</string>"))
        assertTrue(flow.contains("fun openHomeSettings() = openSettings(Intent(Settings.ACTION_HOME_SETTINGS))"))
    }
}
