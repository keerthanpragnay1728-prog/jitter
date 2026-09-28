package dev.molasses.core.setup

/**
 * The first-run flow's steps and when each is satisfied. Pure.
 *
 * A fresh install lands on a console whose engine is inert: nothing is
 * gated until the accessibility service is bound and usage access is
 * granted, and nothing on the console says so. This flow is shown until
 * setup is complete, and can be re-entered from CFG SETUP.
 *
 * ## What can be detected and what cannot
 * - Accessibility: detected, and strictly. Satisfied only when the service
 *   is bound and ready ([Facts.serviceReady], `ServiceHealth.HEALTHY`) and
 *   still switched on. Enabled but not yet bound reads as not satisfied, and
 *   the screen says it is waiting for the bind. Both halves are needed:
 *   `ServiceDiagnostics` lives in the process rather than in the service, so
 *   after the user switches the service off it keeps reading HEALTHY until
 *   the heartbeat times out, up to 45 s later.
 * - Usage access: detected (the app op).
 * - Targets: nothing to detect; the defaults are fine. Satisfied once seen.
 * - Known limits: nothing to detect. Satisfied once seen.
 * - "Restricted setting" (Android 13 and later blocks enabling a sideloaded
 *   app's accessibility service until the user allows it from App info):
 *   cannot be detected. There is no public API that says whether a package
 *   is restricted. So the flow does not guess. Whenever the accessibility
 *   step is still unsatisfied after a return from Settings, on Android 13 or
 *   later, it shows the unlock steps, which are harmless to read if the
 *   setting was never the problem.
 */
object Onboarding {

    enum class Step { ACCESSIBILITY, USAGE_ACCESS, TARGETS, LIMITS }

    /** Android 13, where restricted settings began. */
    const val RESTRICTED_SETTINGS_SDK = 33

    data class Facts(
        /** The accessibility service is bound and ready (`ServiceHealth.HEALTHY`). */
        val serviceReady: Boolean,
        /** Switched on in Settings. Reported, never enough on its own. */
        val accessibilityEnabled: Boolean,
        val usageAccess: Boolean,
        val targetsSeen: Boolean,
        val limitsSeen: Boolean,
    )

    fun satisfied(step: Step, facts: Facts): Boolean = when (step) {
        Step.ACCESSIBILITY -> facts.serviceReady && facts.accessibilityEnabled
        Step.USAGE_ACCESS -> facts.usageAccess
        Step.TARGETS -> facts.targetsSeen
        Step.LIMITS -> facts.limitsSeen
    }

    /** The first step not yet satisfied, in order, or null when setup is complete. */
    fun current(facts: Facts): Step? = Step.entries.firstOrNull { !satisfied(it, facts) }

    fun complete(facts: Facts): Boolean = current(facts) == null

    /** Switched on, not yet bound. The screen says it is waiting rather than asking again. */
    fun waitingForBind(facts: Facts): Boolean = facts.accessibilityEnabled && !facts.serviceReady

    /**
     * Whether to show the restricted-settings unlock. See the class doc: it
     * cannot be detected, so it is shown whenever the accessibility step is
     * still unsatisfied after the user came back from Settings, on 13+.
     */
    fun showUnlock(facts: Facts, returnedFromSettings: Boolean, sdkInt: Int): Boolean =
        !satisfied(Step.ACCESSIBILITY, facts) && returnedFromSettings && sdkInt >= RESTRICTED_SETTINGS_SDK

    /**
     * Whether the launcher shows the flow: until setup has been completed
     * once, and again whenever CFG SETUP asks for it.
     */
    fun shouldShow(completedOnce: Boolean, requested: Boolean): Boolean = requested || !completedOnce
}
