package com.bookmind.app.data.settings

import android.content.Context
import android.content.res.Configuration
import com.bookmind.core.i18n.CoreText
import java.util.Locale

/**
 * The single language the app speaks: UI strings, on-device content, AI output, plan titles and warnings all
 * follow it, so the screen never mixes Turkish and English. Mirrors [AppSettings.language] for code that
 * cannot suspend; [SettingsRepository] keeps it in sync.
 */
object AppLanguage {
    const val DEFAULT = "tr"
    val supported = listOf("tr", "en")

    @Volatile
    var tag: String = DEFAULT
        internal set

    val text: CoreText get() = CoreText.of(tag)

    /** For dates and numbers: follows the app language, not the device locale. */
    val locale: Locale get() = Locale.forLanguageTag(tag)

    fun normalize(value: String?): String = value?.takeIf { it in supported } ?: DEFAULT

    /**
     * A context whose resources use the app language. Activities get this from AppCompat; background work
     * (reminders, processing notifications) runs on the application context and needs it explicitly.
     */
    fun localized(context: Context): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }
}
