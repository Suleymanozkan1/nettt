package com.bookmind.app.ai

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.bookmind.app.BuildConfig
import com.bookmind.core.ai.gemini.GeminiSettings
import com.bookmind.core.ai.summary.SummaryConfig
import java.security.MessageDigest

/**
 * Gemini settings of this build: model and summary sizes from `ai-config.properties`, the API key from
 * local.properties / the GEMINI_API_KEY environment variable (see app/build.gradle.kts). A build without a key
 * uses the classic algorithms everywhere.
 */
data class GeminiConfig(
    val apiKey: String,
    val model: String,
    val thinkingLevel: String?,
    val maxRetries: Int,
    val summary: SummaryConfig,
    val baseUrl: String = DEFAULT_BASE_URL,
    /** Package name and signing-certificate SHA-1 headers for keys restricted to this Android app. */
    val appHeaders: Map<String, String> = emptyMap(),
) {
    val configured: Boolean get() = apiKey.isNotBlank() && model.isNotBlank()

    /** Book summaries run in the background and can wait out rate limits. */
    fun backgroundSettings() = GeminiSettings(apiKey, model, thinkingLevel, baseUrl, maxRetries = maxRetries, extraHeaders = appHeaders)

    /** Someone is waiting on screen: fewer, shorter retries before the classic method takes over. */
    fun interactiveSettings() = GeminiSettings(apiKey, model, thinkingLevel, baseUrl, maxRetries = minOf(maxRetries, 2), maxBackoffMs = 10_000, extraHeaders = appHeaders)

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

        fun fromBuildConfig(context: Context) = GeminiConfig(
            apiKey = BuildConfig.GEMINI_API_KEY,
            model = BuildConfig.GEMINI_MODEL,
            thinkingLevel = BuildConfig.GEMINI_THINKING_LEVEL.ifBlank { null },
            maxRetries = BuildConfig.GEMINI_MAX_RETRIES,
            summary = SummaryConfig(
                chapterRatio = BuildConfig.SUMMARY_CHAPTER_RATIO,
                overallMinWords = BuildConfig.SUMMARY_OVERALL_MIN_WORDS,
                overallMaxWords = BuildConfig.SUMMARY_OVERALL_MAX_WORDS,
                chunkMinWords = BuildConfig.SUMMARY_CHUNK_MIN_WORDS,
                chunkMaxWords = BuildConfig.SUMMARY_CHUNK_MAX_WORDS,
            ),
            appHeaders = appHeaders(context),
        )

        /** `X-Android-Package` / `X-Android-Cert` (SHA-1 of the signing certificate, upper-case hex). */
        @Suppress("DEPRECATION")
        fun appHeaders(context: Context): Map<String, String> = runCatching {
            val pm = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            val cert = signatures?.firstOrNull() ?: return@runCatching emptyMap()
            val sha1 = MessageDigest.getInstance("SHA-1").digest(cert.toByteArray()).joinToString("") { "%02X".format(it) }
            mapOf("X-Android-Package" to context.packageName, "X-Android-Cert" to sha1)
        }.getOrDefault(emptyMap())
    }
}
