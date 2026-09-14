package dev.molasses.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
