package dev.molasses.core.safety

/**
 * When the full-screen overlays mute the music stream, and when they give it
 * back. Pure.
 *
 * ## Why a mute at all
 * On hardware a Reel kept playing under the LEASE EXPIRED gate with focus
 * granted and the pause key dispatched: Instagram ignored both, and an
 * overlay does not pause the Activity under it. The mute is the one lever
 * left that the app under the gate cannot decline.
 *
 * ## What it touches and what it never does
 * The mute flag on `STREAM_MUSIC` and nothing else: never the volume level,
 * and never with volume UI. Only on the overlays [OverlayAudio.silences]
 * names (the expired gate, the walking gate, a lock raised mid-session),
 * never at entry, where the audio is usually the user's own.
 *
 * ## Only undo what we did
 * If the stream was already muted when the first overlay went up, the user
 * or another app did that, and it is left alone now and later: no mute, no
 * unmute. [State.ours] records whether the mute in force is ours, and it is
 * persisted, so a process death mid-mute is repaired on the next service
 * connect by [onConnect] rather than leaving the phone silent.
 *
 * ## Several overlays
 * An overlay can go up before the one it replaces has come down (the
 * expired gate hands over to a lock). Each holder is tracked, the stream is
 * muted by the first and given back only when the last one releases, so a
 * handover never unmutes under the overlay that is still up.
 */
object MuteGuard {

    data class State(val holders: Set<String> = emptySet(), val ours: Boolean = false)

    enum class Action { MUTE, UNMUTE, NONE }

    data class Step(val state: State, val action: Action)

    /**
     * An overlay going up.
     *
     * @param silences `OverlayAudio.silences` for its kind.
     * @param streamMuted whether the music stream is muted right now.
     */
    fun take(state: State, holder: String, silences: Boolean, streamMuted: Boolean): Step = when {
        !silences -> Step(state, Action.NONE)
        holder in state.holders -> Step(state, Action.NONE)
        // Someone already holds it: ours or not, that stays as it is.
        state.holders.isNotEmpty() -> Step(state.copy(holders = state.holders + holder), Action.NONE)
        // Muted before we arrived: hold, but it is not ours to undo.
        streamMuted -> Step(State(setOf(holder), ours = false), Action.NONE)
        else -> Step(State(setOf(holder), ours = true), Action.MUTE)
    }

    /** An overlay coming down, through its choke point. */
    fun release(state: State, holder: String): Step {
        if (holder !in state.holders) return Step(state, Action.NONE)
        val rest = state.holders - holder
        if (rest.isNotEmpty()) return Step(state.copy(holders = rest), Action.NONE)
        return Step(State(), if (state.ours) Action.UNMUTE else Action.NONE)
    }

    /**
     * The mute call failed: nothing is muted, so nothing is ours to undo.
     * The holders stay, so their releases still balance.
     */
    fun muteFailed(state: State): State = state.copy(ours = false)

    /**
     * The service connected. A persisted "ours" can only mean a process died
     * with our mute in force, because every release path clears it. Give it
     * back.
     */
    fun onConnect(persistedOurs: Boolean): Action = if (persistedOurs) Action.UNMUTE else Action.NONE
}
