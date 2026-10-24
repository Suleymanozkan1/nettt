package com.bookmind.core.ai.gemini

import com.bookmind.core.ai.AiException
import com.bookmind.core.ai.AiRequest
import com.bookmind.core.ai.AiTask
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Gemini REST client against a local fake server: request shape, answer parsing, retries and quotas. */
class GeminiClientTest {
    private val server = MockWebServer().apply { start() }
    private val sleeps = mutableListOf<Long>()

    @After fun tearDown() = server.shutdown()

    private fun client(maxRetries: Int = 3, key: String = "test-key", clock: () -> Long = { 1_000L }) = GeminiClient(
        GeminiSettings(key, "gemini-3.8-flash", "low", baseUrl = server.url("/v1beta").toString(), maxRetries = maxRetries, initialBackoffMs = 1_000, maxBackoffMs = 60_000,
            extraHeaders = mapOf("X-Android-Package" to "com.bookmind.app", "X-Android-Cert" to "AB12")),
        sleep = { sleeps += it }, random = Random(1), clock = clock,
    )

    private fun ok(text: String, finish: String = "STOP") = MockResponse().setResponseCode(200).setBody(
        """{"candidates":[{"content":{"role":"model","parts":[{"text":"thinking…","thought":true},{"text":${JsonPrimitive(text)}}]},"finishReason":"$finish"}],
           "usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":40,"thoughtsTokenCount":10,"totalTokenCount":170}}""",
    )

    @Test fun `sends a stateless generateContent request with the key in a header and no sampling parameters`() = runTest {
        server.enqueue(ok("{\"ok\":true}"))
        val reply = client().generate("SYS", "USER", 2_000, json = true)
        val req = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.8-flash:generateContent", req.path)
        assertEquals("test-key", req.getHeader("x-goog-api-key"))
        assertFalse(req.requestUrl.toString().contains("key="), "the key must not be in the URL")
        assertEquals("com.bookmind.app", req.getHeader("X-Android-Package"))
        assertEquals("AB12", req.getHeader("X-Android-Cert"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("SYS", body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("USER", body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        val config = body["generationConfig"]!!.jsonObject
        assertEquals("2000", config["maxOutputTokens"]!!.jsonPrimitive.content)
        assertEquals("application/json", config["responseMimeType"]!!.jsonPrimitive.content)
        assertEquals("low", config["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content)
        assertNull(config["temperature"])
        // Thought parts are not part of the answer; usage includes thinking tokens.
        assertEquals("{\"ok\":true}", reply.text)
        assertEquals(120, reply.inputTokens)
        assertEquals(50, reply.outputTokens)
        assertFalse(reply.truncated)
    }

    @Test fun `rate limits are retried after the delay the server asks for, then with exponential backoff`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody(
            """{"error":{"code":429,"message":"Resource has been exhausted","status":"RESOURCE_EXHAUSTED",
               "details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"7s"}]}}""",
        ))
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":503,"message":"overloaded","status":"UNAVAILABLE"}}"""))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3").setBody("{}"))
        server.enqueue(ok("tamam"))
        assertEquals("tamam", client().generate("s", "u", 500, json = false).text)
        assertEquals(4, server.requestCount)
        assertEquals(7_000L, sleeps[0])
        assertTrue(sleeps[1] in 2_000L..2_500L, "${sleeps[1]}") // second attempt: 2 s + jitter
        assertEquals(3_000L, sleeps[2])
    }

    @Test fun `gives up after the retry limit and reports a temporary rate limit`() = runTest {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}""")) }
        val e = assertFailsWith<AiException.QuotaExceeded> { client(maxRetries = 2).generate("s", "u", 500, json = false) }
        assertEquals(3, server.requestCount)
        assertTrue(e.resetAt!! > 1_000L)
        assertEquals(2, sleeps.size)
    }

    @Test fun `an exhausted daily quota is not retried and resets at midnight Pacific time`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody(
            """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure",
               "violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}""",
        ))
        val now = java.time.ZonedDateTime.of(2026, 10, 24, 15, 0, 0, 0, java.time.ZoneId.of("America/Los_Angeles")).toInstant().toEpochMilli()
        val e = assertFailsWith<AiException.QuotaExceeded> { client(clock = { now }).generate("s", "u", 500, json = false) }
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
        assertEquals(9 * 3_600_000L, e.resetAt!! - now)
    }

    @Test fun `bad keys, bad requests and blocked or empty answers fail without retrying`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":403,"message":"API key not valid","status":"PERMISSION_DENIED"}}"""))
        assertFailsWith<AiException.Unauthorized> { client().generate("s", "u", 500, json = false) }
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"message":"bad field","status":"INVALID_ARGUMENT"}}"""))
        val bad = assertFailsWith<AiException.Server> { client().generate("s", "u", 500, json = false) }
        assertTrue(bad.message!!.contains("bad field"))
        server.enqueue(MockResponse().setBody("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        assertFailsWith<AiException.InvalidOutput> { client().generate("s", "u", 500, json = false) }
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[]},"finishReason":"MAX_TOKENS"}]}"""))
        val empty = assertFailsWith<AiException.InvalidOutput> { client().generate("s", "u", 500, json = false) }
        assertTrue(empty.message!!.contains("MAX_TOKENS"))
        assertEquals(4, server.requestCount)
        assertFailsWith<AiException.NotConfigured> { client(key = "").generate("s", "u", 500, json = false) }
    }

    @Test fun `a cut-off answer is returned and flagged`() = runTest {
        server.enqueue(ok("yarım kal", finish = "MAX_TOKENS"))
        assertTrue(client().generate("s", "u", 500, json = false).truncated)
    }

    @Test fun `the provider asks for JSON and leaves room for thinking tokens`() = runTest {
        server.enqueue(ok("{\"a\":1}"))
        val provider = GeminiProvider(client())
        val r = provider.complete(AiRequest(AiTask.ANSWER, "v", "sys", "user", maxOutputTokens = 800))
        assertEquals("{\"a\":1}", r.text)
        assertEquals("gemini:gemini-3.8-flash", provider.id)
        assertFalse(provider.free)
        val config = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["generationConfig"]!!.jsonObject
        assertEquals("application/json", config["responseMimeType"]!!.jsonPrimitive.content)
        assertEquals((800 * 2 + GeminiProvider.THINKING_ROOM_TOKENS).toString(), config["maxOutputTokens"]!!.jsonPrimitive.content)
    }
}
