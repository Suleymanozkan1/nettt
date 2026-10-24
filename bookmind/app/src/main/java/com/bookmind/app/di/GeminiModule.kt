package com.bookmind.app.di

import android.content.Context
import com.bookmind.app.ai.GeminiConfig
import com.bookmind.core.ai.gemini.GeminiClient
import com.bookmind.core.ai.gemini.TextModel
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Named
import javax.inject.Singleton

/** Gemini API access. Tests replace this module so they never call the real API, even with a key on the machine. */
@Module
@InstallIn(SingletonComponent::class)
object GeminiModule {
    @Provides @Singleton fun config(@ApplicationContext context: Context): GeminiConfig = GeminiConfig.fromBuildConfig(context)

    /** Book summaries: patient retries with backoff (see ai-config.properties). */
    @Provides @Singleton @Named(BACKGROUND)
    fun background(config: GeminiConfig, http: OkHttpClient): TextModel = GeminiClient(config.backgroundSettings(), http)

    /** Ask the book, tutor, quizzes, study modules: short retries, then the classic method. */
    @Provides @Singleton @Named(INTERACTIVE)
    fun interactive(config: GeminiConfig, http: OkHttpClient): TextModel = GeminiClient(config.interactiveSettings(), http)

    const val BACKGROUND = "gemini-background"
    const val INTERACTIVE = "gemini-interactive"
}
