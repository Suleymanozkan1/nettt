package com.bookmind.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where generated content comes from. All modes except REMOTE are free:
 * - LOCAL: no AI; classic on-device algorithms (TextRank, BM25, rules). Default.
 * - ON_DEVICE: an open-weights model file running on the phone (MediaPipe LLM Inference).
 * - COMPUTER: a model on the user's own computer on the local network (Ollama, llama.cpp, LM Studio).
 * - REMOTE: the optional paid cloud backend; only exists in builds made with bookmind.enableCloudAi=true.
 * - MOCK: deterministic test provider (debug builds).
 */
enum class AiMode { LOCAL, ON_DEVICE, COMPUTER, REMOTE, MOCK }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class SrsAlgorithm { FSRS, SM2 }

data class AppSettings(
    val onboardingDone: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** "tr" (default) or "en"; see [AppLanguage]. */
    val language: String = AppLanguage.DEFAULT,
    val aiMode: AiMode = AiMode.LOCAL,
    val algorithm: SrsAlgorithm = SrsAlgorithm.FSRS,
    val desiredRetention: Float = 0.9f,
    val newItemsPerDay: Int = 15,
    val dailyAiRequestLimit: Int = 60,
    val spoilerFree: Boolean = true,
    val teachingLevel: String = "BASIC",
    val summaryLength: String = "MEDIUM",
    val lastSyncAt: Long = 0,
    /** Per-table pull cursors (server timestamps, ISO-8601) as a JSON object. */
    val syncCursors: String = "{}",
    /** Base URL of the user's own model server, e.g. http://192.168.1.20:11434 (Ollama). */
    val computerUrl: String = "",
    val computerModel: String = "",
    /** Context window of the computer model in tokens; prompts are sized to fit. */
    val computerContextTokens: Int = 8_192,
    /** File name of the imported on-device model inside filesDir/models. */
    val onDeviceModel: String = "",
    val onDeviceContextTokens: Int = 2_048,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.dataStore

    private object Keys {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val theme = stringPreferencesKey("theme")
        val language = stringPreferencesKey("language")
        val aiMode = stringPreferencesKey("ai_mode")
        val algorithm = stringPreferencesKey("algorithm")
        val retention = floatPreferencesKey("desired_retention")
        val newPerDay = intPreferencesKey("new_per_day")
        val aiLimit = intPreferencesKey("ai_daily_limit")
        val spoilerFree = booleanPreferencesKey("spoiler_free")
        val teachingLevel = stringPreferencesKey("teaching_level")
        val summaryLength = stringPreferencesKey("summary_length")
        val lastSync = longPreferencesKey("last_sync_at")
        val cursors = stringPreferencesKey("sync_cursors")
        val computerUrl = stringPreferencesKey("computer_url")
        val computerModel = stringPreferencesKey("computer_model")
        val computerContext = intPreferencesKey("computer_context")
        val onDeviceModel = stringPreferencesKey("on_device_model")
        val onDeviceContext = intPreferencesKey("on_device_context")
    }

    val settings: Flow<AppSettings> = store.data.map(::read)

    private fun read(p: Preferences) = AppSettings(
        onboardingDone = p[Keys.onboarding] ?: false,
        theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        language = AppLanguage.normalize(p[Keys.language]),
        aiMode = p[Keys.aiMode]?.let { runCatching { AiMode.valueOf(it) }.getOrNull() } ?: AiMode.LOCAL,
        algorithm = p[Keys.algorithm]?.let { runCatching { SrsAlgorithm.valueOf(it) }.getOrNull() } ?: SrsAlgorithm.FSRS,
        desiredRetention = (p[Keys.retention] ?: 0.9f).coerceIn(0.7f, 0.97f),
        newItemsPerDay = (p[Keys.newPerDay] ?: 15).coerceIn(0, 200),
        dailyAiRequestLimit = (p[Keys.aiLimit] ?: 60).coerceIn(0, 1_000),
        spoilerFree = p[Keys.spoilerFree] ?: true,
        teachingLevel = p[Keys.teachingLevel] ?: "BASIC",
        summaryLength = p[Keys.summaryLength] ?: "MEDIUM",
        lastSyncAt = p[Keys.lastSync] ?: 0,
        syncCursors = p[Keys.cursors] ?: "{}",
        computerUrl = p[Keys.computerUrl] ?: "",
        computerModel = p[Keys.computerModel] ?: "",
        computerContextTokens = (p[Keys.computerContext] ?: 8_192).coerceIn(1_024, 131_072),
        onDeviceModel = p[Keys.onDeviceModel] ?: "",
        onDeviceContextTokens = (p[Keys.onDeviceContext] ?: 2_048).coerceIn(512, 32_768),
    )

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val s = transform(read(p))
            p[Keys.onboarding] = s.onboardingDone
            p[Keys.theme] = s.theme.name
            p[Keys.language] = AppLanguage.normalize(s.language)
            AppLanguage.tag = AppLanguage.normalize(s.language)
            p[Keys.aiMode] = s.aiMode.name
            p[Keys.algorithm] = s.algorithm.name
            p[Keys.retention] = s.desiredRetention.coerceIn(0.7f, 0.97f)
            p[Keys.newPerDay] = s.newItemsPerDay.coerceIn(0, 200)
            p[Keys.aiLimit] = s.dailyAiRequestLimit.coerceIn(0, 1_000)
            p[Keys.spoilerFree] = s.spoilerFree
            p[Keys.teachingLevel] = s.teachingLevel
            p[Keys.summaryLength] = s.summaryLength
            p[Keys.lastSync] = s.lastSyncAt
            p[Keys.cursors] = s.syncCursors
            p[Keys.computerUrl] = s.computerUrl.trim()
            p[Keys.computerModel] = s.computerModel.trim()
            p[Keys.computerContext] = s.computerContextTokens
            p[Keys.onDeviceModel] = s.onDeviceModel
            p[Keys.onDeviceContext] = s.onDeviceContextTokens
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
        AppLanguage.tag = AppLanguage.DEFAULT
    }

    /** Loads the stored language into [AppLanguage]; called once at startup before any UI is shown. */
    suspend fun loadLanguage(): String = current().language.also { AppLanguage.tag = it }
}
