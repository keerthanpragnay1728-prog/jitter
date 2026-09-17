package dev.molasses.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BezelSnapTest {

    private val container = 1080
    private val bit = 120

    @Test
    fun `the right target subtracts the item width`() {
        // The reported bug: a target of containerPx put Bit one full width
        // outside the parent, where Compose delivers it no touch events.
        assertEquals(960f, BezelSnap.maxOffset(container, bit))
        assertEquals(960f, BezelSnap.snapTargetX(900f, container, bit))
    }

    @Test
    fun `the left target is zero`() {
        assertEquals(0f, BezelSnap.snapTargetX(10f, container, bit))
    }

    @Test
    fun `both targets are inside the parent`() {
        for (x in listOf(0f, 100f, 539f, 540f, 900f, 2000f, -500f)) {
            val target = BezelSnap.snapTargetX(x, container, bit)
            assertTrue(
                "target $target from $x is out of bounds",
                BezelSnap.isWithinBounds(target, container, bit),
            )
        }
    }

    @Test
    fun `a zero item width refuses to snap`() {
        // Zero is onSizeChanged not having fired, not a real measurement.
        // Snapping on it is exactly how Bit landed outside the parent.
        assertFalse(BezelSnap.canSnap(container, 0))
        assertFalse(BezelSnap.canSnap(0, bit))
        assertFalse(BezelSnap.canSnap(0, 0))
        assertTrue(BezelSnap.canSnap(container, bit))
    }

    @Test
    fun `an item wider than its container pins at zero`() {
        // The same bug mirrored: a negative max would put the target to the
        // left of the parent.
        assertEquals(0f, BezelSnap.maxOffset(100, 500))
        assertEquals(0f, BezelSnap.snapTargetX(50f, 100, 500))
        assertTrue(BezelSnap.isWithinBounds(0f, 100, 500))
    }

    @Test
    fun `clamp keeps any value in bounds`() {
        for (v in listOf(-9999f, -1f, 0f, 480f, 960f, 961f, 99999f)) {
            val c = BezelSnap.clamp(v, container, bit)
            assertTrue("clamp($v) = $c", BezelSnap.isWithinBounds(c, container, bit))
        }
        assertEquals(0f, BezelSnap.clamp(-5f, container, bit))
        assertEquals(960f, BezelSnap.clamp(5000f, container, bit))
    }

    @Test
    fun `a release past the midpoint goes right, before it goes left`() {
        val mid = BezelSnap.maxOffset(container, bit) / 2f
        assertEquals(0f, BezelSnap.snapTargetX(mid - 1f, container, bit))
        assertEquals(960f, BezelSnap.snapTargetX(mid + 1f, container, bit))
    }

    @Test
    fun `an exact tie goes right, deterministically`() {
        val mid = BezelSnap.maxOffset(container, bit) / 2f
        assertEquals(960f, BezelSnap.snapTargetX(mid, container, bit))
        // Same input, same answer. A coin flip the user cannot predict is
        // worse than a side they can learn.
        repeat(5) { assertEquals(960f, BezelSnap.snapTargetX(mid, container, bit)) }
    }

    @Test
    fun `an out of range current position still yields an in-bounds target`() {
        // The decay animation can overshoot far outside the parent before the
        // snap runs. The target must not inherit that.
        assertEquals(960f, BezelSnap.snapTargetX(50_000f, container, bit))
        assertEquals(0f, BezelSnap.snapTargetX(-50_000f, container, bit))
    }

    @Test
    fun `bounds checking rejects what the bug produced`() {
        // containerPx as the right target, which is what shipped.
        assertFalse(BezelSnap.isWithinBounds(container.toFloat(), container, bit))
    }

    // ------------------------------------------- a width that changes

    /** Bit's slot while a face is showing, and while the slit is. */
    private val wide = 140
    private val narrow = 60

    @Test
    fun `docking to a narrower glyph follows the right bezel`() {
        // The docked-slit bug. A narrower item makes maxOffset larger, so the
        // old offset is still in bounds and nothing corrects it. Bit sits the
        // difference between the two widths in from the edge and looks
        // dropped rather than docked.
        val wasDocked = BezelSnap.maxOffset(container, wide)
        assertEquals(940f, wasDocked)
        assertEquals(
            BezelSnap.maxOffset(container, narrow),
            BezelSnap.reSnap(wasDocked, container, wide, narrow),
        )
        assertEquals(1020f, BezelSnap.reSnap(wasDocked, container, wide, narrow))
    }

    @Test
    fun `un-docking to a wider glyph comes back inside the parent`() {
        // The dangerous direction. A wider item makes maxOffset smaller, so
        // the old offset is outside the parent, and Compose delivers no touch
        // events to a child outside its parent. That is the original right
        // bezel lockup arriving by a second route.
        val wasDocked = BezelSnap.maxOffset(container, narrow)
        val target = BezelSnap.reSnap(wasDocked, container, narrow, wide)
        assertEquals(940f, target)
        assertTrue(BezelSnap.isWithinBounds(target!!, container, wide))
    }

    @Test
    fun `the left bezel stays at zero at either width`() {
        assertEquals(0f, BezelSnap.reSnap(0f, container, wide, narrow))
        assertEquals(0f, BezelSnap.reSnap(0f, container, narrow, wide))
    }

    @Test
    fun `a Bit resting away from a bezel is not moved`() {
        // It was put there by a drag or a decay that is probably still
        // running. Repositioning it would be the layout wrestling the finger.
        assertNull(BezelSnap.reSnap(400f, container, wide, narrow))
        assertNull(BezelSnap.reSnap(400f, container, narrow, wide))
    }

    @Test
    fun `no width transition leaves any part of Bit outside the parent`() {
        // The property, swept rather than sampled: whatever the starting
        // offset and whichever way the width moves, an applied re-snap is
        // inside the bounds of the width it is moving to.
        val widths = listOf(narrow, wide, bit, 1, container, container + 200)
        for (from in widths) {
            for (to in widths) {
                for (x in listOf(-50f, 0f, 1f, 400f, 940f, 1020f, 5000f)) {
                    val target = BezelSnap.reSnap(x, container, from, to) ?: continue
                    assertTrue(
                        "from=$from to=$to x=$x gave $target",
                        BezelSnap.isWithinBounds(target, container, to),
                    )
                }
            }
        }
    }

    @Test
    fun `a missing measurement declines to move anything`() {
        // Same rule as canSnap. A zero width is onSizeChanged not having
        // fired, and snapping on it is how Bit ended up one full width
        // outside the parent the first time.
        assertNull(BezelSnap.reSnap(940f, container, 0, narrow))
        assertNull(BezelSnap.reSnap(940f, container, wide, 0))
        assertNull(BezelSnap.reSnap(940f, 0, wide, narrow))
    }

    @Test
    fun `an unchanged width re-docks to the same edge`() {
        // The container changed, not the glyph: a rotation. A docked Bit
        // still follows its bezel.
        assertEquals(940f, BezelSnap.reSnap(940f, container, wide, wide))
        assertEquals(0f, BezelSnap.reSnap(0f, container, wide, wide))
    }
}
