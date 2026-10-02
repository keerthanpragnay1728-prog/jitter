package dev.molasses.core.settings

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The untrack sunset, as wired. Read as text: the store, the repository, the
 * settings screen and the service compile nowhere here.
 */
class UntrackSunsetWiringTest {

    private val proto by lazy { repoFile("app/src/main/proto/cycle_state.proto").readText() }
    private val store by lazy { repoFile("app/src/main/java/dev/molasses/data/datastore/CycleStateStore.kt").readText() }
    private val repo by lazy { repoFile("app/src/main/java/dev/molasses/data/repo/SettingsRepository.kt").readText() }
    private val service by lazy { repoFile("app/src/main/java/dev/molasses/monitor/MolassesAccessibilityService.kt").readText() }
    private val screen by lazy { repoFile("app/src/main/java/dev/molasses/ui/settings/SettingsScreen.kt").readText() }
    private val panel by lazy { repoFile("app/src/main/java/dev/molasses/ui/settings/UntrackCoolingOffPanel.kt").readText() }

    @Test
    fun `the store field is the next free number, and the entry carries the deadline on all three clocks`() {
        val cycleState = proto.substring(proto.indexOf("message CycleState {")).substringBefore("\n}\n")
        val numbers = Regex("""=\s*(\d+);""").findAll(cycleState).map { it.groupValues[1].toInt() }.toList()
        assertEquals(42, numbers.max())
        assertEquals("42 is used once", 1, numbers.count { it == 42 })
        assertTrue(cycleState.contains("repeated UntrackSunsetEntry untrack_sunsets = 42;"))
        val entry = proto.substring(proto.indexOf("message UntrackSunsetEntry {")).substringBefore("}")
        for (field in listOf("string pkg = 1;", "int64 due_wall_ms = 2;", "int64 due_elapsed_ms = 3;", "int32 due_boot_id = 4;")) {
            assertTrue(field, entry.contains(field))
        }
        val mapping = repoFile("app/src/main/java/dev/molasses/data/datastore/UntrackSunsetMapping.kt").readText()
        for (half in listOf("setDueWallMs(deadline.wallMs)", "setDueElapsedMs(deadline.elapsedMs)", "setDueBootId(deadline.bootId)",
            "wallMs = dueWallMs, elapsedMs = dueElapsedMs, bootId = dueBootId")) {
            assertTrue(half, mapping.contains(half))
        }
    }

    @Test
    fun `the confirm stores the sunset in the untrack's own transaction, and only from the cooling-off`() {
        val toggle = functionBody(store, "suspend fun toggleTarget(")
        assertTrue(toggle.contains("UntrackSunset.afterToggle("))
        assertTrue(toggle.contains("grantsSunset = grantsSunset && onlyIfTracked,"))
        assertTrue(toggle.contains(".addAllUntrackSunsets(sunsets.map { it.toProto() })"))
        assertTrue(repo.contains("store.toggleTarget(pkg, nowStamped(), onlyIfTracked = true, grantsSunset = sunsetInScope(pkg))"))
        assertTrue("turning a target on grants nothing", repo.contains("suspend fun toggleTarget(pkg: String) = store.toggleTarget(pkg, nowStamped())"))
    }

    @Test
    fun `scope reads the platform category once, and the panel asks the same function the write does`() {
        val scope = functionBody(repo, "fun sunsetInScope(")
        assertTrue(scope.contains("ApplicationInfo.CATEGORY_SOCIAL"))
        assertTrue(scope.contains("UntrackSunset.inScope(pkg, categorySocial = social)"))
        val vm = repoFile("app/src/main/java/dev/molasses/ui/settings/SettingsViewModel.kt").readText()
        assertTrue(vm.contains("fun sunsetInScope(pkg: String): Boolean = repo.sunsetInScope(pkg)"))
        assertTrue(screen.contains("val sunsetScope = remember(state.pkg) { vm.sunsetInScope(state.pkg) }"))
        assertTrue(screen.contains("sunsetDays = if (sunsetScope) UntrackSunset.GRACE_DAYS else null,"))
    }

