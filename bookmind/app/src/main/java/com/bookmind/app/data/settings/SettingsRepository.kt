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
 * - ON_DEVICE: an open-weights model downloaded in the app and running on the phone (LiteRT-LM). Default; until
 *   the model is downloaded the classic algorithms are used.
 * - LOCAL: AI turned off; classic on-device algorithms only (TextRank, BM25, rules).
 * - REMOTE: the optional paid cloud backend; only exists in builds made with bookmind.enableCloudAi=true.
 * - MOCK: deterministic test provider (debug builds).
 */
enum class AiMode { ON_DEVICE, LOCAL, REMOTE, MOCK }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class SrsAlgorithm { FSRS, SM2 }

data class AppSettings(
    val onboardingDone: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** "tr" (default) or "en"; see [AppLanguage]. */
    val language: String = AppLanguage.DEFAULT,
    val aiMode: AiMode = AiMode.ON_DEVICE,
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
    /** Id of the chosen on-device model in [com.bookmind.app.ai.ModelCatalog]; empty = the one recommended for this phone. */
    val onDeviceModel: String = "",
    /** The "turn on AI" card on the home screen was dismissed. */
    val aiPromptDismissed: Boolean = false,
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
        val aiModeVersion = intPreferencesKey("ai_mode_version")
        val algorithm = stringPreferencesKey("algorithm")
        val retention = floatPreferencesKey("desired_retention")
        val newPerDay = intPreferencesKey("new_per_day")
        val aiLimit = intPreferencesKey("ai_daily_limit")
        val spoilerFree = booleanPreferencesKey("spoiler_free")
        val teachingLevel = stringPreferencesKey("teaching_level")
        val summaryLength = stringPreferencesKey("summary_length")
        val lastSync = longPreferencesKey("last_sync_at")
        val cursors = stringPreferencesKey("sync_cursors")
        val onDeviceModel = stringPreferencesKey("on_device_model")
        val aiPromptDismissed = booleanPreferencesKey("ai_prompt_dismissed")
    }

    val settings: Flow<AppSettings> = store.data.map(::read)

    private fun read(p: Preferences) = AppSettings(
        onboardingDone = p[Keys.onboarding] ?: false,
        theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        language = AppLanguage.normalize(p[Keys.language]),
        // Earlier versions stored LOCAL as the default and had a "computer" mode; both now mean the on-device model.
        aiMode = p[Keys.aiMode]?.let { runCatching { AiMode.valueOf(it) }.getOrNull() }?.takeIf { p[Keys.aiModeVersion] == 2 } ?: AiMode.ON_DEVICE,
        algorithm = p[Keys.algorithm]?.let { runCatching { SrsAlgorithm.valueOf(it) }.getOrNull() } ?: SrsAlgorithm.FSRS,
        desiredRetention = (p[Keys.retention] ?: 0.9f).coerceIn(0.7f, 0.97f),
        newItemsPerDay = (p[Keys.newPerDay] ?: 15).coerceIn(0, 200),
        dailyAiRequestLimit = (p[Keys.aiLimit] ?: 60).coerceIn(0, 1_000),
        spoilerFree = p[Keys.spoilerFree] ?: true,
        teachingLevel = p[Keys.teachingLevel] ?: "BASIC",
        summaryLength = p[Keys.summaryLength] ?: "MEDIUM",
        lastSyncAt = p[Keys.lastSync] ?: 0,
        syncCursors = p[Keys.cursors] ?: "{}",
        onDeviceModel = p[Keys.onDeviceModel] ?: "",
        aiPromptDismissed = p[Keys.aiPromptDismissed] ?: false,
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
            p[Keys.aiModeVersion] = 2
            p[Keys.algorithm] = s.algorithm.name
            p[Keys.retention] = s.desiredRetention.coerceIn(0.7f, 0.97f)
            p[Keys.newPerDay] = s.newItemsPerDay.coerceIn(0, 200)
            p[Keys.aiLimit] = s.dailyAiRequestLimit.coerceIn(0, 1_000)
            p[Keys.spoilerFree] = s.spoilerFree
            p[Keys.teachingLevel] = s.teachingLevel
            p[Keys.summaryLength] = s.summaryLength
            p[Keys.lastSync] = s.lastSyncAt
            p[Keys.cursors] = s.syncCursors
            p[Keys.onDeviceModel] = s.onDeviceModel
            p[Keys.aiPromptDismissed] = s.aiPromptDismissed
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
        AppLanguage.tag = AppLanguage.DEFAULT
    }

    /** Loads the stored language into [AppLanguage]; called once at startup before any UI is shown. */
    suspend fun loadLanguage(): String = current().language.also { AppLanguage.tag = it }
}
