package com.bookmind.app.notifications

import com.bookmind.app.data.settings.AppLanguage
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bookmind.app.MainActivity
import com.bookmind.app.R

object Notifier {
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_PROCESSING = "processing"
    const val ID_REMINDER = 1001
    const val ID_PROCESSING = 1002
    const val ID_SUMMARY = 1003
    const val EXTRA_DESTINATION = "destination"

    fun ensureChannels(appContext: Context) {
        val nm = appContext.getSystemService(NotificationManager::class.java)
        val context = AppLanguage.localized(appContext)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_REMINDERS, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROCESSING, context.getString(R.string.channel_processing), NotificationManager.IMPORTANCE_LOW))
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Posts the daily reminder. Returns false if the user has not granted notification permission. */
    fun showReminder(context: Context, title: String, text: String, destination: String): Boolean {
        if (!canPost(context)) return false
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_DESTINATION, destination)
        }
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            // Private by default: the lock screen shows only the app name, never note or book content.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_REMINDER, n)
        } catch (_: SecurityException) {
            return false
        }
        return true
    }
}
