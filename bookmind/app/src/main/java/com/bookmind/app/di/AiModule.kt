package com.bookmind.app.di

import com.bookmind.app.data.db.AiCacheEntity
import com.bookmind.app.data.db.AiUsageEntity
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.remote.AuthRepository
import com.bookmind.app.data.remote.BackendConfig
import com.bookmind.app.data.settings.AiMode
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.util.AppClock
import com.bookmind.core.ai.AiCache
import com.bookmind.core.ai.AiProvider
import com.bookmind.core.ai.AiResponse
import com.bookmind.core.ai.AiTask
import com.bookmind.core.ai.AiUsageMeter
import com.bookmind.core.ai.LearningAi
import com.bookmind.core.ai.MockAiProvider
import com.bookmind.core.ai.RemoteAiProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AiModule {
    @Provides @Singleton
    fun learningAi(
        db: BookMindDatabase,
        settings: SettingsRepository,
        auth: AuthRepository,
        config: BackendConfig,
        clock: AppClock,
        http: OkHttpClient,
    ): LearningAi {
        val remote = if (config.isConfigured) RemoteAiProvider(config.aiEndpoint, config.anonKey, { auth.accessToken() }, http) else null
        val mock = MockAiProvider(latencyMs = 300)
        val providerSource: suspend () -> AiProvider? = {
            when (settings.current().aiMode) {
                AiMode.LOCAL -> null
                AiMode.MOCK -> mock
                // Remote needs a configured backend and a signed-in user; otherwise fall back to on-device.
                AiMode.REMOTE -> remote?.takeIf { auth.account.value != null }
            }
        }
        val cache = object : AiCache {
            override suspend fun get(key: String) = db.ai().cached(key)
            override suspend fun put(key: String, task: AiTask, value: String) = db.ai().putCache(AiCacheEntity(key, task.name, value, clock.now()))
        }
        val meter = object : AiUsageMeter {
            override suspend fun allow(task: AiTask): Boolean =
                db.ai().requestsSince(clock.startOfToday()) < settings.current().dailyAiRequestLimit
            override suspend fun record(task: AiTask, response: AiResponse) = db.ai().insertUsage(
                AiUsageEntity(task = task.name, at = clock.now(), model = response.model, inputTokens = response.inputTokens, outputTokens = response.outputTokens, costMicros = response.costMicros),
            )
        }
        return LearningAi(providerSource, cache, meter, clock::now)
    }
}
