package dev.molasses.core.settings

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cooling-off is wired the way [UntrackCoolingOff] says. Read as text,
 * because the settings layer is compiled by nothing here.
 */
class UntrackCoolingOffWiringTest {

    private val screen by lazy { repoFile("app/src/main/java/dev/molasses/ui/settings/SettingsScreen.kt").readText() }
    private val panel by lazy { repoFile("app/src/main/java/dev/molasses/ui/settings/UntrackCoolingOffPanel.kt").readText() }

    @Test
    fun `nothing persists it, so rotation and process death abandon it`() {
        assertTrue(screen.contains("var coolingOff by remember { mutableStateOf<UntrackCoolingOff.State?>(null) }"))
        assertFalse(screen.contains("coolingOff by rememberSaveable"))
    }

    @Test
    fun `off starts the cooling-off and on applies at once`() {
        val toggle = screen.substring(screen.indexOf("onToggleTarget = {"))
        val branch = toggle.substring(0, toggle.indexOf("onHorizon"))
        assertTrue(branch.contains("if (app.pkg in targets) {"))
        assertTrue(branch.contains("coolingOff = UntrackCoolingOff.start("))
        assertTrue(branch.contains("} else {\n                            vm.toggleTarget(app.pkg)"))
    }

    @Test
    fun `the answers exist only at zero`() {
        val body = functionBody(panel, "fun UntrackCoolingOffPanel(")
        val ready = body.indexOf("UntrackCoolingOff.Phase.Ready -> {")
        val confirm = body.indexOf("R.string.untrack_confirm")
        val keep = body.indexOf("R.string.untrack_keep")
        assertTrue(ready >= 0 && confirm > ready && keep > ready)
    }

    @Test
    fun `the last target is judged against installed apps, from the live sets`() {
        assertTrue(screen.contains("lastTarget = UntrackCoolingOff.isLastTarget(state.pkg, targets) { pkg ->"))
        val call = screen.substring(screen.indexOf("lastTarget = UntrackCoolingOff.isLastTarget("))
            .substringBefore("onConfirmRemove")
        assertTrue(call.contains("installed.any { it.pkg == pkg }"))
        assertTrue(screen.contains("val installed by vm.installed.collectAsStateWithLifecycle()"))
        assertTrue(screen.contains("val targets by vm.targets.collectAsStateWithLifecycle()"))
    }

    @Test
    fun `the last target line sits in the visible column, between the target and the count`() {
        val body = functionBody(panel, "fun UntrackCoolingOffPanel(")
        // The one column the panel draws, inside the full-screen box.
        val box = body.indexOf("Box(")
        val column = body.indexOf("Column(", box)
        assertTrue(box >= 0 && column > box)
        assertTrue(body.substring(box, column).contains(".fillMaxSize()"))
        assertTrue(body.substring(box, column).contains("contentAlignment = Alignment.Center"))
        val target = body.indexOf("R.string.untrack_target_fmt", column)
        val gate = body.indexOf("if (lastTarget) {", column)
        val line = body.indexOf("R.string.untrack_last_target", column)
        val count = body.indexOf("when (val p = phase)", column)
        assertTrue(target in (column + 1) until gate && gate < line && line < count)
        assertEquals(1, Regex("""R\.string\.untrack_last_target""").findAll(body).count())
        // Nothing that could hide or clip it on the way.
        val lineBlock = body.substring(gate, count)
        for (hider in listOf("alpha(", "maxLines", "height(0", "clip", "Visibility", "overflow")) {
            assertFalse("$hider on the last target line", lineBlock.contains(hider))
        }
        assertTrue(lineBlock.contains("color = PhosphorGreen"))
        val strings = repoFile("app/src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("name=\"untrack_last_target\""))
    }

    @Test
    fun `pausing and back both abandon it`() {
        assertTrue(panel.contains("if (event == Lifecycle.Event.ON_PAUSE) leave(UntrackCoolingOff.Leave.PAUSED)"))
        assertTrue(panel.contains("BackHandler { leave(UntrackCoolingOff.Leave.BACK) }"))
    }

    @Test
    fun `the commit is remove only and runs the lock guard inside the transaction`() {
        assertTrue(screen.contains("vm.untrackTarget(state.pkg)"))
        val store = repoFile("app/src/main/java/dev/molasses/data/datastore/CycleStateStore.kt").readText()
        val body = functionBody(store, "suspend fun toggleTarget(")
        val update = body.indexOf("store.updateData")
        val onlyIf = body.indexOf("if (onlyIfTracked && pkg !in current) return@updateData state")
        val lock = body.indexOf("TargetLock.toggled(")
        assertTrue("remove-only and the lock guard both inside updateData, in that order", update in 0 until onlyIf && onlyIf < lock)
        val repo = repoFile("app/src/main/java/dev/molasses/data/repo/SettingsRepository.kt").readText()
        assertTrue(repo.contains("store.toggleTarget(pkg, nowStamped(), onlyIfTracked = true)"))
    }
}
