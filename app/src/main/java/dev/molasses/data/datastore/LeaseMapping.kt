package dev.molasses.data.datastore

import dev.molasses.LeaseEntry
import dev.molasses.core.lease.Lease
import dev.molasses.core.time.StampedInstant

/**
 * `LeaseManager` to and from the proto.
 *
 * Same shape as `LockMapping` and kept beside it for the same reason: the
 * round trip has to be lossless in both directions and that is easier to check
 * with both halves on one screen. `LeasePersistenceTest` asserts the proto has
 * a field for every property of [Lease] and [StampedInstant], so this file
 * cannot silently fall behind the model.
 *
 * `startedWallMs` is carried for the round trip and is deliberately not what
 * decides whether a lease is live. See `LeaseManager`: a lease is relief, so
 * it measures on `elapsedRealtime` alone.
 */

fun Lease.toProto(): LeaseEntry = LeaseEntry.newBuilder()
    .setPkg(pkg)
    .setStartedWallMs(startedAt.wallMs)
    .setStartedElapsedMs(startedAt.elapsedMs)
    .setStartedBootId(startedAt.bootId)
    .setDurationMs(durationMs)
    .setTakenAtAccumulatedMs(takenAtAccumulatedMs)
    .build()

fun LeaseEntry.toLease(): Lease = Lease(
    pkg = pkg,
    startedAt = StampedInstant(
        wallMs = startedWallMs,
        elapsedMs = startedElapsedMs,
        bootId = startedBootId,
    ),
    durationMs = durationMs,
    takenAtAccumulatedMs = takenAtAccumulatedMs,
)
