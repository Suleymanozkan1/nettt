package com.bookmind.core.ai.gemini

import com.bookmind.core.ai.AiException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/** A text model the summary pipeline can call; [GeminiClient] in the app, a fake in tests. */
interface TextModel {
    val model: String
    suspend fun generate(system: String, user: String, maxOutputTokens: Int, json: Boolean): ModelReply
}

data class ModelReply(
    val text: String,
    /** STOP, MAX_TOKENS, … as reported by the API. */
    val finishReason: String?,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
) {
    val truncated: Boolean get() = finishReason == "MAX_TOKENS"
}

/** Connection settings for the Gemini API. The key comes from the build (local.properties / environment), never from source. */
data class GeminiSettings(
    val apiKey: String,
    val model: String,
    /** `low`, `medium` or `high`; null leaves the model's default. Thinking tokens count against maxOutputTokens. */
    val thinkingLevel: String? = "low",
    val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    /** Retries after the first attempt for 429, 408 and 5xx responses and network errors. */
    val maxRetries: Int = 6,
    val initialBackoffMs: Long = 2_000,
    val maxBackoffMs: Long = 120_000,
    /**
     * Sent with every request. The app adds `X-Android-Package` and `X-Android-Cert` so a key restricted to the
     * app in the Google Cloud console works (and a key copied out of the APK does not work elsewhere).
     */
    val extraHeaders: Map<String, String> = emptyMap(),
)

/**
 * Minimal Gemini `generateContent` client (REST, API key in the `x-goog-api-key` header). Stateless: nothing is
 * stored server side between requests. Transient failures (429 rate limits, 408, 5xx, network) are retried with
 * exponential backoff and jitter, honouring the server's `RetryInfo.retryDelay` or `Retry-After` when present;
 * an exhausted daily quota is reported at once as [AiException.QuotaExceeded] with the reset time (midnight
 * Pacific), since retrying before then cannot succeed.
 */
