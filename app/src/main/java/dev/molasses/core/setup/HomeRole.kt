package dev.molasses.core.setup

/**
 * Whether Jitter is the device's home app. Pure: the caller reads the role
 * and the resolver and passes them in.
 *
 * ## One answer for every screen
 * The first-run flow's home step, CFG's SETUP row and the console's warning
 * line all ask this, through one Android reader, `isDefaultHome` in
 * `ui/setup/HomeRoleCheck.kt`. Two readers would be two chances to disagree,
 * and a home step marked done beside a console that says otherwise is worse
 * than either alone.
 *
 * ## The role first, the resolver only when the role cannot answer
 * `RoleManager.isRoleHeld(ROLE_HOME)` is the platform's own answer and is
 * available on every device this app installs on (minSdk 30). It can still
 * fail to answer: no RoleManager, the role reported unavailable, or the call
 * throwing. Then the HOME intent's default resolution decides, which is the
 * resolver itself when nothing is chosen, and the resolver is not us.
 *
 * The resolver is a lambda so it is not queried at all when the role
 * answered. It is an IPC, and this runs on every resume of the console.
 *
 * ## Read on resume, never polled
 * The role changes only in the system's own screens, and leaving those
 * resumes whichever of our activities is underneath. No poll, no listener,
 * no notification.
 */
object HomeRole {

    /**
     * @param roleHeld the role's answer, or null when it could not give one.
     * @param resolvedHomePackage the package the HOME intent resolves to by
     *   default, or null when nothing resolves. Called only when [roleHeld]
     *   is null.
     */
    fun isDefault(
        roleHeld: Boolean?,
        ownPackage: String,
        resolvedHomePackage: () -> String?,
    ): Boolean {
        if (roleHeld != null) return roleHeld
        if (ownPackage.isEmpty()) return false
        return resolvedHomePackage() == ownPackage
    }
}
