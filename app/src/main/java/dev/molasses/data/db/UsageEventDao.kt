package dev.molasses.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.molasses.core.model.EventType
import kotlinx.coroutines.flow.Flow

/** Aggregate row for [UsageEventDao.gateOutcomesByPath]. */
data class GateOutcomeRow(val path: String, val type: EventType, val count: Int)

@Dao
interface UsageEventDao {

    @Insert
    suspend fun insert(event: UsageEventEntity): Long

    @Insert
    suspend fun insertAll(events: List<UsageEventEntity>)

    @Query("SELECT * FROM usage_events ORDER BY wallMs DESC LIMIT :limit")
    fun recent(limit: Int = 500): Flow<List<UsageEventEntity>>

    @Query("SELECT * FROM usage_events WHERE pkg = :pkg AND wallMs >= :sinceWallMs ORDER BY wallMs ASC")
    suspend fun forPackageSince(pkg: String, sinceWallMs: Long): List<UsageEventEntity>

    @Query("SELECT * FROM usage_events WHERE type = :type ORDER BY wallMs DESC LIMIT :limit")
    suspend fun ofType(type: EventType, limit: Int = 200): List<UsageEventEntity>

    /** Requested vs. actual armed duration, for the SS11 latency report. */
    @Query("SELECT meta FROM usage_events WHERE type = 'STALL_ARMED' ORDER BY wallMs DESC LIMIT :limit")
    suspend fun stallMetas(limit: Int = 500): List<String?>

    /** Gate outcomes split by sensing pipeline, for calibration review. */
    @Query(
        "SELECT sensorPath AS path, type AS type, COUNT(*) AS count FROM usage_events " +
            "WHERE type IN ('GATE_PASSED', 'GATE_ABANDONED') AND sensorPath IS NOT NULL " +
            "GROUP BY sensorPath, type",
    )
    suspend fun gateOutcomesByPath(): List<GateOutcomeRow>

    @Query("SELECT COUNT(*) FROM usage_events")
    suspend fun count(): Int

    @Query("DELETE FROM usage_events WHERE wallMs < :beforeWallMs")
    suspend fun prune(beforeWallMs: Long): Int

    @Query("DELETE FROM usage_events")
    suspend fun clear()
}
