package com.bookmind.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.bookmind.app.R
import com.bookmind.app.notifications.Notifier
import com.bookmind.app.processing.BookProcessor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class BookProcessingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val processor: BookProcessor,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        // Long books can take minutes; running in the foreground keeps the work alive and visible.
        runCatching { setForeground(getForegroundInfo()) }
        processor.process(bookId, inputData.getBoolean(KEY_OCR, false))
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        Notifier.ensureChannels(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, Notifier.CHANNEL_PROCESSING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.processing_notification_title))
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifier.ID_PROCESSING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(Notifier.ID_PROCESSING, notification)
    }

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val KEY_OCR = "ocr"
    }
}