    @Test
    fun `the re-arm resolves the list and decides inside the transaction`() {
        val rearm = functionBody(store, "suspend fun rearmSunsets(")
        assertTrue(rearm.contains("store.updateData { state ->"))
        assertTrue(rearm.contains("TargetScope.resolve(selection, DEFAULT_TARGETS)"))
        assertTrue(rearm.contains("UntrackSunset.rearm(current, state.untrackSunsetsList.map { it.toSunset() }, now)"))
        assertTrue("nothing due writes nothing", rearm.contains("?: return@updateData state"))
        assertTrue(rearm.contains(".setTargetsChosen(true)"))
    }

    @Test
    fun `the service re-arms on connect, before the event gate opens, and at every checkpoint`() {
        val connect = functionBody(service, "override fun onServiceConnected()")
        val seed = connect.indexOf("leases = LeaseManager.of(")
        val rearm = connect.indexOf("rearmSunsets(\"connect\")")
        assertTrue(seed in 0 until rearm)
        assertTrue(rearm < connect.indexOf("observeSettings()"))
        assertTrue(rearm < connect.indexOf("ready = true"))
        assertTrue(functionBody(service, "private fun startCheckpointing()").contains("rearmSunsets(\"checkpoint\")"))
        val fn = functionBody(service, "private suspend fun rearmSunsets(")
        assertTrue(fn.contains("cycleStore.rearmSunsets(nowStamped())"))
        assertTrue(fn.contains("catch (e: CancellationException) {\n            throw e"))
    }

    @Test
    fun `no alarm, no worker, and no copy of the sunsets held by the service`() {
        for (banned in listOf("WorkManager", "AlarmManager", "untrackSunsets")) {
            assertFalse(banned, functionBody(service, "private suspend fun rearmSunsets(").contains(banned))
        }
        assertFalse("the service reads sunsets only through the store's transaction", service.contains("untrackSunsetsList"))
        assertFalse(service.contains("var sunsets"))
    }

    @Test
    fun `CFG says when tracking resumes, and the panel says it before the answers`() {
        assertTrue(screen.contains("R.string.settings_target_sunset_fmt,"))
        assertTrue(screen.contains("if (!tracked && resumesAtWallMs != null) {"))
        val body = functionBody(panel, "fun UntrackCoolingOffPanel(")
        val note = body.indexOf("if (sunsetDays != null) {")
        assertTrue(note >= 0 && body.indexOf("R.string.untrack_sunset_fmt", note) > note)
        assertTrue("stated while the answer can still be KEEP TRACKING", note < body.indexOf("R.string.untrack_confirm"))
        val strings = repoFile("app/src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains(">Untracked. Tracking resumes %1\$s, or sooner after a restart.</string>"))
    }

    @Test
    fun `no user-visible text hardcodes the sunset's length, and the copy follows the constant`() {
        val strings = repoFile("app/src/main/res/values/strings.xml").readText()
        // String values only: an XML comment is not shown to anyone.
        val values = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(strings).associate { it.groupValues[1] to it.groupValues[2] }
        val sevenDays = Regex("""\b(7|seven)[\s-]*days?\b""", RegexOption.IGNORE_CASE)
        assertEquals(emptyList<String>(), values.filterValues { sevenDays.containsMatchIn(it) }.keys.toList())
        // The panel's line takes the number as an argument, fed from the constant.
        assertTrue(values.getValue("untrack_sunset_fmt").contains("%1\$s DAYS"))
        assertTrue(panel.contains("stringResource(R.string.untrack_sunset_fmt, sunsetDays.toString())"))
        assertTrue(screen.contains("sunsetDays = if (sunsetScope) UntrackSunset.GRACE_DAYS else null,"))
        // The CFG row names a date, never a count of days.
        assertFalse(Regex("""\d""").containsMatchIn(values.getValue("settings_target_sunset_fmt").replace("%1\$s", "")))
        // The README's sunset paragraph says the constant's number, spelled out.
        val words = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
        val readme = repoFile("README.md").readText()
        val paragraph = readme.substring(readme.indexOf("**Untracking a social app is temporary.**")).substringBefore("\n\n")
        assertTrue(paragraph, paragraph.replace("\n", " ").contains("after ${words[UntrackSunset.GRACE_DAYS]} days"))
        assertFalse(sevenDays.containsMatchIn(paragraph.replace("\n", " ")))
        // And the store text does not state it at all.
        assertFalse(sevenDays.containsMatchIn(repoFile("fastlane/metadata/android/en-US/full_description.txt").readText()))
    }
}
