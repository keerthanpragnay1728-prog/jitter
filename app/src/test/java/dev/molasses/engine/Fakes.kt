package dev.molasses.engine

import dev.molasses.core.model.EngineSnapshot
import dev.molasses.core.model.EventType
import dev.molasses.core.time.MonotonicClock
import dev.molasses.core.time.WallClock

class FakeStore : EngineStore {
    val writes = mutableListOf<EngineSnapshot>()
    override suspend fun persist(snapshot: EngineSnapshot) { writes += snapshot }
}

class FakeLedger : FrictionLedger {
    data class Row(val pkg: String, val type: EventType, val meta: String?)
    val rows = mutableListOf<Row>()
    override fun log(pkg: String, type: EventType, meta: String?) {
        rows += Row(pkg, type, meta)
    }
    fun typesFor(pkg: String) = rows.filter { it.pkg == pkg }.map { it.type }
    fun count(type: EventType) = rows.count { it.type == type }
}

class MutableClock(var now: Long = 0) : MonotonicClock, WallClock {
    override fun elapsedMs() = now
    override fun wallMs() = now
    fun advance(ms: Long) { now += ms }
}

/** Separately settable wall and monotonic clocks, for tamper scenarios. */
class SplitClock(var wall: Long = 0, var mono: Long = 0) : MonotonicClock, WallClock {
    override fun elapsedMs() = mono
    override fun wallMs() = wall
}
