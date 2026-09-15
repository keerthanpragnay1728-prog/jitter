package dev.molasses.data.datastore

import dev.molasses.LockEntry
import dev.molasses.LockReasonProto
import dev.molasses.core.lock.Lock
import dev.molasses.core.lock.LockReason
import dev.molasses.core.time.StampedInstant

/**
 * `LockRegistry` to and from the proto.
 *
 * Kept in one file rather than beside the other mappings because the round
 * trip has to be lossless in both directions and that is easier to check when
 * both halves are on one screen. `LockPersistenceTest` asserts the proto has a
 * field for every property of [Lock] and [StampedInstant], so this file
 * cannot silently fall behind the model.
 */

fun Lock.toProto(): LockEntry = LockEntry.newBuilder()
    .setPkg(pkg)
    .setStartedWallMs(startedAt.wallMs)
    .setStartedElapsedMs(startedAt.elapsedMs)
    .setStartedBootId(startedAt.bootId)
    .setDurationMs(durationMs)
    .setReason(reason.toProto())
    .build()

fun LockEntry.toLock(): Lock = Lock(
    pkg = pkg,
    startedAt = StampedInstant(
        wallMs = startedWallMs,
        elapsedMs = startedElapsedMs,
        bootId = startedBootId,
    ),
    durationMs = durationMs,
    reason = reason.toModel(),
)

fun LockReason.toProto(): LockReasonProto = when (this) {
    LockReason.BLOCK -> LockReasonProto.BLOCK
    LockReason.FOCUS -> LockReasonProto.FOCUS
    LockReason.BEDTIME -> LockReasonProto.BEDTIME
    LockReason.CHECKPOINT -> LockReasonProto.CHECKPOINT
}

/**
 * Unrecognised values fall back to BLOCK rather than throwing. A lock read
 * from a file written by a newer build must still be enforced; losing the
 * label is survivable, silently dropping the lock is not.
 */
fun LockReasonProto.toModel(): LockReason = when (this) {
    LockReasonProto.FOCUS -> LockReason.FOCUS
    LockReasonProto.BEDTIME -> LockReason.BEDTIME
    LockReasonProto.CHECKPOINT -> LockReason.CHECKPOINT
    else -> LockReason.BLOCK
}
