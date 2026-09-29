package dev.molasses.core.safety

/**
 * Packages Jitter must never draw anything over.
 *
 * ## Why this is not a cosmetic concern
 * Banking and UPI apps in India check two things about other apps on the
 * device. They read the declared `AccessibilityServiceInfo` of every enabled
 * accessibility service, and they check
 * `MotionEvent.FLAG_WINDOW_IS_OBSCURED` on their own touches. The second one
 * is the dangerous half: an overlay sitting over a payment screen sets that
 * flag, and a hardened app is entitled to refuse the transaction outright.
 * Some also set `FLAG_SECURE`, which makes our overlay behave unpredictably
 * on top of theirs.
 *
 * So this is not "be polite to banks". Drawing a stall sink over a UPI PIN
 * entry can lose someone a payment at the till, and the friction it would
 * add is worth nothing.
 *
 * ## Prefix matching, not equality
 * Indian banking apps ship under a long tail of package names, many of them
 * per-bank white-label builds of the same SDK (`com.icicibank.pockets`,
 * `com.icicibank.imobile`, and so on). Matching on prefix covers a family
 * from one entry. It also means a bad entry is broad, which is why the list
 * is short, explicit, and editable by the user rather than inferred.
 *
 * The default set cannot possibly be complete. There are dozens of Indian
 * bank apps and this list names six families, so the user-extensible set in
 * settings is load-bearing, not a convenience.
 *
 * Pure; no Android imports. Unit-tested in `SensitivePackagesTest`.
 */
object SensitivePackages {

    /**
     * Shipped defaults: the three dominant UPI apps, the NPCI reference app,
     * and two bank families that are common enough to be worth naming.
     */
    val DEFAULT_PREFIXES: Set<String> = setOf(
        "com.phonepe",
        "net.one97.paytm",
        "com.google.android.apps.nbu.paisa.user",
        "in.org.npci.upiapp",
        "com.sbi.upi",
        "com.icicibank",
    )

    /**
     * True when [pkg] is covered by any prefix in [prefixes].
     *
     * A prefix matches either the whole package or a package that continues
     * with a dot. `com.icicibank` therefore matches `com.icicibank.imobile`
     * but not `com.icicibankruptcy.game`, which a bare `startsWith` would
     * have swallowed. Getting that wrong in the permissive direction would
     * silently disable friction for an unrelated app.
     */
    fun isSensitive(pkg: String?, prefixes: Set<String> = DEFAULT_PREFIXES): Boolean {
        if (pkg.isNullOrEmpty()) return false
        return prefixes.any { prefix ->
            prefix.isNotEmpty() &&
                (pkg == prefix || pkg.startsWith("$prefix."))
        }
    }

    /**
     * Fold a user's extra entries into the defaults.
     *
     * Blank entries are dropped and everything is lowercased, because the
     * settings field is free text and a stray space or a capital would
     * produce an entry that silently never matches. The defaults are always
     * included: letting a user remove `com.phonepe` from the suppression set
     * is not a preference, it is a way to lose money.
     */
    fun resolve(userPrefixes: List<String>): Set<String> =
        DEFAULT_PREFIXES + userPrefixes
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
}
