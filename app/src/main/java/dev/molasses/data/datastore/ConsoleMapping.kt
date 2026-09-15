package dev.molasses.data.datastore

import dev.molasses.ConsoleBudget
import dev.molasses.ConsoleQueued
import dev.molasses.CycleState
import dev.molasses.core.console.ConsoleLine
import dev.molasses.core.console.ConsoleSpeech

/**
 * Bit's speech to and from the proto.
 *
 * The persisted form carries a string id rather than a resource id, so the
 * round trip survives a rebuild. `ConsoleCopyTest` requires copy to exist for
 * every declared id, which is the other half of that: an id that persists and
 * then renders as nothing would spend one of three lines an hour on a blank
 * row.
 */

/** Queue, live prompt and budget, read together so they cannot disagree. */
data class ConsoleState(
    val queued: ConsoleLine? = null,
    val live: ConsoleLine.Prompt? = null,
    val budget: ConsoleSpeech.Budget = ConsoleSpeech.Budget(),
)

fun CycleState.toConsoleState(): ConsoleState = ConsoleState(
    queued = if (hasConsoleQueued()) consoleQueued.toLine() else null,
    live = if (hasConsoleLive()) consoleLive.toLine() as? ConsoleLine.Prompt else null,
    budget = consoleBudget.toBudget(),
)

fun ConsoleLine.toProto(): ConsoleQueued = ConsoleQueued.newBuilder()
    .setId(id)
    .addAllArgs(args)
    .setIsPrompt(this is ConsoleLine.Prompt)
    .setAction(if (this is ConsoleLine.Prompt) action else "")
    .build()

fun ConsoleQueued.toLine(): ConsoleLine =
    if (isPrompt) {
        ConsoleLine.Prompt(id = id, args = argsList.toList(), action = action)
    } else {
        ConsoleLine.Notice(id = id, args = argsList.toList())
    }

fun ConsoleSpeech.Budget.toProto(): ConsoleBudget = ConsoleBudget.newBuilder()
    .addAllDeliveredAtWallMs(deliveredAtWallMs)
    .addAllSeenIds(seenIds)
    .setCycleAnchorWallMs(cycleAnchorWallMs)
    .build()

fun ConsoleBudget.toBudget(): ConsoleSpeech.Budget = ConsoleSpeech.Budget(
    deliveredAtWallMs = deliveredAtWallMsList.toList(),
    seenIds = seenIdsList.toSet(),
    cycleAnchorWallMs = cycleAnchorWallMs,
)
