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

/** Where generated content comes from. LOCAL never sends book text off the device. */
enum class AiMode { LOCAL, REMOTE, MOCK }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class SrsAlgorithm { FSRS, SM2 }

data class AppSettings(
    val onboardingDone: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** "system", "tr" or "en". */
    val language: String = "system",
    val aiMode: AiMode = AiMode.LOCAL,
    val algorithm: SrsAlgorithm = SrsAlgorithm.FSRS,
    val desiredRetention: Float = 0.9f,
    val newItemsPerDay: Int = 15,
    val dailyAiRequestLimit: Int = 60,
    val spoilerFree: Boolean = true,
    val teachingLevel: String = "BASIC",
    val summaryLength: String = "MEDIUM",
    val lastSyncAt: Long = 0,
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
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            onboardingDone = p[Keys.onboarding] ?: false,
            theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            language = p[Keys.language] ?: "system",
            aiMode = p[Keys.aiMode]?.let { runCatching { AiMode.valueOf(it) }.getOrNull() } ?: AiMode.LOCAL,
            algorithm = p[Keys.algorithm]?.let { runCatching { SrsAlgorithm.valueOf(it) }.getOrNull() } ?: SrsAlgorithm.FSRS,
            desiredRetention = (p[Keys.retention] ?: 0.9f).coerceIn(0.7f, 0.97f),
            newItemsPerDay = (p[Keys.newPerDay] ?: 15).coerceIn(0, 200),
            dailyAiRequestLimit = (p[Keys.aiLimit] ?: 60).coerceIn(0, 1_000),
            spoilerFree = p[Keys.spoilerFree] ?: true,
            teachingLevel = p[Keys.teachingLevel] ?: "BASIC",
            summaryLength = p[Keys.summaryLength] ?: "MEDIUM",
            lastSyncAt = p[Keys.lastSync] ?: 0,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val s = transform(
                AppSettings(
                    p[Keys.onboarding] ?: false,
                    p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
                    p[Keys.language] ?: "system",
                    p[Keys.aiMode]?.let { runCatching { AiMode.valueOf(it) }.getOrNull() } ?: AiMode.LOCAL,
                    p[Keys.algorithm]?.let { runCatching { SrsAlgorithm.valueOf(it) }.getOrNull() } ?: SrsAlgorithm.FSRS,
                    p[Keys.retention] ?: 0.9f,
                    p[Keys.newPerDay] ?: 15,
                    p[Keys.aiLimit] ?: 60,
                    p[Keys.spoilerFree] ?: true,
                    p[Keys.teachingLevel] ?: "BASIC",
                    p[Keys.summaryLength] ?: "MEDIUM",
                    p[Keys.lastSync] ?: 0,
                ),
            )
            p[Keys.onboarding] = s.onboardingDone
            p[Keys.theme] = s.theme.name
            p[Keys.language] = s.language
            p[Keys.aiMode] = s.aiMode.name
            p[Keys.algorithm] = s.algorithm.name
            p[Keys.retention] = s.desiredRetention.coerceIn(0.7f, 0.97f)
            p[Keys.newPerDay] = s.newItemsPerDay.coerceIn(0, 200)
            p[Keys.aiLimit] = s.dailyAiRequestLimit.coerceIn(0, 1_000)
            p[Keys.spoilerFree] = s.spoilerFree
            p[Keys.teachingLevel] = s.teachingLevel
            p[Keys.summaryLength] = s.summaryLength
            p[Keys.lastSync] = s.lastSyncAt
        }
    }

    suspend fun clear() { store.edit { it.clear() } }
}
