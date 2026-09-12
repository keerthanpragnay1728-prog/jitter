package dev.molasses.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.molasses.core.model.EventType

/**
 * Append-only ledger row. Never updated, never deleted except by retention
 * pruning, so the accumulation can be reconstructed and audited after the fact.
 *
 * Both clocks are recorded on every row along with [bootId], because that
 * triple is the only way to tell "eight hours passed" from "the clock was
 * moved" after the fact.
 */
@Entity(tableName = "usage_events", indices = [Index("wallMs"), Index("pkg")])
data class UsageEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val type: EventType,
    /** `System.currentTimeMillis()`. */
    val wallMs: Long,
    /** `SystemClock.elapsedRealtime()`. */
    val elapsedMs: Long,
    /** `Settings.Global.BOOT_COUNT`. */
    val bootId: Int,
    val meta: String? = null,
    /**
     * Which sensing pipeline was live when this row was written
     * ([dev.molasses.core.model.GateProgress.Path]), or null outside a gate.
     *
     * Stamped on *every* row, not just gate rows: when a gate pass looks wrong
     * in hindsight, the first question is which calibration domain produced
     * it, and that has to be answerable from the ledger alone.
     */
    val sensorPath: String? = null,
)
