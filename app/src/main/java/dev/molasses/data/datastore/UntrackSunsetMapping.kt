package dev.molasses.data.datastore

import dev.molasses.UntrackSunsetEntry
import dev.molasses.core.settings.UntrackSunset
import dev.molasses.core.time.StampedInstant

/**
 * `UntrackSunset.Sunset` to and from the proto. Lossless both ways: the
 * deadline is carried on all three clocks, because the RELIEF clamp needs
 * both the wall and the monotonic reading and the boot id decides whether
 * the monotonic one can be compared at all.
 */

fun UntrackSunset.Sunset.toProto(): UntrackSunsetEntry = UntrackSunsetEntry.newBuilder()
    .setPkg(pkg)
    .setDueWallMs(deadline.wallMs)
    .setDueElapsedMs(deadline.elapsedMs)
    .setDueBootId(deadline.bootId)
    .build()

fun UntrackSunsetEntry.toSunset(): UntrackSunset.Sunset = UntrackSunset.Sunset(
    pkg = pkg,
    deadline = StampedInstant(wallMs = dueWallMs, elapsedMs = dueElapsedMs, bootId = dueBootId),
)
