package com.bookmind.core.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A free model running on the phone itself (an open-weights model file the user imported). The actual
 * inference engine is supplied by the app as [generate] (MediaPipe LLM Inference on Android), which keeps this
 * class testable on the JVM. Prompts are formatted with the chat template the model was trained on.
 */
class OnDeviceProvider(
    private val modelName: String,
    private val template: ChatTemplate,
    /** Small phones run 2–4k token contexts; prompts are cut to this many characters. */
    override val inputBudgetChars: Int = 6_000,
    private val generate: suspend (prompt: String) -> String,
) : AiProvider {
    override val id: String = "on-device"

    // One inference at a time: running two would double the memory needed on the phone.
    private val lock = Mutex()

    override suspend fun complete(request: AiRequest): AiResponse {
        val prompt = template.format(request.system + "\nReply with a single JSON object only.", request.user)
        val raw = lock.withLock {
            try {
                generate(prompt)
            } catch (e: AiException) {
                throw e
            } catch (e: OutOfMemoryError) {
                throw AiException.Server(507, "not enough memory for the on-device model")
            } catch (e: Exception) {
                throw AiException.Server(500, e.message?.take(200))
            }
        }
        val text = template.strip(raw).trim()
        if (text.isEmpty()) throw AiException.InvalidOutput("empty on-device reply")
        return AiResponse(text, modelName, inputTokens = prompt.length / 4, outputTokens = text.length / 4, costMicros = 0)
    }
}

/** Prompt formats of common open-weights instruction models. */
enum class ChatTemplate(private val stopMarkers: List<String>) {
    /** Gemma 2/3 (Google). Gemma has no system role; the system text goes first in the user turn. */
    GEMMA(listOf("<end_of_turn>")),

    /** ChatML: Qwen 2/2.5/3, SmolLM, many fine-tunes. */
    CHATML(listOf("<|im_end|>")),

    /** Llama 3.x. */
    LLAMA3(listOf("<|eot_id|>")),

    /** Phi-3/3.5/4-mini. */
    PHI(listOf("<|end|>"));

    fun format(system: String, user: String): String = when (this) {
        GEMMA -> "<start_of_turn>user\n$system\n\n$user<end_of_turn>\n<start_of_turn>model\n"
        CHATML -> "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
        LLAMA3 -> "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n$system<|eot_id|>" +
            "<|start_header_id|>user<|end_header_id|>\n\n$user<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
        PHI -> "<|system|>\n$system<|end|>\n<|user|>\n$user<|end|>\n<|assistant|>\n"
    }

    /** Removes anything after the end-of-turn marker if the engine returned it. */
    fun strip(output: String): String = stopMarkers.fold(output) { acc, m -> acc.substringBefore(m) }

    companion object {
        /** Guesses the template from a model file name ("gemma3-1b-it-int4.task" → GEMMA). */
        fun forModelName(name: String): ChatTemplate {
            val n = name.lowercase()
            return when {
                "gemma" in n -> GEMMA
                "llama" in n -> LLAMA3
                "phi" in n -> PHI
                else -> CHATML
            }
        }
    }
}
