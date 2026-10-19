package com.bookmind.app.ai

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import com.bookmind.core.ai.OnDevicePrompt
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The downloaded model files and the LiteRT-LM engine that runs them on the phone (free, offline; no text
 * leaves the device). The engine is loaded on first use and released after a few idle minutes so a 1–2 GB model
 * does not stay in memory while the app is in the background.
 */
@Singleton
class OnDeviceModels @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir get() = File(context.filesDir, "models").apply { mkdirs() }
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: Engine? = null
    private var engineModel: String? = null
    private var idleRelease: Job? = null

    private val _installed = MutableStateFlow(scanInstalled())
    /** Ids of the catalog models that are fully downloaded and verified. */
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    fun file(spec: ModelSpec): File = File(dir, spec.fileName)

    /** Partial download kept between attempts so an interrupted download resumes instead of restarting. */
    fun partFile(spec: ModelSpec): File = File(dir, spec.fileName + ".part")

    fun isInstalled(spec: ModelSpec): Boolean = file(spec).let { it.isFile && it.length() == spec.bytes }

    fun refresh() { _installed.value = scanInstalled() }

    private fun scanInstalled() = ModelCatalog.all.filter(::isInstalled).map { it.id }.toSet()

    /** The engine ships for 64-bit ARM phones (and x86_64 emulators); older 32-bit phones use the classic methods. */
    val supported: Boolean get() = android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "x86_64" }

    /** Total RAM in GB as Android reports it ("6 GB" phones report about 5.5). */
    fun deviceRamGb(): Double {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.totalMem / 1_073_741_824.0
    }

    /** Free bytes on the app's internal storage. */
    fun freeBytes(): Long = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    /** Room needed to download [spec]: the file, the engine's first-run cache and a safety margin. */
    fun neededBytes(spec: ModelSpec): Long = spec.bytes - partFile(spec).length().coerceAtMost(spec.bytes) + spec.bytes / 3 + 300_000_000

    suspend fun delete(spec: ModelSpec) = lock.withLock {
        if (engineModel == spec.id) closeEngine()
        file(spec).delete()
        partFile(spec).delete()
        context.cacheDir.listFiles { f -> f.name.startsWith(spec.fileName) }?.forEach { it.delete() }
        refresh()
    }

    /**
     * Runs one prompt in a fresh conversation (no history: every request is self-contained). Output is not
     * grammar-constrained: with Gemma 4 that made generation degenerate, while plain decoding gives clean JSON that
     * the pipeline extracts and validates (one corrective retry on invalid JSON).
     */
    suspend fun generate(spec: ModelSpec, prompt: OnDevicePrompt): String = withContext(Dispatchers.Default) {
        lock.withLock {
            idleRelease?.cancel()
            try {
                val e = engineFor(spec)
                val config = ConversationConfig(
                    systemInstruction = Contents.of(prompt.system),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = prompt.temperature),
                    maxOutputToken = prompt.maxOutputTokens,
                    // Answers are short and structured; a reasoning preamble would only cost time and battery.
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                )
                e.createConversation(config).use { c ->
                    c.sendMessage(prompt.user)
                        .contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                }
            } finally {
                idleRelease = scope.launch {
                    delay(IDLE_RELEASE_MS)
                    lock.withLock { closeEngine() }
                }
            }
        }
    }

    private fun engineFor(spec: ModelSpec): Engine {
        engine?.takeIf { engineModel == spec.id }?.let { return it }
        closeEngine()
        val f = file(spec)
        check(isInstalled(spec)) { "model file missing" }
        // CPU works on every arm64 phone; the engine writes a one-time weight cache to the (clearable) cache dir.
        val e = try {
            Engine(EngineConfig(modelPath = f.absolutePath, backend = Backend.CPU(), maxNumTokens = spec.contextTokens, cacheDir = context.cacheDir.path))
                .also { it.initialize() }
        } catch (e: LinkageError) {
            // Native library missing for this CPU: report it as an ordinary failure so the classic method takes over.
            throw IllegalStateException("on-device engine unavailable on this phone", e)
        }
        engine = e
        engineModel = spec.id
        return e
    }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        engineModel = null
    }

    companion object {
        const val IDLE_RELEASE_MS = 3 * 60_000L
    }
}
