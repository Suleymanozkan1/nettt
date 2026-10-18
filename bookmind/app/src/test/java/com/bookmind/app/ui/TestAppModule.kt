package com.bookmind.app.ui

import android.content.Context
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.remote.BackendConfig
import com.bookmind.app.di.AppModule
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AppModule::class])
object TestAppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): BookMindDatabase =
        Room.inMemoryDatabaseBuilder(context, BookMindDatabase::class.java).allowMainThreadQueries()
            .addCallback(object : androidx.room.RoomDatabase.Callback() { override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) = com.bookmind.app.sync.SyncTriggers.install(db) })
            .build()

    @Provides @Singleton fun okHttp(): OkHttpClient = OkHttpClient()

    @Provides @Singleton fun backendConfig(): BackendConfig = BackendConfig("", "")

    @Provides @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        return WorkManager.getInstance(context)
    }
}
