package dev.molasses.core.ui

/**
 * The A-Z rail down the right margin of a long list.
 *
 * ## Why a shared object and not a setting screen detail
 * CFG's target list and the launcher's app drawer are the same list of the
 * same eighty apps with different rows on top. Two rails would be two answers
 * to "which letter is this app under", and the first divergence is something
 * like a leading emoji or a leading digit, where a difference is invisible
 * until someone reports that an app is in the list but not under its letter.
 * So the bucketing lives here and both hosts ask it.
 *
 * ## What is a letter
 * The first character, uppercased, when it is A to Z. Everything else is
 * [OTHER], which covers digits, leading punctuation, emoji and every
 * non-Latin script at once.
 *
 * Lumping non-Latin scripts into one bucket is a real limitation and worth
 * naming rather than discovering. A device in Hindi or Japanese puts most of
 * its apps under a single glyph, which makes the rail useless there rather
 * than wrong. The alternative is per-locale collation, which is
 * `java.text.Collator` and a whole class of its own; this module is pure and
 * cannot reach it. The search box remains the answer that works in every
 * script, which is why the rail sits beside it rather than replacing it.
 *
 * ## The rail shows only the letters that are there
 * A fixed A to Z strip is twenty six targets of which a dozen do nothing, and
 * a rail with dead entries teaches the user that the rail does not work.
 * [lettersOf] returns what the list actually contains, in order, so every
 * target on screen goes somewhere.
 *
 * Pure; no Android imports. Unit-tested in `AlphaIndexTest`.
 */
object AlphaIndex {

    /** Digits, punctuation, emoji and every non-Latin script. See the doc. */
    const val OTHER = '#'

    /**
     * The bucket [label] belongs in.
     *
     * Leading whitespace is skipped rather than bucketed, because a label
     * that begins with a space is a packaging accident and the user still
     * expects it under its first real letter.
     */
    fun bucketOf(label: String): Char {
        val c = label.trimStart().firstOrNull() ?: return OTHER
        val upper = c.uppercaseChar()
        return if (upper in 'A'..'Z') upper else OTHER
    }

    /**
     * The buckets present, in the order the list presents them.
     *
     * Order of first appearance rather than sorted, so the rail matches the
     * list even when the list is not purely alphabetical. CFG's target list
     * is tracked-first, so its letters are not sorted overall, and a rail
     * claiming otherwise would scroll to the wrong place.
     */
    fun lettersOf(labels: List<String>): List<Char> {
        val seen = LinkedHashSet<Char>()
        for (label in labels) seen += bucketOf(label)
        return seen.toList()
    }

    /**
     * The first position in [labels] under [letter], or null.
     *
     * Null rather than zero for a letter that is not present. Zero is a
     * perfectly good index and would scroll the list to the top, which reads
     * as "this rail is broken" rather than "nothing is filed there". The
     * caller declines to scroll instead.
     */
    fun firstIndexOf(labels: List<String>, letter: Char): Int? {
        val i = labels.indexOfFirst { bucketOf(it) == letter }
        return if (i < 0) null else i
    }

    /**
     * The letter under a touch at [yFraction] down the rail.
     *
     * Null when the rail is empty, or when the fraction is outside it, which
     * happens on a drag that leaves the rail vertically. The caller stops
     * scrolling rather than pinning to an end: a finger that has slid off the
     * top should not keep dragging the list to A.
     *
     * Clamping inside the range is deliberate and different: a fraction of
     * exactly 1.0 is the last letter rather than one past the end, which is
     * the off-by-one that makes the bottom entry unreachable.
     */
    fun letterAt(yFraction: Float, letters: List<Char>): Char? {
        if (letters.isEmpty()) return null
        if (yFraction < 0f || yFraction > 1f) return null
        val i = (yFraction * letters.size).toInt().coerceIn(letters.indices)
        return letters[i]
    }
}
