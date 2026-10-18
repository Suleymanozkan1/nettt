package com.bookmind.core.ai

/**
 * Provider-agnostic LLM boundary. Implementations: [RemoteAiProvider] (calls our backend, which holds the API
 * keys), [MockAiProvider] (deterministic, no network). The app never embeds provider API keys.
 */
interface AiProvider {
    val id: String
    suspend fun complete(request: AiRequest): AiResponse
}

enum class AiTask { BOOK_ANALYSIS, CHAPTER_ANALYSIS, SUMMARY, QUIZ, TEACH, GRADE, STORY, CONCEPT_LINKS }

data class AiRequest(
    val task: AiTask,
    val promptVersion: String,
    val system: String,
    val user: String,
    val maxOutputTokens: Int = 2_000,
    val temperature: Double = 0.3,
    /** Structured input, used by the mock provider to build a plausible answer without parsing prompts. */
    val payload: Map<String, String> = emptyMap(),
)

data class AiResponse(
    val text: String,
    val model: String?,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    /** Cost in micro-dollars when the backend reports it. */
    val costMicros: Long? = null,
)

sealed class AiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured : AiException("AI sağlayıcısı yapılandırılmadı")
    class Network(cause: Throwable?) : AiException("Ağ hatası", cause)
    class Timeout : AiException("İstek zaman aşımına uğradı")
    class QuotaExceeded(val resetAt: Long?) : AiException("Kullanım kotası doldu")
    class Unauthorized : AiException("Oturum geçersiz")
    class Server(val code: Int, detail: String?) : AiException("Sunucu hatası ($code)${detail?.let { ": $it" }.orEmpty()}")
    class InvalidOutput(detail: String) : AiException("Model yanıtı geçersiz: $detail")

    /** Network-ish errors are worth retrying; quota, auth and bad output are not. */
    val retryable: Boolean get() = this is Network || this is Timeout || (this is Server && code >= 500)
}
