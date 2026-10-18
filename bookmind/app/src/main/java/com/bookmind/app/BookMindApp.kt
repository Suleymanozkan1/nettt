package com.bookmind.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.bookmind.app.data.db.BookMindDatabase
import com.bookmind.app.data.remote.AuthRepository
import com.bookmind.app.data.repo.UserRepository
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.notifications.Notifier
import com.bookmind.app.notifications.ReminderManager
import com.bookmind.app.work.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class BookMindApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var reminders: ReminderManager
    @Inject lateinit var users: UserRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var db: BookMindDatabase
    @Inject lateinit var scheduler: WorkScheduler
    @Inject lateinit var auth: AuthRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        appScope.launch {
            val lang = settings.settings.first().language
            if (lang != "system") AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
            users.userId()
            users.refreshTimezone()
            reminders.reschedule()
            // Resume books whose processing was interrupted (process death, reboot).
            db.books().unfinished().forEach { scheduler.processBook(it.id) }
            if (auth.account.value != null) scheduler.schedulePeriodicSync()
        }
        // Reminders follow the device time zone; recompute when it changes.
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                appScope.launch { users.refreshTimezone(); reminders.reschedule() }
            }
        }, IntentFilter(Intent.ACTION_TIMEZONE_CHANGED))
    }
}
