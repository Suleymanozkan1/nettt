package com.bookmind.app.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** All background work goes through WorkManager so it survives process death and respects Doze. */
@Singleton
class WorkScheduler @Inject constructor(private val workManager: WorkManager) {

    fun processBook(bookId: String, ocr: Boolean = false, requireNetwork: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<BookProcessingWorker>()
            .setInputData(workDataOf(BookProcessingWorker.KEY_BOOK_ID to bookId, BookProcessingWorker.KEY_OCR to ocr))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (requireNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED).setRequiresStorageNotLow(true).build())
            .addTag(TAG_PROCESSING)
            .addTag("book:$bookId")
            .build()
        workManager.enqueueUniqueWork(processingName(bookId), ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Summarises a book with Gemini once a connection is available. [delayMs] postpones it (rate limit or daily quota);
     * [replace] restarts a pending run, otherwise a run that is already queued is kept. [afterCurrent] is for the
     * worker itself: the next run is chained after the running one instead of cancelling it.
     */
    fun summarizeBook(bookId: String, delayMs: Long = 0, replace: Boolean = false, afterCurrent: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<BookSummaryWorker>()
            .setInputData(workDataOf(BookSummaryWorker.KEY_BOOK_ID to bookId))
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG_SUMMARY)
            .addTag("book:$bookId")
            .build()
        val policy = when {
            afterCurrent -> ExistingWorkPolicy.APPEND_OR_REPLACE
            replace -> ExistingWorkPolicy.REPLACE
            else -> ExistingWorkPolicy.KEEP
        }
        workManager.enqueueUniqueWork(summaryName(bookId), policy, request)
    }

    fun cancelSummary(bookId: String) = workManager.cancelUniqueWork(summaryName(bookId))

    fun cancelBook(bookId: String) = workManager.cancelUniqueWork(processingName(bookId))

    fun processingInfo(bookId: String) = workManager.getWorkInfosForUniqueWorkFlow(processingName(bookId))

    /** Schedules the next reminder check after [delayMs]; the worker reschedules itself after running. */
    fun scheduleReminder(delayMs: Long) {
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .addTag(TAG_REMINDER)
            .build()
        workManager.enqueueUniqueWork(REMINDER, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelReminders() = workManager.cancelUniqueWork(REMINDER)

    fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(SYNC_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun syncNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(SYNC_NOW, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelSync() {
        workManager.cancelUniqueWork(SYNC_NOW)
        workManager.cancelUniqueWork(SYNC_PERIODIC)
    }

    companion object {
        const val TAG_PROCESSING = "processing"
        const val TAG_REMINDER = "reminder"
        const val TAG_SUMMARY = "summary"
        const val REMINDER = "reminder"
        const val SYNC_NOW = "sync-now"
        const val SYNC_PERIODIC = "sync-periodic"
        fun processingName(bookId: String) = "process-$bookId"
        fun summaryName(bookId: String) = "summary-$bookId"
    }
}
