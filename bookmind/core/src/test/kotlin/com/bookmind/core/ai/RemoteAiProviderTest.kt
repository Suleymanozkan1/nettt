package com.bookmind.core.ai

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RemoteAiProviderTest {
    private val server = MockWebServer().apply { start() }
    private val request = AiRequest(AiTask.SUMMARY, "summary/3", "sys", "user")

    @After fun tearDown() = server.shutdown()

    private fun provider(token: String? = "jwt") = RemoteAiProvider(server.url("/functions/v1/ai").toString(), "anon", { token })

    @Test fun `sends the session token, never a provider key, and parses usage`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"{}","model":"m-1","usage":{"inputTokens":10,"outputTokens":5,"costMicros":42}}"""))
        val r = provider().complete(request)
        assertEquals("m-1", r.model); assertEquals(10, r.inputTokens); assertEquals(42L, r.costMicros)
        val sent = server.takeRequest()
        assertEquals("Bearer jwt", sent.getHeader("Authorization"))
        assertEquals("anon", sent.getHeader("apikey"))
        assertTrue(sent.body.readUtf8().contains("\"task\":\"SUMMARY\""))
    }

    @Test fun `maps http errors to typed exceptions`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"quota","resetAt":123}"""))
        val quota = assertFailsWith<AiException.QuotaExceeded> { provider().complete(request) }
        assertEquals(123L, quota.resetAt)
        assertTrue(!quota.retryable)

        server.enqueue(MockResponse().setResponseCode(401))
        assertFailsWith<AiException.Unauthorized> { provider().complete(request) }

        server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":"upstream"}"""))
        val e = assertFailsWith<AiException.Server> { provider().complete(request) }
        assertTrue(e.retryable)

        server.enqueue(MockResponse().setBody("<html>proxy page</html>"))
        assertFailsWith<AiException.InvalidOutput> { provider().complete(request) }
    }

    @Test fun `network failures are retryable`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val e = runCatching { provider().complete(request) }.exceptionOrNull()
        assertTrue(e is AiException && e.retryable, "got $e")
    }

    @Test fun `missing session is unauthorized without a request`() = runTest {
        assertFailsWith<AiException.Unauthorized> { provider(token = null).complete(request) }
        assertEquals(0, server.requestCount)
    }
}
