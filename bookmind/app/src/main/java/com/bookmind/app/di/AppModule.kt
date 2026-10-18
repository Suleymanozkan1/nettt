package com.bookmind.app.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.remote.BackendConfig
import com.bookmind.app.data.remote.KeystoreSessionStore
import com.bookmind.app.data.remote.SessionStore
import com.bookmind.app.util.AppClock
import com.bookmind.app.util.SystemClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): BookMindDatabase =
        Room.databaseBuilder(context, BookMindDatabase::class.java, BookMindDatabase.NAME)
            // Schema changes ship with migrations (see BookMindDatabase.autoMigrations); never wipe user data.
            .build()

    @Provides @Singleton fun okHttp(): OkHttpClient = OkHttpClient.Builder().retryOnConnectionFailure(true).build()

    @Provides @Singleton fun backendConfig(): BackendConfig = BackendConfig.fromBuildConfig()

    @Provides fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun clock(impl: SystemClock): AppClock
    @Binds abstract fun sessionStore(impl: KeystoreSessionStore): SessionStore
}
