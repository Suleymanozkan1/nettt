package com.bookmind.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bookmind.app.R
import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.app.data.summary.BookSummaryRepository
import com.bookmind.app.notifications.Notifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Summarises one book with Gemini in the background. Each finished request is saved, so when Android stops the
 * work (or the app is closed) the next run continues where this one stopped. Rate limits are waited out: a short
 * one by WorkManager's backoff, a used-up daily quota by scheduling the next run for when it resets.
 */
@HiltWorker
class BookSummaryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val summaries: BookSummaryRepository,
    private val scheduler: WorkScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        runCatching { setForeground(foreground(0, 0)) }
        val outcome = summaries.run(bookId) { s ->
            setProgress(workDataOf(KEY_DONE to s.done, KEY_TOTAL to s.total))
            runCatching { setForeground(foreground(s.done, s.total)) }
        }
        return when (outcome) {
            BookSummaryRepository.Outcome.Done, BookSummaryRepository.Outcome.Skipped -> Result.success()
            is BookSummaryRepository.Outcome.RetryLater -> {
                // Wait for the limit to reset instead of burning retries; at least a minute, at most a day.
                val delay = (outcome.at - System.currentTimeMillis()).coerceIn(60_000L, 24 * 3_600_000L)
                scheduler.summarizeBook(bookId, delayMs = delay, afterCurrent = true)
                Result.success()
            }
            is BookSummaryRepository.Outcome.Failed ->
                if (outcome.retryable && runAttemptCount < MAX_ATTEMPTS) { summaries.markQueued(bookId); Result.retry() } else Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(0, 0)

    private fun foreground(done: Int, total: Int): ForegroundInfo {
        Notifier.ensureChannels(applicationContext)
        val res = AppLanguage.localized(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, Notifier.CHANNEL_PROCESSING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(res.getString(R.string.summary_notification_title))
            .apply { if (total > 0) setContentText(res.getString(R.string.summary_progress, done, total)) }
            .setOngoing(true)
            .setSilent(true)
            .setProgress(total, done, total == 0)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifier.ID_SUMMARY, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(Notifier.ID_SUMMARY, notification)
    }

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val MAX_ATTEMPTS = 5
    }
}
