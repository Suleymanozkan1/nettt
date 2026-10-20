package com.bookmind.core.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One request to the on-device model; the engine applies the model's own chat template. */
data class OnDevicePrompt(val system: String, val user: String, val maxOutputTokens: Int, val temperature: Double)

/**
 * A free open-weights model running on the phone itself (LiteRT-LM). The engine is supplied by the app as
 * [generate], which keeps this class testable on the JVM and lets the desktop build of the same engine run the
 * real model in tests. Nothing leaves the device.
 */
class OnDeviceProvider(
    private val modelName: String,
    /** Context window the engine was created with (prompt + answer, in tokens). */
    private val contextTokens: Int,
    private val generate: suspend (OnDevicePrompt) -> String,
) : AiProvider {
    // The model is part of the id so cached results of one model are not served for another.
    override val id: String = "on-device:$modelName"

    /** Prompt budget (at most 60 % of the context); used to size book chunks. */
    override val inputBudgetChars: Int = budgetChars(contextTokens)

    override fun inputBudgetFor(request: AiRequest): Int = budgetChars(contextTokens, request.maxOutputTokens)

    /**
     * The answer gets whatever the prompt leaves (at least the 40 % reserved by [budgetChars]), up to what the
     * task asked for, so a long book excerpt can never push the reply out of the context window.
     */
    private fun answerTokens(request: AiRequest, promptChars: Int): Int {
        val promptTokens = (promptChars / CHARS_PER_TOKEN).toInt() + TEMPLATE_TOKENS
        return request.maxOutputTokens.coerceAtMost((contextTokens - promptTokens).coerceAtLeast(contextTokens * 2 / 5))
    }

    // One inference at a time: two would double the memory needed on the phone.
    private val lock = Mutex()

    override suspend fun complete(request: AiRequest): AiResponse {
        val system = request.system + "\nReply with a single JSON object only."
        val prompt = OnDevicePrompt(system, request.user, answerTokens(request, system.length + request.user.length), request.temperature)
        val raw = lock.withLock {
            try {
                generate(prompt)
            } catch (e: AiException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                throw AiException.Server(507, "not enough memory for the on-device model")
            } catch (e: Exception) {
                throw AiException.Server(500, e.message?.take(200))
            }
        }
        val text = clean(raw)
        if (text.isEmpty()) throw AiException.InvalidOutput("empty on-device reply")
        return AiResponse(text, modelName, inputTokens = (prompt.system.length + prompt.user.length) / 4, outputTokens = text.length / 4, costMicros = 0)
    }

    companion object {
        /** Removes reasoning blocks some small models emit before the answer ("<think>…</think>"). */
        fun clean(raw: String): String = raw.replace(Regex("(?s)<think>.*?</think>"), "").substringAfter("</think>").trim()

        /** Turkish needs more tokens per character than English, so the estimate is conservative. */
        const val CHARS_PER_TOKEN = 2.6
        const val TEMPLATE_TOKENS = 120

        /** Share of the context the prompt (system text + book excerpt) may use; the rest is kept for the answer. */
        const val PROMPT_SHARE = 0.6

        /**
         * Prompt characters for a context of [contextTokens]: at most 60 % of the context, and less when the task
         * needs a longer answer ([answerTokens]) than the remaining 40 % holds.
         */
        fun budgetChars(contextTokens: Int, answerTokens: Int = 0): Int {
            val share = (contextTokens * PROMPT_SHARE).toInt()
            val leftForPrompt = contextTokens - answerTokens - TEMPLATE_TOKENS
            return ((minOf(share, leftForPrompt).coerceAtLeast(contextTokens / 4) - TEMPLATE_TOKENS) * CHARS_PER_TOKEN).toInt().coerceAtLeast(400)
        }
    }
}
