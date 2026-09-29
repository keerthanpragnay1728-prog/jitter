package dev.molasses.core.diag

/**
 * Whether our accessibility service is among the enabled ones, from the raw
 * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` string.
 *
 * ## Why not a string compare
 * The setting holds flattened component names, and a component has two
 * flattened forms: the full `pkg/pkg.path.Class` and the short `pkg/.path.Class`,
 * where a leading `.` stands for the package. A compare against one form
 * misses the other, and the checklist then reads "not enabled" for a service
 * that is running. The applicationId and the code namespace are separate build
 * settings, so the short form is never read as the namespace: it is always
 * expanded against the package in front of the slash.
 *
 * This is `ComponentName.unflattenFromString`'s rule, written without
 * Android so it can be tested here. `ComponentName` is not available in the
 * pure layer.
 *
 * Pure; no Android imports. Unit-tested in `ServiceComponentTest`.
 */
object ServiceComponent {

    /** A component as package and fully qualified class. */
    data class Name(val pkg: String, val cls: String)

    /**
     * `pkg/cls` to a [Name], expanding a leading `.` in `cls` against `pkg`.
     * Null when there is no slash or nothing after it, as `unflattenFromString`
     * returns null for those.
     */
    fun unflatten(flat: String): Name? {
        val sep = flat.indexOf('/')
        if (sep < 0 || sep + 1 >= flat.length) return null
        val pkg = flat.substring(0, sep)
        val cls = flat.substring(sep + 1)
        return Name(pkg, if (cls.startsWith(".")) pkg + cls else cls)
    }

    /**
     * True when any `:`-separated entry of [enabledSetting] names [pkg] and
     * [cls], in either flattened form. Exact, as `ComponentName.equals` is:
     * package and class names are case sensitive.
     */
    fun isEnabled(enabledSetting: String, pkg: String, cls: String): Boolean {
        val ours = Name(pkg, cls)
        return enabledSetting.split(':').any { unflatten(it) == ours }
    }
}
