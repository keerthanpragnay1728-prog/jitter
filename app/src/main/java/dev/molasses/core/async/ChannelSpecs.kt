package dev.molasses.core.async

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/**
 * Every [Channel] the app constructs, built here rather than at the call site.
 *
 * The reason is specific. `Channel(Channel.CONFLATED, BufferOverflow.DROP_OLDEST)`
 * throws `IllegalArgumentException` from the factory -- `CONFLATED` already
 * implies `DROP_OLDEST`, and passing both is rejected. That exact pair shipped
 * once in `FrictionEngine` and took the service down at construction; it was
 * only caught because the engine happened to be unit-testable.
 *
 * The channels that matter are created inside Android components that cannot
 * be constructed on the JVM, so a test that rebuilt their arguments would be
 * testing a copy. Centralising the constructions here means `ChannelSpecTest`
 * exercises the same code path the service does, and a bad capacity/overflow
 * pair fails in CI instead of on a device.
 *
 * Pure: kotlinx-coroutines only, no Android imports.
 */
object ChannelSpecs {

    /**
     * Engine checkpoints. Only the newest snapshot matters, and the producer
     * is the accessibility callback thread, which must never suspend.
     *
     * `CONFLATED` alone -- the overflow policy is implied and passing it
     * explicitly is an error.
     */
    fun <T> engineCheckpoints(): Channel<T> = Channel(Channel.CONFLATED)

    /**
     * Ledger rows. Unlike checkpoints these are not interchangeable -- each row
     * is a distinct fact -- so this buffers rather than conflates, and drops
     * the oldest under pressure.
     *
     * `capacity = N` with `DROP_OLDEST` is the legal spelling of "buffer, then
     * shed"; `CONFLATED` with an explicit overflow is not.
     */
    fun <T> ledgerRows(capacity: Int = LEDGER_CAPACITY): Channel<T> =
        Channel(capacity = capacity, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    const val LEDGER_CAPACITY = 256
}
