package com.soundscape.app.core.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Application cache wiring; Room is opened lazily when requested from Hilt. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    /** One persistent cache; no destructive migration fallback. */
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): SoundScapeDatabase =
        Room.databaseBuilder(context, SoundScapeDatabase::class.java, "soundscape-cache.db").build()

    /** DAO backed by the application singleton database. */
    @Provides
    fun cacheDao(database: SoundScapeDatabase): CacheDao = database.cacheDao()
}
