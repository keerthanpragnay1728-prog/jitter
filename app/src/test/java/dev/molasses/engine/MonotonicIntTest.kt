package dev.molasses.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MonotonicIntTest {

    @Test
    fun `rises freely`() {
        val m = MonotonicInt(0)
        m.value = 1
        m.value = 4
        assertEquals(4, m.value)
    }

    @Test
    fun `same value is allowed`() {
        val m = MonotonicInt(3)
        m.value = 3
        assertEquals(3, m.value)
    }

    @Test
    fun `any decrease throws rather than clamping`() {
        val m = MonotonicInt(3)
        val e = assertThrows(IllegalStateException::class.java) { m.value = 2 }
        assertEquals(true, e.message!!.contains("monotonic"))
        assertEquals(3, m.value)
    }

    @Test
    fun `raiseTo never throws and never lowers`() {
        val m = MonotonicInt(5)
        m.raiseTo(2)
        assertEquals(5, m.value)
        m.raiseTo(7)
        assertEquals(7, m.value)
    }
}
