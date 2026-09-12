package dev.molasses.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

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
