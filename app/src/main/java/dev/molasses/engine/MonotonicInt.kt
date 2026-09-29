package dev.molasses.engine

/**
 * An int that refuses to go down.
 *
 * This exists so the brief's central invariant -- "clearing a gate never
 * rewinds `tierIndex`" -- is enforced by the type rather than by everyone
 * remembering. Assigning a lower value throws rather than clamping, because a
 * silent clamp would turn a logic bug into a slow behavioural drift that only
 * shows up as a user complaint about the app "going easy" after a gate.
 *
 * A cycle rollover does not assign a lower value here; it constructs a fresh
 * [MutableAppState] with a new counter. See `FrictionEngine.rollCycle`.
 */
class MonotonicInt(initial: Int) {
    var value: Int = initial
        set(next) {
            if (next < field) {
                throw IllegalStateException(
                    "tierIndex is monotonic: refused to move $field -> $next",
                )
            }
            field = next
        }

    /** Raise to [candidate] if it is higher. Never throws. */
    fun raiseTo(candidate: Int) {
        if (candidate > value) value = candidate
    }

    override fun toString(): String = value.toString()
}
