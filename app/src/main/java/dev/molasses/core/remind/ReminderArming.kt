package dev.molasses.core.remind

/**
 * How a saved reminder's alarm was armed, decided in one place so the
 * acknowledgement can never claim more than the scheduler did. Pure.
 *
 * Three outcomes, each with its own acknowledgement:
 *
 * - [Armed.EXACT]: exact alarms are allowed and the exact call succeeded.
 * - [Armed.INEXACT]: exact was not allowed, or it threw (a revocation can
 *   land between the check and the call), and the inexact call succeeded.
 * - [Armed.NOT_ARMED]: no alarm is set. The reminder is saved in the store
 *   regardless, and `rescheduleAll` arms it on the next service connect.
 *
 * NOT_ARMED is its own answer rather than a quiet INEXACT because INEXACT
 * promises a delivery, late at worst, and here nothing will deliver it until
 * the service reconnects.
 */
object ReminderArming {

    enum class Armed { EXACT, INEXACT, NOT_ARMED }

    /**
     * @param exactAllowed whether the platform lets this app set an exact alarm now.
     * @param tryExact sets the exact alarm; true when the call succeeded.
     * @param tryInexact sets the inexact alarm; true when the call succeeded.
     */
    fun arm(exactAllowed: Boolean, tryExact: () -> Boolean, tryInexact: () -> Boolean): Armed = when {
        exactAllowed && tryExact() -> Armed.EXACT
        tryInexact() -> Armed.INEXACT
        else -> Armed.NOT_ARMED
    }
}
