package com.bookmind.app.di

import com.bookmind.app.BuildConfig
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
import com.bookmind.core.ai.ChatTemplate
import com.bookmind.core.ai.LocalServerProvider
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
    /** Characters of prompt that fit a context of [tokens], leaving ~1k tokens for the answer (≈3 chars/token). */
    fun budgetChars(tokens: Int) = ((tokens - 1_024).coerceAtLeast(512) * 3)

    /** The chosen local model is not set up (no file, no server address): the on-device engine is used, with a warning. */
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
        var computer: Pair<String, LocalServerProvider>? = null
        val providerSource: suspend () -> AiProvider? = {
            val s = settings.current()
            when (s.aiMode) {
                AiMode.LOCAL -> null
                AiMode.MOCK -> mock
                AiMode.ON_DEVICE -> models.file(s.onDeviceModel)?.let { f ->
                    OnDeviceProvider(f.name, ChatTemplate.forModelName(f.name), budgetChars(s.onDeviceContextTokens)) { prompt ->
                        models.generate(f.name, s.onDeviceContextTokens, prompt)
                    }
                } ?: Unavailable
                AiMode.COMPUTER -> {
                    val key = "${s.computerUrl}|${s.computerModel}|${s.computerContextTokens}"
                    computer?.takeIf { it.first == key }?.second
                        ?: runCatching { LocalServerProvider(s.computerUrl, s.computerModel, budgetChars(s.computerContextTokens), http) }.getOrNull()
                            ?.also { computer = key to it }
                        ?: Unavailable
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
