package dev.molasses.sensing

import org.junit.Test

class BucketProbe {
    private fun walk(ms: Long, rate: Int = 50, ramp: Long = 400) =
        SignalGen.gait(ms, jitterFrac = 0.08, sampleHz = rate, tiltRampMs = ramp)

    private fun shift(s: List<SignalGen.Sample>, byMs: Long) = s.map {
        SignalGen.Sample(it.timestampNs + byMs * 1_000_000, it.x, it.y, it.z,
            it.trueGx, it.trueGy, it.trueGz)
    }

    /** Ticks until the bucket first reports a pass, or -1. */
    private fun ticksToClear(gate: FallbackImuGate, samples: List<SignalGen.Sample>): Int {
        var n = -1
        for (s in samples) {
            val e = gate.onRawSample(s.timestampNs, s.x, s.y, s.z) ?: continue
            if (e.passed) { n = e.tick!!.tick; break }
        }
        return n
    }

    @Test fun probe() {
        println("=== clean gait, ticks to clear and credit, by delivery rate ===")
        for (rate in listOf(25, 50, 100, 200)) {
            val g = FallbackImuGate()
            val samples = walk(30_000, rate = rate)
            val n = ticksToClear(g, samples)
            println("rate=%3d  ticks=%3d  credit=%5d".format(rate, n, g.creditMs))
        }

        println()
        println("=== total ticks over a fixed 12 s, by delivery rate ===")
        for (rate in listOf(25, 50, 100, 200)) {
            val ticks = SignalGen.ticksIir(FallbackImuGate(), walk(12_000, rate = rate))
            val dts = ticks.map { it.tick!!.dtMs }
            println("rate=%3d  ticks=%3d  dt min=%d max=%d".format(rate, ticks.size, dts.min(), dts.max()))
        }

        println()
        println("=== gait with a 1 s stumble at t=6 s ===")
        run {
            val g = FallbackImuGate()
            SignalGen.feedIir(g, walk(6_000))
            val before = g.creditMs
            SignalGen.feedIir(g, shift(SignalGen.thumbTremor(1_000), 6_000))
            val after = g.creditMs
            val rest = shift(walk(30_000, ramp = 0), 7_000)
            var n = -1
            for (s in rest) {
                val e = g.onRawSample(s.timestampNs, s.x, s.y, s.z) ?: continue
                if (e.passed) { n = e.tick!!.tick; break }
            }
            println("credit before=$before after=$after  total ticks to clear=$n")
        }

        println()
        println("=== gait with 10% of ticks randomly failing ===")
        run {
            // Splice 250 ms of stillness over 10% of the timeline.
            val rnd = kotlin.random.Random(4242)
            val out = mutableListOf<SignalGen.Sample>()
            var t = 0L
            var chunk = 0
            while (t < 60_000) {
                val bad = rnd.nextDouble() < 0.10
                val piece = if (bad) SignalGen.static(250) else walk(250, ramp = if (chunk == 0) 400 else 0)
                out += shift(piece, t)
                t += 250; chunk++
            }
            val g = FallbackImuGate()
            val n = ticksToClear(g, out)
            println("ticks to clear=$n  credit=${g.creditMs}")
        }

        println()
        println("=== clean gait baseline for comparison ===")
        run {
            val g = FallbackImuGate()
            println("ticks to clear=${ticksToClear(g, walk(60_000))}")
        }
    }
}
