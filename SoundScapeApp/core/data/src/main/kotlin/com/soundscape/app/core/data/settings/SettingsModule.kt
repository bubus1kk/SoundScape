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

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    @Provides @Singleton
    fun store(@ApplicationContext context: Context): DataStore<Preferences> = context.appearanceStore

    @Provides @Singleton
    fun repository(implementation: DataStoreSettingsRepository): SettingsRepository = implementation
}
