package dev.molasses.core.settings

import dev.molasses.core.settings.CfgAccordion.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two things about CFG's accordion that are worth more than a comment:
 * the pinned section survives everything, and nothing is remembered.
 */
class CfgAccordionTest {

    private val group = Section.entries.filter { it != CfgAccordion.PINNED }

    @Test
    fun `CFG opens with setup expanded and nothing else`() {
        val state = CfgAccordion.initial()
        assertTrue(CfgAccordion.isOpen(state, CfgAccordion.PINNED))
        for (section in group) {
            assertFalse("$section should start closed", CfgAccordion.isOpen(state, section))
        }
    }

    @Test
    fun `initial is a constant, so nothing can be persisted into it`() {
        // The host holds this in remember rather than rememberSaveable. This
        // is the assertion a later "helpful" persistence has to argue with:
        // whatever the user had open last time, CFG opens the same way.
        assertEquals(CfgAccordion.initial(), CfgAccordion.initial())
        assertEquals(CfgAccordion.State(setupOpen = true, open = null), CfgAccordion.initial())
    }

    @Test
    fun `opening a section closes the one that was open`() {
        var state = CfgAccordion.initial()
        state = CfgAccordion.toggle(state, Section.TARGETS)
        assertTrue(CfgAccordion.isOpen(state, Section.TARGETS))

        state = CfgAccordion.toggle(state, Section.GATE)
        assertTrue(CfgAccordion.isOpen(state, Section.GATE))
        assertFalse(CfgAccordion.isOpen(state, Section.TARGETS))
    }

    @Test
    fun `at most one of the group is ever open`() {
        // Swept rather than sampled, because the invariant is the whole
        // reason this is a type instead of eight booleans.
        var state = CfgAccordion.initial()
        for (section in group) {
            state = CfgAccordion.toggle(state, section)
            val open = group.filter { CfgAccordion.isOpen(state, it) }
            assertEquals("after opening $section: $open", 1, open.size)
        }
    }

    @Test
    fun `tapping an open section closes it`() {
        var state = CfgAccordion.toggle(CfgAccordion.initial(), Section.GATE)
        state = CfgAccordion.toggle(state, Section.GATE)
        assertFalse(CfgAccordion.isOpen(state, Section.GATE))
        assertNull(state.open)
    }

    @Test
    fun `nothing in the group can close setup`() {
        // The one that matters on a fresh install. Setup is the permission
        // checklist and the live service state, so a user who opens TARGET
        // APPS to pick an app must not lose the only row on screen that says
        // why nothing is happening yet.
        var state = CfgAccordion.initial()
        for (section in group) {
            state = CfgAccordion.toggle(state, section)
            assertTrue(
                "opening $section closed setup",
                CfgAccordion.isOpen(state, CfgAccordion.PINNED),
            )
            state = CfgAccordion.toggle(state, section)
            assertTrue(
                "closing $section closed setup",
                CfgAccordion.isOpen(state, CfgAccordion.PINNED),
            )
        }
    }

    @Test
    fun `setup can still be collapsed deliberately`() {
        // A panel nobody can ever close reads as a bug, and someone past
        // setup should be able to put it away.
        val state = CfgAccordion.toggle(CfgAccordion.initial(), CfgAccordion.PINNED)
        assertFalse(CfgAccordion.isOpen(state, CfgAccordion.PINNED))
    }

    @Test
    fun `collapsing setup does not open or close anything else`() {
        var state = CfgAccordion.toggle(CfgAccordion.initial(), Section.SAFETY)
        state = CfgAccordion.toggle(state, CfgAccordion.PINNED)
        assertFalse(CfgAccordion.isOpen(state, CfgAccordion.PINNED))
        assertTrue(CfgAccordion.isOpen(state, Section.SAFETY))
    }

    @Test
    fun `setup is the first section, so the pinned one is also the first read`() {
        // Pinning a section that rendered fourth would be a different and
        // worse idea: it would sit open in the middle of a list of closed
        // headers with no explanation.
        assertEquals(CfgAccordion.PINNED, Section.entries.first())
    }

    @Test
    fun `the chevron is two distinct single characters`() {
        assertEquals(1, CfgAccordion.CHEVRON_OPEN.length)
        assertEquals(1, CfgAccordion.CHEVRON_CLOSED.length)
        assertEquals(CfgAccordion.CHEVRON_OPEN, CfgAccordion.chevron(open = true))
        assertEquals(CfgAccordion.CHEVRON_CLOSED, CfgAccordion.chevron(open = false))
    }
}
