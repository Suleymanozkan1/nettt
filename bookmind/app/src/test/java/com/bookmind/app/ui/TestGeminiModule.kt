package com.bookmind.app.ui

import com.bookmind.app.FakeGemini
import com.bookmind.app.ai.GeminiConfig
import com.bookmind.app.di.GeminiModule
import com.bookmind.core.ai.gemini.TextModel
import com.bookmind.core.ai.summary.SummaryConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Named
import javax.inject.Singleton

/**
 * Tests never call the real Gemini API, even when a developer has a key in local.properties. By default Gemini
 * counts as "not configured" (like a build without a key); a test can set [configured] before injecting to get
 * the [fake] model instead.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [GeminiModule::class])
object TestGeminiModule {
    @Volatile var configured = false
    @Volatile var fake = FakeGemini()

    fun reset() { configured = false; fake = FakeGemini() }

    @Provides @Singleton
    fun config(): GeminiConfig = GeminiConfig(if (configured) "test-key" else "", "fake-gemini", "low", 1, SummaryConfig(chunkMinWords = 500, chunkMaxWords = 2_000))

    @Provides @Singleton @Named(GeminiModule.BACKGROUND) fun background(): TextModel = fake

    @Provides @Singleton @Named(GeminiModule.INTERACTIVE) fun interactive(): TextModel = fake
}
