package dev.molasses.core.support

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportProblemTest {

    @Test
    fun `the intent spec is VIEW, BROWSABLE and the exact issues URL, from one function`() {
        val spec = ReportProblem.spec()
        assertEquals("android.intent.action.VIEW", spec.action)
        assertEquals("android.intent.category.BROWSABLE", spec.category)
        assertEquals("https://github.com/keerthanpragnay1728-prog/jitter/issues", spec.url)
        assertEquals("the same spec every time", spec, ReportProblem.spec())
    }

    @Test
    fun `the row is shown when the intent resolves and hidden when it does not`() {
        assertEquals(ReportProblem.Row.Shown(failureNote = false), ReportProblem.row(resolvable = true, launchFailed = false))
        assertEquals(ReportProblem.Row.Hidden, ReportProblem.row(resolvable = false, launchFailed = false))
    }

    @Test
    fun `a failed launch keeps the row and adds the note, and an unresolvable row stays hidden`() {
        assertEquals(ReportProblem.Row.Shown(failureNote = true), ReportProblem.row(resolvable = true, launchFailed = true))
        assertEquals(ReportProblem.Row.Hidden, ReportProblem.row(resolvable = false, launchFailed = true))
    }
}
