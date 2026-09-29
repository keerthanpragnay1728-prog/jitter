package dev.molasses.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.molasses.data.datastore.CycleStateStore
import dev.molasses.data.db.MolassesDatabase
import dev.molasses.data.db.UsageEventDao
import dev.molasses.data.repo.SettingsRepository
import javax.inject.Singleton

/**
 * Not in the package tree the brief lists; added because Hilt needs a module
 * and putting it in `data/` would make a DI concern look like a storage one.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): MolassesDatabase =
        Room.databaseBuilder(context, MolassesDatabase::class.java, MolassesDatabase.NAME)
            // No destructive migration: the ledger is the reconstruction
            // source of truth, so silently dropping it on a schema change
            // would lose the only audit trail the user has.
            .build()

    @Provides
    fun usageEventDao(db: MolassesDatabase): UsageEventDao = db.usageEvents()

    @Provides
    @Singleton
    fun cycleStateStore(@ApplicationContext context: Context): CycleStateStore =
        CycleStateStore(context)

    @Provides
    @Singleton
    fun settingsRepository(
        @ApplicationContext context: Context,
        store: CycleStateStore,
    ): SettingsRepository = SettingsRepository(context, store)
}
