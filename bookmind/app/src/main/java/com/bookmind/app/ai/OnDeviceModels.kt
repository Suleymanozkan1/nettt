package com.bookmind.app.ai

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Open-weights model files the user imported, and the MediaPipe LLM Inference engine that runs them on the
 * phone (free, offline). The app never downloads a model itself: the user picks a file they obtained under
 * the model's own licence (e.g. Gemma 3 1B or Qwen2.5 1.5B in .task format).
 */
@Singleton
class OnDeviceModels @Inject constructor(@ApplicationContext private val context: Context) {
    class ImportException(val reason: Reason) : IOException(reason.name) {
        enum class Reason { UNSUPPORTED, TOO_LARGE, UNREADABLE }
    }

    private val dir get() = File(context.filesDir, "models").apply { mkdirs() }
    private val lock = Mutex()
    private var engine: LlmInference? = null
    private var engineKey: String? = null

    fun file(name: String): File? = name.takeIf { it.isNotBlank() }?.let { File(dir, it) }?.takeIf { it.isFile }

    fun installed(): List<File> = dir.listFiles()?.filter { it.isFile && it.extension in EXTENSIONS }.orEmpty().sortedBy { it.name }

    /** Total RAM in GB; 1–2B models need roughly 4 GB free, so phones under 6 GB get a warning. */
    fun deviceRamGb(): Double {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.totalMem / 1_073_741_824.0
    }

    /** Copies the picked file into app storage (streamed; models are hundreds of MB). Returns the stored name. */
    suspend fun import(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "model.task"
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        if (safe.substringAfterLast('.', "").lowercase() !in EXTENSIONS) throw ImportException(ImportException.Reason.UNSUPPORTED)
        val target = File(dir, safe)
        val tmp = File(dir, "$safe.part")
        try {
            resolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw ImportException(ImportException.Reason.TOO_LARGE)
                        out.write(buf, 0, n)
                    }
                }
            } ?: throw ImportException(ImportException.Reason.UNREADABLE)
            if (!tmp.renameTo(target)) throw ImportException(ImportException.Reason.UNREADABLE)
        } finally {
            tmp.delete()
        }
        safe
    }

    suspend fun delete(name: String) = lock.withLock {
        if (engineKey?.startsWith(name + "|") == true) { engine?.close(); engine = null; engineKey = null }
        file(name)?.delete()
    }

    /**
     * Runs one prompt. The engine is created on first use and kept while the same model/context is selected;
     * loading a model takes seconds and several hundred MB, so it is not recreated per request.
     */
    suspend fun generate(name: String, maxTokens: Int, prompt: String): String = withContext(Dispatchers.Default) {
        lock.withLock {
            val f = file(name) ?: throw IllegalStateException("model file missing")
            val key = "$name|$maxTokens"
            if (engineKey != key) {
                engine?.close()
                engine = null
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(f.absolutePath)
                    .setMaxTokens(maxTokens)
                    .build()
                engine = LlmInference.createFromOptions(context, options)
                engineKey = key
            }
            engine!!.generateResponse(prompt)
        }
    }

    companion object {
        val EXTENSIONS = setOf("task", "litertlm", "bin")
        const val MAX_BYTES = 6L * 1024 * 1024 * 1024
    }
}