class GeminiClient(
    private val settings: GeminiSettings,
    client: OkHttpClient = OkHttpClient(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) : TextModel {
    override val model: String get() = settings.model

    private val http = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // A long summary with thinking can take minutes; the call timeout is the hard stop.
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(600, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun generate(system: String, user: String, maxOutputTokens: Int, json: Boolean): ModelReply {
        if (settings.apiKey.isBlank()) throw AiException.NotConfigured()
        val body = requestBody(system, user, maxOutputTokens, json).toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("${settings.baseUrl.trimEnd('/')}/models/${settings.model}:generateContent")
            .header("x-goog-api-key", settings.apiKey)
            .apply { settings.extraHeaders.forEach { (k, v) -> header(k, v) } }
            .post(body)
            .build()

        var attempt = 0
        while (true) {
            val failure: Failure = try {
                http.newCall(request).await().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (r.isSuccessful) return parse(text)
                    classify(r.code, text, r.header("Retry-After"))
                }
            } catch (e: IOException) {
                Failure.Transient(AiException.Network(e), null)
            }
            when (failure) {
                is Failure.Fatal -> throw failure.error
                is Failure.Transient -> {
                    if (attempt >= settings.maxRetries) throw failure.error
                    sleep(failure.retryAfterMs ?: backoff(attempt))
                    attempt++
                }
            }
        }
    }

    internal fun requestBody(system: String, user: String, maxOutputTokens: Int, jsonOutput: Boolean): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
        putJsonArray("contents") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") { add(buildJsonObject { put("text", user) }) }
            })
        }
        // No temperature/topP/topK: Gemini 3.6+ ignores custom sampling parameters and recommends leaving them out.
        putJsonObject("generationConfig") {
            put("maxOutputTokens", maxOutputTokens.coerceIn(256, MAX_OUTPUT_TOKENS))
            if (jsonOutput) put("responseMimeType", "application/json")
            settings.thinkingLevel?.takeIf { it.isNotBlank() }?.let { level ->
                putJsonObject("thinkingConfig") { put("thinkingLevel", level) }
            }
        }
    }

    internal fun parse(body: String): ModelReply {
        val root = try { json.parseToJsonElement(body).jsonObject } catch (e: Exception) { throw AiException.InvalidOutput("unreadable Gemini response") }
        val blocked = (root["promptFeedback"] as? JsonObject)?.get("blockReason")?.let { (it as? JsonPrimitive)?.contentOrNull }
        if (blocked != null) throw AiException.InvalidOutput("request blocked: $blocked")
        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: throw AiException.InvalidOutput("no candidates")
        val finish = (candidate["finishReason"] as? JsonPrimitive)?.contentOrNull
        val parts = ((candidate["content"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        // Thought summaries (if any) are flagged with "thought": true and are not part of the answer.
        val text = parts.mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            if ((o["thought"] as? JsonPrimitive)?.booleanOrNull == true) null else (o["text"] as? JsonPrimitive)?.contentOrNull
        }.joinToString("")
        if (text.isBlank()) {
            throw AiException.InvalidOutput(if (finish != null && finish != "STOP") "empty answer ($finish)" else "empty answer")
        }
        if (finish in BLOCKING_FINISH) throw AiException.InvalidOutput("answer stopped: $finish")
        val usage = root["usageMetadata"] as? JsonObject
        fun count(name: String) = (usage?.get(name) as? JsonPrimitive)?.intOrNull
        val output = listOfNotNull(count("candidatesTokenCount"), count("thoughtsTokenCount")).takeIf { it.isNotEmpty() }?.sum()
        return ModelReply(text, finish, count("promptTokenCount"), output)
    }

    private sealed interface Failure {
        data class Transient(val error: AiException, val retryAfterMs: Long?) : Failure
        data class Fatal(val error: AiException) : Failure
    }

    private fun classify(code: Int, body: String, retryAfterHeader: String?): Failure {
        val error = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
        val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull
        val details = (error?.get("details") as? JsonArray).orEmpty()
        val retryDelay = details.firstNotNullOfOrNull { d -> ((d as? JsonObject)?.get("retryDelay") as? JsonPrimitive)?.contentOrNull?.let(::parseDuration) }
            ?: retryAfterHeader?.trim()?.toLongOrNull()?.times(1_000)
        return when {
            code == 429 && isDailyQuota(body) -> Failure.Fatal(AiException.QuotaExceeded(nextPacificMidnight()))
            code == 429 -> Failure.Transient(AiException.QuotaExceeded(clock() + (retryDelay ?: settings.maxBackoffMs)), retryDelay?.coerceAtMost(settings.maxBackoffMs))
            code == 408 || code >= 500 -> Failure.Transient(AiException.Server(code, message), retryDelay?.coerceAtMost(settings.maxBackoffMs))
            code == 401 || code == 403 -> Failure.Fatal(AiException.Unauthorized())
            else -> Failure.Fatal(AiException.Server(code, message))
        }
    }

    /** Free-tier daily limits name the quota "…PerDay…"; the newer error format uses the code `quota_exceeded`. */
    private fun isDailyQuota(body: String) = body.contains("PerDay", ignoreCase = true) || body.contains("\"quota_exceeded\"")

    private fun parseDuration(s: String): Long? =
        s.trim().removeSuffix("s").toDoubleOrNull()?.let { (it * 1_000).toLong() }

    internal fun backoff(attempt: Int): Long {
        val base = (settings.initialBackoffMs shl attempt.coerceAtMost(16)).coerceAtMost(settings.maxBackoffMs)
        return base + (base * 0.25 * random.nextDouble()).toLong()
    }

    /** Requests-per-day quotas reset at midnight Pacific time. */
    private fun nextPacificMidnight(): Long {
        val zone = ZoneId.of("America/Los_Angeles")
        val today = java.time.Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
        return LocalDate.from(today).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { cont.resume(response) }
        })
    }

    companion object {
        const val MAX_OUTPUT_TOKENS = 65_536
        private val BLOCKING_FINISH = setOf("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "LANGUAGE")
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
