package com.bookmind.core.ai.gemini

import com.bookmind.core.ai.AiProvider
import com.bookmind.core.ai.AiRequest
import com.bookmind.core.ai.AiResponse

/**
 * The interactive features (ask the book, tutor, quizzes, study modules) on Gemini. Their prompts ask for JSON,
 * so JSON output mode is always on. The prompts' token limits were written for answers only; Gemini's thinking
 * tokens count against the same limit, so extra room is added for them.
 */
class GeminiProvider(private val client: TextModel) : AiProvider {
    override val id: String get() = "gemini:${client.model}"
    /** Gemini Flash reads 1M tokens; this keeps single requests (and their cost) at a sensible size. */
    override val inputBudgetChars: Int = 400_000
    override val free: Boolean = false

    override suspend fun complete(request: AiRequest): AiResponse {
        val reply = client.generate(request.system, request.user, answerTokens(request.maxOutputTokens), json = true)
        return AiResponse(reply.text, client.model, reply.inputTokens, reply.outputTokens)
    }

    companion object {
        const val THINKING_ROOM_TOKENS = 4_096
        fun answerTokens(requested: Int) = (requested * 2 + THINKING_ROOM_TOKENS).coerceAtMost(GeminiClient.MAX_OUTPUT_TOKENS)
    }
}
