package dev.molasses.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The ledger. Version 1, exported to `app/schemas` (see `room.schemaLocation`
 * in app/build.gradle.kts).
 *
 * ## What a version bump does
 * There are no migrations and no destructive fallback, deliberately: the
 * ledger is the reconstruction source and silently dropping it would lose the
 * only audit trail the user has. So raising `version` without adding a
 * `Migration(1, 2)` to the builder in `AppModule` does not wipe anything. It
 * crashes: Room throws `IllegalStateException` ("A migration from 1 to 2 was
 * required but not found") the first time anything opens the database. In
 * practice that is the ledger's first write after the service connects, and
 * it happens on every launch until a build with the migration is installed.
 *
 * A bump therefore needs, in one change: the new version here, a `Migration`
 * in `AppModule`, the new schema JSON that the build writes to `app/schemas`,
 * and a migration test against the old JSON.
 */
@Database(
    entities = [UsageEventEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MolassesDatabase : RoomDatabase() {
    abstract fun usageEvents(): UsageEventDao

    companion object {
        const val NAME = "molasses.db"
    }
}
