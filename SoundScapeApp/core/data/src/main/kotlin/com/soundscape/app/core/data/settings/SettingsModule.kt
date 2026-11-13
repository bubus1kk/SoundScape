package com.soundscape.app.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.soundscape.app.core.domain.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.appearanceStore by preferencesDataStore(name = "appearance")

/** One application-scoped Preferences DataStore; no credentials are stored here. */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    /** Returns the single store associated with the application context. */
    @Provides @Singleton
    fun store(@ApplicationContext context: Context): DataStore<Preferences> = context.appearanceStore

    /** Exposes the storage adapter through the independent domain contract. */
    @Provides @Singleton
    fun repository(implementation: DataStoreSettingsRepository): SettingsRepository = implementation
}
