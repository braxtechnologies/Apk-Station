package com.brax.apkstation.di

import android.content.Context
import androidx.work.WorkManager
import com.brax.apkstation.data.installer.AppInstaller
import com.brax.apkstation.data.installer.SessionInstaller
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for providing installer and work manager dependencies
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class InstallerModule {
    
    /**
     * Bind SessionInstaller as the implementation of AppInstaller
     */
    @Binds
    @Singleton
    abstract fun bindAppInstaller(
        sessionInstaller: SessionInstaller
    ): AppInstaller
}

/**
 * Module for providing WorkManager instance
 */
@Module
@InstallIn(SingletonComponent::class)
object WorkManagerModule {
    
    @Provides
    @Singleton
    fun provideWorkManager(
        @ApplicationContext context: Context
    ): WorkManager {
        return WorkManager.getInstance(context)
    }
}


