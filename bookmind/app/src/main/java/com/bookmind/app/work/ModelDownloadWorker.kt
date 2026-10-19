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
import com.bookmind.app.ai.ModelCatalog
import com.bookmind.app.ai.ModelDownloader
import com.bookmind.app.ai.ModelSpec
import com.bookmind.app.ai.OnDeviceModels
import com.bookmind.app.data.settings.AiMode
import com.bookmind.app.data.settings.AppLanguage
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.notifications.Notifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads a catalog model from its pinned Hugging Face revision (free, no account), resuming a partial file
 * with an HTTP range request, then verifies size and SHA-256 before the file is used. Only the model is
 * downloaded; nothing about the user or their books is sent.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val models: OnDeviceModels,
    private val settings: SettingsRepository,
    http: OkHttpClient,
) : CoroutineWorker(context, params) {

    private val downloader = ModelDownloader(http.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.MILLISECONDS).build())
    private var lastNotified = 0L

    override suspend fun doWork(): Result {
        val spec = ModelCatalog.byId(inputData.getString(KEY_MODEL).orEmpty()) ?: return Result.failure(error(Failure.UNKNOWN))
        if (models.isInstalled(spec)) return finish(spec)
        if (models.freeBytes() < models.neededBytes(spec)) return Result.failure(error(Failure.NO_SPACE))
        runCatching { setForeground(foreground(spec, 0)) }
        return try {
            downloader.download(spec.url, spec.bytes, models.partFile(spec)) { report(spec, it) }
            if (!verify(spec)) {
                models.partFile(spec).delete()
                return Result.failure(error(Failure.CORRUPT))
            }
            check(models.partFile(spec).renameTo(models.file(spec)))
            finish(spec)
        } catch (e: IOException) {
            // Network drops are normal on a 2–4 GB download: keep the partial file and let WorkManager retry.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure(error(Failure.NETWORK))
        }
    }

    private suspend fun finish(spec: ModelSpec): Result {
        models.refresh()
        settings.update { it.copy(onDeviceModel = spec.id, aiMode = if (it.aiMode == AiMode.MOCK) it.aiMode else AiMode.ON_DEVICE) }
        return Result.success()
    }

    private suspend fun report(spec: ModelSpec, have: Long) {
        val now = System.currentTimeMillis()
        if (now - lastNotified < 750 && have < spec.bytes) return
        lastNotified = now
        setProgress(workDataOf(KEY_DONE to have, KEY_TOTAL to spec.bytes))
        runCatching { setForeground(foreground(spec, have)) }
    }

    private suspend fun verify(spec: ModelSpec): Boolean {
        setProgress(workDataOf(KEY_DONE to spec.bytes, KEY_TOTAL to spec.bytes, KEY_VERIFYING to true))
        return downloader.verify(models.partFile(spec), spec.bytes, spec.sha256)
    }

    private fun foreground(spec: ModelSpec, done: Long): ForegroundInfo {
        Notifier.ensureChannels(applicationContext)
        val ctx = AppLanguage.localized(applicationContext)
        val percent = (done * 100 / spec.bytes).toInt()
        val notification = NotificationCompat.Builder(applicationContext, Notifier.CHANNEL_PROCESSING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ctx.getString(R.string.ai_download_notification, spec.displayName))
            .setContentText("$percent%")
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, percent, done == 0L)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifier.ID_MODEL_DOWNLOAD, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(Notifier.ID_MODEL_DOWNLOAD, notification)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foreground(ModelCatalog.byId(inputData.getString(KEY_MODEL).orEmpty()) ?: ModelCatalog.GEMMA_4_E2B, 0)

    private fun error(f: Failure) = workDataOf(KEY_ERROR to f.name)

    enum class Failure { NO_SPACE, NETWORK, CORRUPT, UNKNOWN }

    companion object {
        const val KEY_MODEL = "model"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_VERIFYING = "verifying"
        const val KEY_ERROR = "error"
        const val MAX_ATTEMPTS = 8
    }
}
