package com.soundscape.app.core.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): SoundScapeDatabase =
        Room.databaseBuilder(context, SoundScapeDatabase::class.java, "soundscape-cache.db").build()

    @Provides
    fun cacheDao(database: SoundScapeDatabase): CacheDao = database.cacheDao()
}
