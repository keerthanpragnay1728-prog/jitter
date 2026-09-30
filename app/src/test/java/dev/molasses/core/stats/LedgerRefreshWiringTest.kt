package dev.molasses.core.stats

import dev.molasses.core.functionBody
import dev.molasses.core.repoFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ledger page re-reads the day when it is shown and on resume, and says
 * what its distribution list leaves out. Read as text: the launcher compiles
 * nowhere here.
 */
class LedgerRefreshWiringTest {

    private val launcher by lazy { repoFile("app/src/main/java/dev/molasses/ui/launcher/LauncherActivity.kt").readText() }
    private val view by lazy { functionBody(launcher, "fun TextualWellbeingView(") }

    @Test
    fun `the day is re-read on every show of the page and on every resume`() {
        assertTrue(launcher.contains("shown = pagerState.settledPage == PAGE_LEDGER,"))
        assertTrue(view.contains("LaunchedEffect(shown, resumes) {"))
        assertFalse("a query keyed on nothing goes stale while the page stays composed", view.contains("LaunchedEffect(Unit)"))
        assertTrue(view.contains("if (event == Lifecycle.Event.ON_RESUME) resumes++"))
    }

    @Test
    fun `every read assigns every value, so a refresh can clear as well as add`() {
        val effect = view.substring(view.indexOf("LaunchedEffect(shown, resumes) {"))
        for (assign in listOf("screenTimeMs = ", "underFloor = ", "pastCap = ", "usageRecords = if (", "unlockCount = ")) {
            assertTrue(assign, effect.contains(assign))
        }
        assertTrue(effect.contains("day?.distribution(DISTRIBUTION_ROWS, DISTRIBUTION_MIN_MS)"))
    }

    @Test
    fun `what the floor and the cap hide is counted on the page`() {
        assertTrue(view.contains("R.string.ledger_distribution_under_floor_fmt"))
        assertTrue(view.contains("R.string.ledger_distribution_past_cap_fmt"))
        assertTrue("no usage is claimed only when there was none", view.contains("if (usageRecords.isEmpty() && underFloor == 0) {"))
        assertTrue(launcher.contains("private const val DISTRIBUTION_MIN_MS = 60_000L"))
    }
}
