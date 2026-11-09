package com.soundscape.app.di

import com.soundscape.app.presentation.shell.ShellInfo
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

class DefaultShellInfo @Inject constructor() : ShellInfo {
    override val applicationName = "SoundScape"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ShellModule {
    @Binds
    abstract fun bindShellInfo(implementation: DefaultShellInfo): ShellInfo
}
