package com.brax.apkstation.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for providing installer and work manager dependencies
 * 
 * Note: The installers (NewSessionInstaller, AppInstallerManager) are already
 * annotated with @Singleton and @Inject, so they don't need explicit bindings here.
 * Hilt will automatically provide them.
 */

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
