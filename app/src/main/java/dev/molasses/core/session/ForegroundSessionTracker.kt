package dev.molasses.core.session

/**
 * Identity for the currently open foreground session. Pure.
 *
 * Two independent paths can close a session: the accessibility launcher event
 * and the `UsageStatsManager` watchdog. Both firing for one session must
 * produce one `PAUSED` row, not two.
 *
 * Deduplicating on timestamp proximity would be wrong. A user who leaves
 * Instagram and returns within the watchdog interval has two genuine sessions
 * close together, and a proximity window would swallow the second open or the
 * first close depending on which way it was tuned. An id is exact: each path
 * captures the id it saw and asks to close that one, and a stale request
 * names a session that is already gone.
 */
class ForegroundSessionTracker {

    @JvmInline
    value class SessionId(val value: Long)

    private var nextId = 1L

    var openPkg: String? = null
        private set

    var openId: SessionId? = null
        private set

    /**
     * Open a session, closing any previous one.
     *
     * @return the new id, or the existing id when [pkg] is already open.
     *   Re-entering the same app is not a new session: window state changes
     *   fire repeatedly inside one app and a new id per dialog would fragment
     *   the accounting.
     */
    fun open(pkg: String): SessionId {
        openId?.let { if (openPkg == pkg) return it }
        val id = SessionId(nextId++)
        openPkg = pkg
        openId = id
        return id
    }

    /**
     * Close [id] if it is still the open session.
     *
     * @return the package that was closed, or null when [id] is stale or
     *   already closed. Only a non-null return should produce a `PAUSED` row.
     */
    fun close(id: SessionId): String? {
        if (openId != id) return null
        val pkg = openPkg
        openId = null
        openPkg = null
        return pkg
    }

    /** Close whatever is open, whichever session that is. For teardown. */
    fun closeCurrent(): String? = openId?.let { close(it) }

    fun reset() {
        openPkg = null
        openId = null
    }
}
