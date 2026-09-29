package dev.molasses.core.latency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencySegmentsTest {

    @Test
    fun `percentiles are nearest-rank, not interpolated`() {
        val r = LatencyRing(100)
        (1..100).forEach { r.add(it.toLong()) }
        assertEquals(50, r.p50)
        assertEquals(95, r.p95)
        assertEquals(1, r.min)
        assertEquals(100, r.max)
    }

    @Test
    fun `a right-skewed distribution is exactly what a mean would hide`() {
        // The argument for reporting percentiles rather than a moving average,
        // as an assertion so it does not become a comment nobody checks.
        val r = LatencyRing(100)
        repeat(90) { r.add(60) }
        repeat(10) { r.add(400) }
        val mean = r.snapshot().average()
        assertTrue("mean $mean looks acceptable", mean < 100)
        assertEquals("but one flick in ten is 400 ms", 400, r.p95)
    }

    @Test
    fun `the ring keeps the most recent capacity samples`() {
        val r = LatencyRing(10)
        (1..100).forEach { r.add(it.toLong()) }
        assertEquals(10, r.size)
        assertEquals(91, r.min)
        assertEquals(100, r.max)
    }

    @Test
    fun `negative samples are discarded and counted, not recorded`() {
        // Segment D can see a MotionEvent that was already in flight when the
        // stall armed, whose eventTime precedes the scroll. Recording it would
        // flatter the result.
        val r = LatencyRing()
        assertTrue(r.add(50))
        assertFalse(r.add(-3))
        assertFalse(r.add(-120))
        assertEquals(1, r.size)
        assertEquals(2, r.discarded)
        assertEquals(50, r.p50)
    }

    @Test
    fun `an empty ring reports -1 rather than zero`() {
        val r = LatencyRing()
        // Zero would read as "instant", which is the one wrong answer here.
        assertEquals(-1, r.p50)
        assertEquals(-1, r.p95)
        assertEquals(-1, r.min)
        assertEquals(-1, r.max)
    }

    @Test
    fun `clear resets samples and the discard count`() {
        val r = LatencyRing()
        r.add(10); r.add(-1)
        r.clear()
        assertEquals(0, r.size)
        assertEquals(0, r.discarded)
    }

    @Test
    fun `the logcat line matches the documented format`() {
        // Built so p50 and p95 genuinely differ: 45 samples at 68 ms and 5 at
        // 112 ms gives nearest-rank p50 = 68, p95 = 112.
        val p = PackageLatency("com.instagram.android")
        repeat(45) { p.record(Segment.D, 68) }
        repeat(5) { p.record(Segment.D, 112) }
        assertEquals(
            "[TARGET: com.instagram.android] A=34 B=2 C=18 D=71 | p50(D)=68 p95(D)=112 n=50",
            p.formatLine(a = 34, b = 2, c = 18, d = 71),
        )
    }

    @Test
    fun `the logcat line surfaces discards when there are any`() {
        val p = PackageLatency("com.instagram.android")
        p.record(Segment.D, 70)
        p.record(Segment.D, -5)
        assertTrue(p.formatLine(1, 2, 3, 70).contains("discarded=1"))
    }

    @Test
    fun `all four segments are tracked per package and kept separate`() {
        val reg = LatencyRegistry()
        val ig = reg.forPackage("com.instagram.android")
        val yt = reg.forPackage("com.google.android.youtube")
        ig.record(Segment.A, 34)
        ig.record(Segment.D, 71)
        yt.record(Segment.A, 90)

        assertEquals(34, ig[Segment.A].p50)
        assertEquals(71, ig[Segment.D].p50)
        assertEquals(90, yt[Segment.A].p50)
        assertEquals(-1, yt[Segment.D].p50)
        assertEquals(listOf("com.instagram.android", "com.google.android.youtube"), reg.packages())
    }

    @Test
    fun `the dump table covers every segment even when empty`() {
        val table = PackageLatency("com.x").formatTable()
        for (seg in Segment.entries) {
            assertTrue("table missing ${seg.label}", table.contains(" ${seg.label} "))
        }
    }

    @Test
    fun `an empty registry says so rather than printing nothing`() {
        assertTrue(LatencyRegistry().formatAll().contains("no stall latency recorded"))
    }

    @Test
    fun `last returns the most recent sample, not the largest`() {
        // The per-event logcat line reports the latest value for A, B and C.
        // snapshot() is sorted, so snapshot().last() would report the maximum
        // and quietly turn the line into a running worst case.
        val r = LatencyRing(10)
        r.add(500)
        r.add(20)
        assertEquals(20, r.last)
        assertEquals(500, r.max)
        assertEquals(-1, LatencyRing().last)
    }

    @Test
    fun `last survives ring wraparound`() {
        val r = LatencyRing(4)
        (1..10).forEach { r.add(it.toLong()) }
        assertEquals(10, r.last)
    }

    @Test
    fun `segment A carries the not-optimisable warning`() {
        // A is the number that decides whether the concept is salvageable;
        // its meaning string is what a reader sees in a dumpsys table.
        assertTrue(Segment.A.meaning.contains("pipeline"))
        assertTrue(Segment.D.meaning.contains("ground truth"))
    }
}
