package dev.molasses.data.repo

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dev.molasses.core.model.EventType
import dev.molasses.data.db.UsageEventDao
import dev.molasses.data.db.UsageEventEntity
import dev.molasses.engine.FrictionLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Room-backed [FrictionLedger].
 *
 * [log] is called from the accessibility callback thread and must not touch
 * disk, so rows go onto an unbounded-ish buffered channel and a single writer
 * coroutine drains them in batches. A full buffer drops the oldest rows rather
 * than blocking the caller: losing a ledger row degrades the debug view,
 * whereas blocking that thread delays every subsequent accessibility event.
 */
class RoomFrictionLedger(
    context: Context,
    private val dao: UsageEventDao,
    scope: CoroutineScope,
) : FrictionLedger {

    private val resolver = context.applicationContext.contentResolver

    private val bootId: Int by lazy {
        runCatching {
            Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, 0)
        }.getOrDefault(0)
    }

    private val queue = Channel<UsageEventEntity>(capacity = 256)

    init {
        scope.launch(Dispatchers.IO) {
            val batch = ArrayList<UsageEventEntity>(32)
            for (row in queue) {
                batch += row
                // Opportunistically coalesce whatever else is already queued.
                while (batch.size < 32) {
                    val next = queue.tryReceive().getOrNull() ?: break
                    batch += next
                }
                runCatching { dao.insertAll(batch) }
                batch.clear()
            }
        }
    }

    override fun log(pkg: String, type: EventType, meta: String?) {
        queue.trySend(
            UsageEventEntity(
                pkg = pkg,
                type = type,
                wallMs = System.currentTimeMillis(),
                elapsedMs = SystemClock.elapsedRealtime(),
                bootId = bootId,
                meta = meta,
            ),
        )
    }

    fun currentBootId(): Int = bootId
}
