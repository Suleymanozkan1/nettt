package com.bookmind.core.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A free, self-hosted model on the user's own computer, reached over the local network through the
 * OpenAI-compatible chat API that Ollama, llama.cpp's `llama-server`, LM Studio and LocalAI all expose
 * (`POST {base}/v1/chat/completions`). No API key, no per-token cost; book text never leaves the user's
 * network because [LocalEndpoint] only accepts private addresses.
 */
class LocalServerProvider(
    baseUrl: String,
    private val model: String,
    /** Input budget for one request; small local models usually run with a 4–8k token context. */
    override val inputBudgetChars: Int = 8_000,
    client: OkHttpClient = OkHttpClient(),
) : AiProvider {
    override val id: String = "local-server"

    private val base: HttpUrl = LocalEndpoint.parse(baseUrl).getOrThrow()

    // CPU inference is slow: generous read timeout, but still bounded so the UI never hangs forever.
    private val http = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .callTimeout(660, TimeUnit.SECONDS)
        .build()

    @Serializable private data class Message(val role: String, val content: String)
    @Serializable private data class ResponseFormat(val type: String)
    @Serializable
    private data class Body(
        val model: String,
        val messages: List<Message>,
        val temperature: Double,
        @SerialName("max_tokens") val maxTokens: Int,
        @SerialName("response_format") val responseFormat: ResponseFormat,
        val stream: Boolean = false,
    )
    @Serializable private data class Choice(val message: Message? = null)
    @Serializable private data class Usage(@SerialName("prompt_tokens") val prompt: Int? = null, @SerialName("completion_tokens") val completion: Int? = null)
    @Serializable private data class Reply(val choices: List<Choice> = emptyList(), val model: String? = null, val usage: Usage? = null)
    @Serializable private data class ModelEntry(val id: String)
    @Serializable private data class Models(val data: List<ModelEntry> = emptyList())

    override suspend fun complete(request: AiRequest): AiResponse {
        val body = AiJson.json.encodeToString(
            Body.serializer(),
            Body(
                model = model,
                messages = listOf(Message("system", request.system), Message("user", request.user)),
                temperature = request.temperature,
                maxTokens = request.maxOutputTokens,
                // JSON mode is supported by all four servers; it keeps small models from wrapping JSON in prose.
                responseFormat = ResponseFormat("json_object"),
            ),
        )
        val httpRequest = Request.Builder()
            .url(base.newBuilder().addPathSegments("v1/chat/completions").build())
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val text = call(httpRequest)
        val reply = try { AiJson.json.decodeFromString(Reply.serializer(), text) } catch (e: Exception) {
            throw AiException.InvalidOutput("unreadable local server response")
        }
        val content = reply.choices.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() }
            ?: throw AiException.InvalidOutput("empty local model reply")
        // Local inference is free: cost is always zero.
        return AiResponse(content, reply.model ?: model, reply.usage?.prompt, reply.usage?.completion, costMicros = 0)
    }

    /** Lists the models the server offers; used by the "test connection" button. */
    suspend fun listModels(): List<String> {
        val text = call(Request.Builder().url(base.newBuilder().addPathSegments("v1/models").build()).get().build())
        return runCatching { AiJson.json.decodeFromString(Models.serializer(), text).data.map { it.id } }.getOrDefault(emptyList())
    }

    private suspend fun call(request: Request): String {
        val response = try {
            http.newCall(request).await()
        } catch (e: InterruptedIOException) {
            throw AiException.Timeout()
        } catch (e: IOException) {
            throw AiException.Network(e)
        }
        return response.use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                throw AiException.Server(r.code, text.take(200).ifBlank { null })
            }
            text
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

/**
 * Guards the "model on my computer" setting: only http(s) URLs whose host is on a private network
 * (loopback, 10/8, 172.16/12, 192.168/16, link-local, IPv6 ULA, `*.local`). A public URL could be a paid
 * service or leak book text, so it is rejected instead of silently used.
 */
object LocalEndpoint {
    enum class Problem { INVALID, NOT_PRIVATE }

    class Rejected(val problem: Problem) : IllegalArgumentException(problem.name)

    fun parse(raw: String): Result<HttpUrl> {
        val trimmed = raw.trim().removeSuffix("/")
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return Result.failure(Rejected(Problem.INVALID))
        // Accept both "http://host:11434" and "http://host:11434/v1"; the provider appends "v1/...".
        val base = if (url.pathSegments.lastOrNull() == "v1") url.newBuilder().removePathSegment(url.pathSegments.lastIndex).build() else url
        return if (isPrivateHost(base.host)) Result.success(base) else Result.failure(Rejected(Problem.NOT_PRIVATE))
    }

    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        // Only literal IP addresses are classified; other names would need DNS and could resolve anywhere.
        if (!h.matches(Regex("[0-9.]+")) && !h.contains(':')) return false
        val address = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
        return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
            (address.address.size == 16 && (address.address[0].toInt() and 0xFE) == 0xFC)
    }
}
