package dev.molasses.core.support

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import dev.molasses.core.repoRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [ REPORT A PROBLEM ], as wired. Read as text: the manifest, CFG and the
 * launcher compile nowhere here.
 */
class ReportProblemWiringTest {

    private val manifest by lazy { repoFile("app/src/main/AndroidManifest.xml").readText() }
    private val screen by lazy { repoFile("app/src/main/java/dev/molasses/ui/settings/SettingsScreen.kt").readText() }
    private val launch by lazy { repoFile("app/src/main/java/dev/molasses/ui/settings/ReportProblemLaunch.kt").readText() }

    @Test
    fun `the manifest queries VIEW with BROWSABLE on https`() {
        val queries = manifest.substring(manifest.indexOf("<queries>"), manifest.indexOf("</queries>"))
        val entry = Regex("""<intent>(.*?)</intent>""", RegexOption.DOT_MATCHES_ALL).findAll(queries)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains("android.intent.action.VIEW") }
        assertTrue("no VIEW entry in <queries>", entry != null)
        assertTrue(entry!!.contains("""<category android:name="android.intent.category.BROWSABLE"/>"""))
        assertTrue(entry.contains("""<data android:scheme="https"/>"""))
    }

    @Test
    fun `the permission set is still exactly the six, and INTERNET is absent`() {
        val declared = Regex("""<uses-permission[^>]*android:name="([^"]+)"""")
            .findAll(manifest).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf(
                "android.permission.PACKAGE_USAGE_STATS",
                "android.permission.ACTIVITY_RECOGNITION",
                "android.permission.RECEIVE_BOOT_COMPLETED",
                "android.permission.VIBRATE",
                "android.permission.USE_EXACT_ALARM",
                "android.permission.SCHEDULE_EXACT_ALARM",
            ),
            declared,
        )
        assertFalse(manifest.contains("android.permission.INTERNET"))
    }

    @Test
    fun `CFG shows the row through the shared resolve check and the shared intent`() {
        assertTrue(screen.contains("context.packageManager.canResolve(ReportProblem.spec().toIntent())"))
        assertTrue(screen.contains("ReportProblem.row(resolvable = reportResolvable, launchFailed = reportFailed)"))
        assertTrue(screen.contains("if (reportRow is ReportProblem.Row.Shown) {"))
        assertTrue(screen.contains("reportFailed = !launchReportProblem(context)"))
        assertTrue(screen.contains("R.string.settings_report_problem_failed"))
    }

    @Test
    fun `the row is its own row after the last section, not inside DBG or a section`() {
        val row = screen.indexOf("item(CfgRowKey.chrome(\"report-problem\"))")
        val lastSection = screen.indexOf("section = Section.TRY,")
        assertTrue(row > lastSection)
        assertFalse(repoFile("app/src/main/java/dev/molasses/ui/settings/DebugScreen.kt").readText().contains("ReportProblem"))
        // After the TRY section's block has closed, at the builder's own
        // indentation rather than a section's.
        val between = screen.substring(lastSection, row)
        assertTrue(between.contains("        }\n\n        // Its own row after the last section"))
        assertTrue(screen.contains("\n        if (reportRow is ReportProblem.Row.Shown) {\n            item(CfgRowKey.chrome(\"report-problem\")) {"))
    }

    @Test
    fun `the row is centred across the list with about 24dp above and below, in its old style`() {
        val row = screen.substring(screen.indexOf("item(CfgRowKey.chrome(\"report-problem\"))"))
            .substringBefore("\n        }\n    }\n")
        assertTrue(row.contains(".fillMaxWidth()\n                        .padding(vertical = 24.dp),"))
        assertTrue(row.contains("horizontalAlignment = Alignment.CenterHorizontally,"))
        assertEquals("the label and the note are both centred", 2, Regex("textAlign = TextAlign.Center,").findAll(row).count())
        assertTrue(row.contains("style = MaterialTheme.typography.labelMedium,\n                        color = MaterialTheme.colorScheme.primary,"))
    }

    @Test
    fun `the launch is inside a try with both catches, and logs`() {
        val fn = launch.substring(launch.indexOf("fun launchReportProblem("))
        val tryAt = fn.indexOf("= try {")
        val start = fn.indexOf("context.startActivity(ReportProblem.spec().toIntent())")
        assertTrue(tryAt in 0 until start)
        assertTrue(fn.indexOf("} catch (e: ActivityNotFoundException) {") > start)
        assertTrue(fn.indexOf("} catch (e: SecurityException) {") > start)
        assertEquals(2, Regex("""Log\.w\(""").findAll(fn).count())
        assertTrue(launch.contains("Intent(action, Uri.parse(url)).addCategory(category)"))
    }

    @Test
    fun `one resolve check and one URL in the app`() {
        val main = File(repoRoot(), "app/src/main/java")
        val kt = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        // SetupFlow asks a different question (which activity is the default
        // home), so its resolveActivity is not a second "can this resolve".
        val resolvers = kt.filter { it.readText().contains("resolveActivity(intent") }.map { it.name }
        assertEquals(listOf("IntentResolution.kt"), resolvers)
        val shared = functionBody(repoFile("app/src/main/java/dev/molasses/ui/IntentResolution.kt").readText(), "fun PackageManager.canResolve(")
        assertTrue(shared.contains("if (intent.component != null) 0 else PackageManager.MATCH_DEFAULT_ONLY"))
        assertTrue(repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherActivity.kt").readText()
            .contains("private fun canResolve(intent: Intent): Boolean = packageManager.canResolve(intent)"))
        val urls = kt.filter { it.readText().contains("github.com/keerthanpragnay1728-prog/jitter/issues") }.map { it.name }
        assertEquals(listOf("ReportProblem.kt"), urls)
    }
}
