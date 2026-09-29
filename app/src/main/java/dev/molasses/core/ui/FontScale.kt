package dev.molasses.core.ui

/**
 * The five font-size steps offered in settings.
 *
 * ## It multiplies, it does not replace
 * The returned value is a **multiplier applied to `sp` sizes**, so the
 * platform's own font scale still applies underneath and the two compound. A
 * user who has already enlarged system text has done so for a reason, usually
 * eyesight, and an app that substitutes its own absolute sizes silently
 * overrides that. [MEDIUM] is 1.0 and therefore a no-op, which is what
 * "default" has to mean for this to be true.
 *
 * ## Why a fixed ladder and not a slider
 * Five named steps are a decision a user makes once. A continuous slider
 * invites fiddling and produces values like 1.07 that nobody chose on purpose,
 * and every one of them has to render correctly in a monospace grid where the
 * telemetry row and the ledger bars must not wrap.
 *
 * Pure; no Android imports. Unit-tested in `FontScaleTest`.
 */
enum class FontScale(val multiplier: Float) {
    VERY_SMALL(0.80f),
    SMALL(0.90f),
    MEDIUM(1.00f),
    LARGE(1.15f),
    VERY_LARGE(1.30f),
    ;

    companion object {
        val DEFAULT = MEDIUM

        /**
         * Persisted as an ordinal, so an unknown or out-of-range stored value
         * has to resolve to something. It resolves to [DEFAULT] rather than
         * throwing: a corrupt preference should make the text ordinary, not
         * make the launcher refuse to start.
         */
        fun fromOrdinal(ordinal: Int): FontScale =
            entries.getOrNull(ordinal) ?: DEFAULT

        /**
         * How many cells wide a fixed-width bar may be at this scale.
         *
         * The ledger draws a 20 cell bar. At the larger steps 20 monospace
         * cells plus a label plus a percentage no longer fit a narrow phone,
         * and the grid collapses into a wrapped mess. Reducing the cell count
         * keeps the row on one line, which is the property that matters;
         * the bar is a proportion, so fewer cells costs only resolution.
         */
        fun barCells(scale: FontScale): Int = when (scale) {
            VERY_SMALL, SMALL, MEDIUM -> 20
            LARGE -> 16
            VERY_LARGE -> 12
        }
    }
}
