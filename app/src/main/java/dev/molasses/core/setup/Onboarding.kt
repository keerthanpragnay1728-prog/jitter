package dev.molasses.core.setup

/**
 * The first-run flow's steps and when each is satisfied. Pure.
 *
 * A fresh install has an inert engine: nothing is gated until the
 * accessibility service is bound and usage access is granted. This flow is
 * shown until setup is complete, by whichever screen opens first. That is
 * the console when Jitter is already the home app, and CFG from the app
 * drawer when it is not, which is the usual case on a fresh sideload. Both
 * read one [Session], so LATER on one holds on the other. It can be
 * re-entered from CFG SETUP.
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
 * - Home app: detected (`RoleManager.isRoleHeld(ROLE_HOME)`, or the resolved
 *   HOME activity where the role is not available). Set through the role
 *   request, with the system's home settings behind it.
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

    enum class Step { ACCESSIBILITY, USAGE_ACCESS, HOME, TARGETS, LIMITS }

    /** Android 13, where restricted settings began. */
    const val RESTRICTED_SETTINGS_SDK = 33

    data class Facts(
        /** The accessibility service is bound and ready (`ServiceHealth.HEALTHY`). */
        val serviceReady: Boolean,
        /** Switched on in Settings. Reported, never enough on its own. */
        val accessibilityEnabled: Boolean,
        val usageAccess: Boolean,
        /** Jitter is the default home app. */
        val defaultHome: Boolean,
        val targetsSeen: Boolean,
        val limitsSeen: Boolean,
    )

    fun satisfied(step: Step, facts: Facts): Boolean = when (step) {
        Step.ACCESSIBILITY -> facts.serviceReady && facts.accessibilityEnabled
        Step.USAGE_ACCESS -> facts.usageAccess
        Step.HOME -> facts.defaultHome
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
     * The steps whose answer changes in another app, so the flow re-reads
     * them while it waits rather than only on resume. A grant or a bind can
     * land after this screen is already back in front.
     */
    fun needsPoll(step: Step?): Boolean =
        step == Step.ACCESSIBILITY || step == Step.USAGE_ACCESS || step == Step.HOME

    /** How the home step asks. */
    enum class HomeRoute { ROLE_REQUEST, HOME_SETTINGS }

    /** The role request where the device offers the home role, the settings screen otherwise. */
    fun homeRoute(roleAvailable: Boolean): HomeRoute =
        if (roleAvailable) HomeRoute.ROLE_REQUEST else HomeRoute.HOME_SETTINGS

    /**
     * Offer the home settings screen as well, once a role request came back
     * without the role. The request can return at once with no dialog shown
     * (a user who declined it before), and the settings screen is the way
     * through that.
     */
    fun showHomeSettingsFallback(facts: Facts, session: Session, roleAvailable: Boolean): Boolean =
        roleAvailable && session.homeRequestReturned && !satisfied(Step.HOME, facts)

    /**
     * The flow's state for the life of the process, shared by every screen
     * that can show it. A cold start is a new process and a fresh session,
     * which is what brings the flow back after LATER.
     */
    data class Session(
        /** CFG SETUP asked for the flow again. */
        val requested: Boolean = false,
        /** LATER or DONE. The flow stays away until the next cold start. */
        val closed: Boolean = false,
        val targetsSeen: Boolean = false,
        val limitsSeen: Boolean = false,
        /** Came back from a Settings screen the flow opened. Drives [showUnlock]. */
        val returnedFromSettings: Boolean = false,
        /** A home role request has returned at least once. */
        val homeRequestReturned: Boolean = false,
    )

    /** Show it again from the top of what is still open. */
    fun request(s: Session): Session =
        s.copy(requested = true, closed = false, targetsSeen = false, limitsSeen = false)

    /** LATER: away for this session. */
    fun later(s: Session): Session = s.copy(requested = false, closed = true)

    /**
     * DONE. Closed as well as completed, so the flow does not come back in
     * the moment before the stored flag lands, or at all this session if the
     * write fails. The caller persists completion.
     */
    fun done(s: Session): Session = s.copy(requested = false, closed = true, limitsSeen = true)

    /**
     * Whether a screen shows the flow: until setup has been completed once,
     * unless put off for this session, and again whenever CFG SETUP asks.
     * The same answer on every screen, so whichever opens first shows it.
     */
    fun shouldShow(completedOnce: Boolean, session: Session): Boolean =
        session.requested || (!completedOnce && !session.closed)
}
