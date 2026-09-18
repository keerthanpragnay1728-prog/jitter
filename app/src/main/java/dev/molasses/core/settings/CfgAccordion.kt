package dev.molasses.core.settings

/**
 * Which section of CFG is open.
 *
 * ## Why this is a type and not a boolean per section
 * Eight sections is eight booleans and two hundred and fifty six states, of
 * which the ones anyone cares about are "setup plus at most one other". As a
 * type with one resolver there is one rule, it is total, and the two things
 * that matter about it are assertable rather than hoped for.
 *
 * ## The rule
 * [SETUP] is pinned. Every other section is a single-open accordion: opening
 * one closes whichever was open before.
 *
 * Single-open rather than free multi-open because eight expanded sections is
 * the screen CFG already was, and the whole point of the change is that a
 * tester should see the map before the territory. It also bounds the list: a
 * user cannot scroll past a section they opened three taps ago and forgot
 * about. The usual argument for multi-open, comparing two panes side by side,
 * does not apply to any pair of these. They are independent settings groups,
 * not readings of the same thing.
 *
 * ## Why SETUP is outside the accordion rather than merely open first
 * It is the permission checklist and the live service state, which is the
 * only thing on this screen that says why nothing is working. On a fresh
 * install that is the one row that matters, and a user whose first act is to
 * open TARGET APPS would have closed it without ever knowing it was there.
 *
 * So it is pinned rather than default-open: opening any other section leaves
 * it alone. It can still be collapsed deliberately, because a user who has
 * finished setup should be able to get it out of the way, and an expanding
 * panel nobody can ever close is a panel that reads as a bug.
 *
 * ## Not persisted
 * [initial] is what CFG opens as, every time. Expansion is a reading position
 * rather than a preference: a setting the user changed should still be where
 * they left it, but which drawer they had open while doing it is not
 * something they asked to be remembered. The host holds this in `remember`
 * and not `rememberSaveable` for the same reason, and `CfgAccordionTest`
 * asserts what [initial] contains so a later "helpful" persistence has to
 * disagree with a test rather than with a comment.
 *
 * Pure; no Android imports. Unit-tested in `CfgAccordionTest`.
 */
object CfgAccordion {

    /**
     * Every section of CFG, in the order it is rendered.
     *
     * Declaration order is render order, so a section added here appears on
     * screen and cannot be silently left out of the accordion. `entries` is
     * what the host iterates.
     */
    enum class Section {
        /** Permissions and live service state. Pinned. See the class doc. */
        SETUP,
        LADDER,
        TARGETS,
        POLICY,
        GATE,
        APPEARANCE,
        SAFETY,
        TRY,
    }

    /** The section that is pinned rather than part of the single-open group. */
    val PINNED: Section = Section.SETUP

    /**
     * @param setupOpen the pinned section, tracked separately because it is
     *   not in competition with the others.
     * @param open the one section of the accordion group that is open, or
     *   null when none is.
     */
    data class State(
        val setupOpen: Boolean = true,
        val open: Section? = null,
    )

    /** What CFG opens as. Setup expanded, everything else closed. */
    fun initial(): State = State(setupOpen = true, open = null)

    fun isOpen(state: State, section: Section): Boolean =
        if (section == PINNED) state.setupOpen else state.open == section

    /**
     * Tap a section header.
     *
     * The pinned section toggles on its own. Any other section becomes the
     * open one, or closes if it already was, and either way the pinned
     * section is untouched. That last clause is the whole reason this is a
     * function rather than two assignments at the call site.
     */
    fun toggle(state: State, section: Section): State = when {
        section == PINNED -> state.copy(setupOpen = !state.setupOpen)
        state.open == section -> state.copy(open = null)
        else -> state.copy(open = section)
    }

    /**
     * The chevron for a header, as text.
     *
     * Text rather than an icon asset: the whole screen is a character grid,
     * and a vector drawable here would be the one thing on it that is not
     * made of type. Two glyphs, not a rotation, because a rotating chevron
     * needs an animation and a rotation modifier to say what one character
     * says on its own.
     */
    fun chevron(open: Boolean): String = if (open) CHEVRON_OPEN else CHEVRON_CLOSED

    /**
     * Open, written as an escape rather than as the character itself.
     *
     * The glyph is outside ASCII, and this repository has already shipped one
     * file that was not valid UTF-8 and another carrying a BOM. An escape
     * cannot be mangled by an editor, a patch tool or a paste, and
     * check-encoding.sh has nothing to find. The closed form is an ordinary
     * ASCII character and is written as itself.
     *
     * In Kotlin rather than strings.xml because it is a glyph and not copy,
     * the same way BitGlyph.SLIT_NORMAL is. There is nothing here to
     * translate and nothing a translator should be invited to reword.
     */
    const val CHEVRON_OPEN = "\u2228"

    /** Closed. */
    const val CHEVRON_CLOSED = ">"
}
