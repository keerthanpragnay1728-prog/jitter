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

    /** The section builder's calls, in order, and the report row's position among them. */
    private val cfgBuilder by lazy {
        screen.substring(screen.indexOf("val cfgRows = buildCfgRows {"))
            .substringBefore("\n    // The letters the rail offers")
    }

    @Test
    fun `the row is the last row, directly after TRY IT, and not in DBG`() {
        val sections = Regex("""section = Section\.(\w+),""").findAll(cfgBuilder).map { it.groupValues[1] }.toList()
        assertEquals("TRY", sections.last())
        val row = cfgBuilder.indexOf("item(CfgRowKey.chrome(\"report-problem\"))")
        assertTrue(row > cfgBuilder.indexOf("section = Section.TRY,"))
        // Nothing after it but the builder's close.
        val after = cfgBuilder.substring(row + "item(".length)
        assertFalse(after.contains("item("))
        assertFalse(after.contains("section("))
        // At the builder's own indentation, outside TRY IT's block.
        assertTrue(cfgBuilder.contains("\n        if (reportRow is ReportProblem.Row.Shown) {\n            item(CfgRowKey.chrome(\"report-problem\")) {"))
        assertFalse(repoFile("app/src/main/java/dev/molasses/ui/settings/DebugScreen.kt").readText().contains("ReportProblem"))
    }

    @Test
    fun `the row is not an accordion section and never takes part in single-open`() {
        val accordion = repoFile("app/src/main/java/dev/molasses/core/settings/CfgAccordion.kt").readText()
        val sectionEnum = accordion.substring(accordion.indexOf("enum class Section")).substringBefore("}")
        assertFalse("no REPORT section", sectionEnum.contains("REPORT"))
        val row = cfgBuilder.substring(cfgBuilder.indexOf("if (reportRow is ReportProblem.Row.Shown) {"))
        assertFalse(row.contains("CfgAccordion."))
        assertFalse(row.contains("accordion"))
        assertFalse(row.contains("CfgRowKey.section("))
        assertFalse(row.contains("SectionHeader("))
        assertTrue(row.contains("LinkRow("))
    }

    @Test
    fun `the row is laid out as a section header, left-aligned, with its own glyph`() {
        val link = functionBody(screen, "private fun LinkRow(")
        val header = functionBody(screen, "private fun SectionHeader(")
        // The same spacing above and below as every section header.
        for (spacing in listOf("Spacer(Modifier.height(20.dp))", "Spacer(Modifier.height(8.dp))")) {
            assertTrue(spacing, header.contains(spacing) && link.contains(spacing))
        }
        // The same row and label style.
        val rowShape = ".fillMaxWidth()\n            .clickable(onClick = "
        assertTrue(header.contains(rowShape + "onToggle)") && link.contains(rowShape + "onClick)"))
        val label = ".uppercase(),\n            style = MaterialTheme.typography.labelMedium,\n            color = MaterialTheme.colorScheme.primary,"
        assertTrue(header.contains(label) && link.contains(label))
        assertTrue(link.contains("Spacer(Modifier.weight(1f))"))
        // Left-aligned: no centring anywhere in it.
        for (centring in listOf("TextAlign", "CenterHorizontally", "Arrangement.Center", "padding(vertical = 24.dp)")) {
            assertFalse(centring, link.contains(centring))
        }
        // Its own glyph, which never flips: not the accordion's chevron.
        assertTrue(link.contains("ReportProblem.ROW_GLYPH,"))
        assertFalse(link.contains("CfgAccordion.chevron("))
        assertEquals(">", ReportProblem.ROW_GLYPH)
        // The failure note under the row, left-aligned.
        assertTrue(link.indexOf("stringResource(failureNote)") > link.indexOf("ReportProblem.ROW_GLYPH"))
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
