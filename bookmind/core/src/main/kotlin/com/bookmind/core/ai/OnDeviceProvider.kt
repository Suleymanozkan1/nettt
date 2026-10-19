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

    /** Typical budget (answers up to ~1k tokens); used to size book chunks. */
    override val inputBudgetChars: Int = budgetChars(contextTokens)

    override fun inputBudgetFor(request: AiRequest): Int = budgetChars(contextTokens, answerTokens(request))

    /** Never let the answer take more than half the context, so the book excerpt keeps room. */
    private fun answerTokens(request: AiRequest) = request.maxOutputTokens.coerceAtMost(contextTokens / 2)

    // One inference at a time: two would double the memory needed on the phone.
    private val lock = Mutex()

    override suspend fun complete(request: AiRequest): AiResponse {
        val prompt = OnDevicePrompt(request.system + "\nReply with a single JSON object only.", request.user, answerTokens(request), request.temperature)
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

        /**
         * Prompt characters for a context of [contextTokens], keeping room for an answer of [answerTokens] and the
         * chat template. Turkish needs more tokens per character than English, so the estimate is conservative
         * (≈2.6 characters per token).
         */
        fun budgetChars(contextTokens: Int, answerTokens: Int = 1_000): Int =
            ((contextTokens - answerTokens - 200).coerceAtLeast(600) * 2.6).toInt()
    }
}
