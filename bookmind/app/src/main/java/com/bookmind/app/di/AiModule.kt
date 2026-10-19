package com.bookmind.app.di

import com.bookmind.app.BuildConfig
import com.bookmind.app.ai.ModelCatalog
import com.bookmind.app.ai.OnDeviceModels
import com.bookmind.app.data.db.AiCacheEntity
import com.bookmind.app.data.db.AiUsageEntity
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.remote.AuthRepository
import com.bookmind.app.data.remote.BackendConfig
import com.bookmind.app.data.settings.AiMode
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.util.AppClock
import com.bookmind.core.ai.AiCache
import com.bookmind.core.ai.AiException
import com.bookmind.core.ai.AiProvider
import com.bookmind.core.ai.AiRequest
import com.bookmind.core.ai.OnDeviceProvider
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
    /** Only reachable in cloud builds: REMOTE chosen but not signed in. The classic engine is used, with a warning. */
    private object Unavailable : AiProvider {
        override val id = "unavailable"
        override suspend fun complete(request: AiRequest): AiResponse = throw AiException.NotConfigured()
    }

    @Provides @Singleton
    fun learningAi(
        db: BookMindDatabase,
        settings: SettingsRepository,
        auth: AuthRepository,
        config: BackendConfig,
        clock: AppClock,
        http: OkHttpClient,
        models: OnDeviceModels,
    ): LearningAi {
        // Paid cloud AI only exists in builds made with bookmind.enableCloudAi=true; default builds are free-only.
        val remote = if (BuildConfig.CLOUD_AI_ENABLED && config.isConfigured) RemoteAiProvider(config.aiEndpoint, config.anonKey, { auth.accessToken() }, http) else null
        val mock = MockAiProvider(latencyMs = 300)
        val onDevice = java.util.concurrent.ConcurrentHashMap<String, OnDeviceProvider>()
        val providerSource: suspend () -> AiProvider? = {
            val s = settings.current()
            when (s.aiMode) {
                AiMode.LOCAL -> null
                AiMode.MOCK -> mock
                // Until the model is downloaded the classic algorithms are used; that is expected, not an error.
                AiMode.ON_DEVICE -> (ModelCatalog.byId(s.onDeviceModel) ?: ModelCatalog.recommended(models.deviceRamGb()))
                    ?.takeIf { models.supported && models.isInstalled(it) }
                    ?.let { spec ->
                        onDevice.getOrPut(spec.id) {
                            OnDeviceProvider(spec.displayName, spec.contextTokens) { prompt -> models.generate(spec, prompt) }
                        }
                    }
                // Never a silent fallback to a paid service: without the cloud build flag this is simply unavailable.
                AiMode.REMOTE -> remote?.takeIf { auth.account.value != null } ?: Unavailable
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
