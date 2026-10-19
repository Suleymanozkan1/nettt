package com.bookmind.core.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Calls the BookMind backend (Supabase Edge Function `ai`), which holds the provider API keys, enforces
 * per-user quotas and forwards the request to the configured LLM. The client only sends the user's session
 * token; no provider key ever ships in the APK.
 */
class RemoteAiProvider(
    private val endpoint: String,
    private val anonKey: String,
    private val accessToken: suspend () -> String?,
    client: OkHttpClient = OkHttpClient(),
) : AiProvider {
    override val id: String = "remote"

    private val http = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    @Serializable
    private data class Body(
        val task: String,
        val promptVersion: String,
        val system: String,
        val user: String,
        val maxOutputTokens: Int,
        val temperature: Double,
    )

    @Serializable
    private data class Usage(val inputTokens: Int? = null, val outputTokens: Int? = null, val costMicros: Long? = null)

    @Serializable
    private data class Reply(val text: String = "", val model: String? = null, val usage: Usage? = null)

    @Serializable
    private data class ErrorReply(val error: String? = null, val resetAt: Long? = null)

    override suspend fun complete(request: AiRequest): AiResponse {
        val token = accessToken() ?: throw AiException.Unauthorized()
        val body = AiJson.json.encodeToString(
            Body.serializer(),
            Body(request.task.name, request.promptVersion, request.system, request.user, request.maxOutputTokens, request.temperature),
        ).toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $token")
            .header("apikey", anonKey)
            .post(body)
            .build()
        val response = try {
            http.newCall(httpRequest).await()
        } catch (e: InterruptedIOException) {
            throw AiException.Timeout()
        } catch (e: IOException) {
            throw AiException.Network(e)
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            when {
                r.code == 401 || r.code == 403 -> throw AiException.Unauthorized()
                r.code == 429 -> throw AiException.QuotaExceeded(runCatching { AiJson.json.decodeFromString(ErrorReply.serializer(), text).resetAt }.getOrNull())
                !r.isSuccessful -> throw AiException.Server(r.code, runCatching { AiJson.json.decodeFromString(ErrorReply.serializer(), text).error }.getOrNull())
            }
            val reply = try {
                AiJson.json.decodeFromString(Reply.serializer(), text)
            } catch (e: Exception) {
                throw AiException.InvalidOutput("unreadable server response")
            }
            return AiResponse(reply.text, reply.model, reply.usage?.inputTokens, reply.usage?.outputTokens, reply.usage?.costMicros)
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { cont.resume(response) }
        })
    }
}
