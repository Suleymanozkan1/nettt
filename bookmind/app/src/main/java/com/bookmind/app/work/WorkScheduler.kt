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

    /** Downloads an on-device model; needs a connection and storage, survives app restarts and resumes. */
    fun downloadModel(modelId: String, tag: String) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL to modelId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
            .addTag(TAG_MODEL)
            .addTag(tag)
            .build()
        workManager.enqueueUniqueWork(MODEL_DOWNLOAD, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelModelDownload() = workManager.cancelUniqueWork(MODEL_DOWNLOAD)

    fun modelDownloadInfo() = workManager.getWorkInfosForUniqueWorkFlow(MODEL_DOWNLOAD)

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
        const val TAG_MODEL = "model"
        const val MODEL_DOWNLOAD = "model-download"
        const val REMINDER = "reminder"
        const val SYNC_NOW = "sync-now"
        const val SYNC_PERIODIC = "sync-periodic"
        fun processingName(bookId: String) = "process-$bookId"
    }
}
