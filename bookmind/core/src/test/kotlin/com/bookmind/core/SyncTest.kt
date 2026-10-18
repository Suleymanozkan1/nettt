package com.bookmind.core

import com.bookmind.core.sync.SupabaseRest
import com.bookmind.core.sync.SyncMerge
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncTest {
    private val server = MockWebServer().apply { start() }
    private val rest = SupabaseRest(server.url("/").toString(), "anon")

    @After fun tearDown() = server.shutdown()

    @Test fun `sign in parses the session`() = runTest {
        server.enqueue(MockResponse().setBody("""{"access_token":"a","refresh_token":"r","expires_in":3600,"user":{"id":"u1","email":"e@x"}}"""))
        val s = rest.signIn("e@x", "pw")
        assertEquals("u1", s.user!!.id)
        assertEquals(4600, s.expiry(1000))
        val req = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=password", req.path)
        assertEquals("anon", req.getHeader("apikey"))
    }

    @Test fun `sign up without confirmation returns no session`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"u1","email":"e@x"}"""))
        assertNull(rest.signUp("e@x", "pw"))
    }

    @Test fun `upsert uses merge-duplicates and the user token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        rest.upsert("notes", listOf(buildJsonObject { put("id", JsonPrimitive("n1")) }), "jwt")
        val req = server.takeRequest()
        assertEquals("Bearer jwt", req.getHeader("Authorization"))
        assertTrue(req.getHeader("Prefer")!!.contains("merge-duplicates"))
        assertEquals("""[{"id":"n1"}]""", req.body.readUtf8())
    }

    @Test fun `changed rows query is filtered and ordered`() = runTest {
        server.enqueue(MockResponse().setBody("""[{"id":"1"},{"id":"2"}]"""))
        val rows = rest.changedSince("books", "updated_at", "2026-01-01T00:00:00Z", "jwt")
        assertEquals(2, rows.size)
        val path = server.takeRequest().requestUrl!!
        assertEquals("gt.2026-01-01T00:00:00Z", path.queryParameter("updated_at"))
        assertEquals("updated_at.asc", path.queryParameter("order"))
    }

    @Test fun `http errors surface with their code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"rls"}"""))
        val e = assertFailsWith<SupabaseRest.HttpException> { rest.upsert("books", listOf(buildJsonObject { }), "jwt") }
        assertEquals(403, e.code)
    }

    @Test fun `last writer wins and deletions beat older edits`() {
        assertEquals(SyncMerge.Decision.TAKE_REMOTE, SyncMerge.resolve(100, null, 200, null))
        assertEquals(SyncMerge.Decision.KEEP_LOCAL, SyncMerge.resolve(300, null, 200, null))
        assertEquals(SyncMerge.Decision.TAKE_REMOTE, SyncMerge.resolve(100, null, 50, 150))
        assertEquals(SyncMerge.Decision.KEEP_LOCAL, SyncMerge.resolve(100, 300, 200, null))
        assertEquals(SyncMerge.Decision.TAKE_REMOTE, SyncMerge.resolve(null, null, 1, null))
        assertEquals(SyncMerge.Decision.TAKE_REMOTE, SyncMerge.resolve(100, null, 100, 100))
    }

    @Test fun `review state keeps the more reviewed side`() {
        assertEquals(SyncMerge.Decision.TAKE_REMOTE, SyncMerge.resolveReviewState(3, 500, 4, 400))
        assertEquals(SyncMerge.Decision.KEEP_LOCAL, SyncMerge.resolveReviewState(4, 500, 4, 400))
    }
}
