package dev.molasses.core.command

/**
 * Dummy resource ids for a registry under test.
 *
 * ## Why this is a fixture and not a literal in each test
 * [CommandRegistry.Keys] has one field per command, so every test that builds
 * a registry had to write out the whole positional list, and adding three
 * commands meant editing five copies of it by hand. Five copies of a list
 * that has to stay in step with a sixth is the shape this repository keeps
 * deleting elsewhere.
 *
 * The ids are sequential and distinct, which is all any test needs: the
 * registry stores them and never dereferences them. Distinct matters because
 * `CommandResourcesTest` compares what the registry holds against the
 * resource file, and two commands sharing an id would hide a swap.
 */
internal fun dummyCommandKeys(): CommandRegistry.Keys {
    var next = 0
    fun id(): Int = ++next
    return CommandRegistry.Keys(
        blockUsage = id(), blockDesc = id(),
        focusUsage = id(), focusDesc = id(),
        bedtimeUsage = id(), bedtimeDesc = id(),
        statusUsage = id(), statusDesc = id(),
        helpUsage = id(), helpDesc = id(),
        alarmUsage = id(), alarmDesc = id(),
        timerUsage = id(), timerDesc = id(),
        rebootUsage = id(), rebootDesc = id(),
        poweroffUsage = id(), poweroffDesc = id(),
        wifiUsage = id(), wifiDesc = id(),
        dndUsage = id(), dndDesc = id(),
        calcUsage = id(), calcDesc = id(),
        convUsage = id(), convDesc = id(),
        daysUsage = id(), daysDesc = id(),
    )
}
