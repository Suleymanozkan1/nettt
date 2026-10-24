package com.bookmind.app.di

import com.bookmind.app.BuildConfig
import com.bookmind.app.ai.GeminiConfig
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
import com.bookmind.core.ai.AiResponse
import com.bookmind.core.ai.AiTask
import com.bookmind.core.ai.AiUsageMeter
import com.bookmind.core.ai.LearningAi
import com.bookmind.core.ai.MockAiProvider
import com.bookmind.core.ai.RemoteAiProvider
import com.bookmind.core.ai.gemini.GeminiProvider
import com.bookmind.core.ai.gemini.TextModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Named
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
        gemini: GeminiConfig,
        @Named(GeminiModule.INTERACTIVE) geminiModel: TextModel,
    ): LearningAi {
        // Paid cloud AI only exists in builds made with bookmind.enableCloudAi=true; default builds are free-only.
        val remote = if (BuildConfig.CLOUD_AI_ENABLED && config.isConfigured) RemoteAiProvider(config.aiEndpoint, config.anonKey, { auth.accessToken() }, http) else null
        val mock = MockAiProvider(latencyMs = 300)
        val geminiProvider = GeminiProvider(geminiModel)
        val providerSource: suspend () -> AiProvider? = {
            val s = settings.current()
            when (s.aiMode) {
                AiMode.LOCAL -> null
                AiMode.MOCK -> mock
                // A build without an API key simply uses the classic algorithms; that is expected, not an error.
                AiMode.GEMINI -> geminiProvider.takeIf { gemini.configured }
                // The backend proxy only exists in builds with the cloud flag.
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
        // Concepts and review cards are extracted for every text chunk of a book during import: that stays on the
        // free classic engine, while the book summary (BookSummaryRepository) and the interactive features use Gemini.
        return LearningAi(providerSource, cache, meter, clock::now, localOnly = setOf(AiTask.BOOK_ANALYSIS, AiTask.CHAPTER_ANALYSIS))
    }
}
