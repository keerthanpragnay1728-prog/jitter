package dev.molasses.core.command

/**
 * `$ block instagram 30m` to a package name.
 *
 * ## Why this is not in the parser
 * The parser is a pure function from text to a `Command` and knows nothing
 * about what is installed. Resolving a token needs the app list, which is an
 * Android concern, so the parser carries the raw token and the caller does
 * this. That split is what lets the grammar be exhaustively tested without a
 * device.
 *
 * ## Why an ambiguous token is an error and not a guess
 * The commands that take an app token are the ones that arm something
 * irreversible. `$ block go 30d` matching both Google and GoPro and silently
 * picking the first is a thirty day lock on the wrong app, and there is no
 * unlock. Failing costs one retype.
 *
 * ## Why targets win
 * A tracked app is the one the user almost certainly means, and it is the only
 * set where a lock does anything at all. Resolving against targets first means
 * `$ block insta 1h` works on a phone that also has a dozen apps whose labels
 * happen to contain those letters.
 *
 * Pure; no Android imports. Unit-tested in `AppTokenResolverTest`.
 */
object AppTokenResolver {

    /** One installed app, as much of it as resolution needs. */
    data class Candidate(val pkg: String, val label: String)

    sealed interface Result {
        data class One(val pkg: String) : Result

        /** Nothing matched. The user probably typed a name that is not here. */
        data object None : Result

        /** Several matched. Carries them so the message can name them. */
        data class Ambiguous(val packages: List<String>) : Result
    }

    /**
     * @param preferred packages to resolve against first, normally the
     *   tracked targets.
     */
    fun resolve(
        token: String,
        candidates: List<Candidate>,
        preferred: Set<String> = emptySet(),
    ): Result {
        val text = token.trim().lowercase()
        if (text.isEmpty()) return Result.None

        // An exact package name is unambiguous by construction and beats
        // everything, including a label that happens to equal it.
        candidates.firstOrNull { it.pkg.lowercase() == text }?.let { return Result.One(it.pkg) }

        val ranked = candidates.sortedByDescending { it.pkg in preferred }
        val preferredOnly = ranked.filter { it.pkg in preferred }

        // Targets first, then everything. Each pass runs exact-label before
        // prefix, so "photos" does not lose to "photoshop".
        for (pool in listOf(preferredOnly, ranked)) {
            if (pool.isEmpty()) continue
            matchIn(pool, text)?.let { return it }
        }
        return Result.None
    }

    private fun matchIn(pool: List<Candidate>, text: String): Result? {
        pool.filter { it.label.lowercase() == text }.let { exact ->
            if (exact.size == 1) return Result.One(exact[0].pkg)
            if (exact.size > 1) return Result.Ambiguous(exact.map { it.pkg })
        }
        val partial = pool.filter {
            it.label.lowercase().contains(text) || it.pkg.lowercase().contains(text)
        }
        return when (partial.size) {
            0 -> null
            1 -> Result.One(partial[0].pkg)
            else -> Result.Ambiguous(partial.map { it.pkg })
        }
    }
}
