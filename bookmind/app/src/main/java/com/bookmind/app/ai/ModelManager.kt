package com.bookmind.app.ai

import androidx.work.WorkInfo
import com.bookmind.app.data.settings.AiMode
import com.bookmind.app.data.settings.SettingsRepository
import com.bookmind.app.work.ModelDownloadWorker
import com.bookmind.app.work.WorkScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/** What the UI shows about the on-device AI: which model, whether it is there, and download progress. */
data class AiState(
    /** False on phones the engine cannot run on (32-bit CPUs). */
    val supported: Boolean,
    val ramGb: Double,
    val enabled: Boolean,
    /** The model the user picked, or the one recommended for this phone. */
    val selected: ModelSpec?,
    val recommended: ModelSpec?,
    val installed: Set<String>,
    val download: Download?,
) {
    sealed interface Download {
        val modelId: String
        data class Running(override val modelId: String, val done: Long, val total: Long, val verifying: Boolean) : Download {
            val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
        }
        data class Waiting(override val modelId: String) : Download
        data class Failed(override val modelId: String, val reason: ModelDownloadWorker.Failure) : Download
    }

    val ready: Boolean get() = selected != null && selected.id in installed
    /** AI is on and the model is on the phone: summaries, quizzes and answers come from the model. */
    val active: Boolean get() = enabled && ready
}

@Singleton
class ModelManager @Inject constructor(
    private val models: OnDeviceModels,
    private val scheduler: WorkScheduler,
    private val settings: SettingsRepository,
) {
    val ramGb: Double by lazy { runCatching { models.deviceRamGb() }.getOrDefault(0.0) }

    val state: Flow<AiState> = combine(settings.settings, models.installed, scheduler.modelDownloadInfo()) { s, installed, infos ->
        val recommended = if (models.supported) ModelCatalog.recommended(ramGb) else null
        val selected = if (models.supported) ModelCatalog.byId(s.onDeviceModel) ?: recommended else null
        AiState(models.supported, ramGb, s.aiMode == AiMode.ON_DEVICE, selected, recommended, installed, download(infos.lastOrNull(), s.onDeviceModel.ifBlank { selected?.id.orEmpty() }))
    }.onEach { if (it.download == null) models.refresh() }

    private fun download(info: WorkInfo?, fallbackId: String): AiState.Download? {
        info ?: return null
        val id = info.tags.firstOrNull { it.startsWith(TAG_PREFIX) }?.removePrefix(TAG_PREFIX) ?: fallbackId
        return when (info.state) {
            WorkInfo.State.RUNNING -> AiState.Download.Running(
                id,
                info.progress.getLong(ModelDownloadWorker.KEY_DONE, 0),
                info.progress.getLong(ModelDownloadWorker.KEY_TOTAL, ModelCatalog.byId(id)?.bytes ?: 0),
                info.progress.getBoolean(ModelDownloadWorker.KEY_VERIFYING, false),
            )
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> AiState.Download.Waiting(id)
            WorkInfo.State.FAILED -> AiState.Download.Failed(
                id,
                runCatching { ModelDownloadWorker.Failure.valueOf(info.outputData.getString(ModelDownloadWorker.KEY_ERROR).orEmpty()) }
                    .getOrDefault(ModelDownloadWorker.Failure.UNKNOWN),
            )
            else -> null
        }
    }

    suspend fun download(spec: ModelSpec) {
        settings.update { it.copy(onDeviceModel = spec.id) }
        scheduler.downloadModel(spec.id, "$TAG_PREFIX${spec.id}")
    }

    fun cancelDownload() = scheduler.cancelModelDownload()

    suspend fun delete(spec: ModelSpec) = models.delete(spec)

    suspend fun select(spec: ModelSpec) = settings.update { it.copy(onDeviceModel = spec.id) }

    suspend fun setEnabled(enabled: Boolean) = settings.update {
        it.copy(aiMode = if (enabled) AiMode.ON_DEVICE else AiMode.LOCAL)
    }

    fun hasRoomFor(spec: ModelSpec): Boolean = models.freeBytes() >= models.neededBytes(spec)

    companion object {
        const val TAG_PREFIX = "model:"
    }
}
