package com.bookmind.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bookmind.app.data.remote.BackendConfig
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    private val server = MockWebServer()
    private val requests = Collections.synchronizedList(mutableListOf<Pair<RecordedRequest, String>>())
    private var remoteRows: Map<String, String> = emptyMap()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                requests += request to body
                val path = request.requestUrl!!.encodedPath
                return when {
                    path == "/auth/v1/token" -> MockResponse().setBody("""{"access_token":"jwt","refresh_token":"r","expires_in":3600,"user":{"id":"remote-user","email":"a@b.c"}}""")
                    request.method == "GET" && path.startsWith("/rest/v1/") -> MockResponse().setBody(remoteRows[path.removePrefix("/rest/v1/")] ?: "[]")
                    path.startsWith("/rest/v1/") -> MockResponse().setResponseCode(201)
                    path.startsWith("/storage/v1/object/") -> MockResponse().setBody("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    private val graph = TestGraph(backend = BackendConfig(server.url("/").toString().trimEnd('/'), "anon", allowLocalHttp = true))

    @After fun tearDown() { graph.close(); server.shutdown() }

    @Test fun `push sends local changes without user ids and uploads the book file`() = runTest {
        val bookId = graph.demoBook()
        graph.auth.signIn("a@b.c", "password1")
        val report = graph.syncEngine.sync()
        assertTrue(report.pushed > 0)
        assertEquals(0, report.failed)
        assertTrue(graph.db.sync().pending(10).isEmpty(), "outbox drained")

        val bookPush = requests.first { it.first.method == "POST" && it.first.requestUrl!!.encodedPath == "/rest/v1/books" }
        assertTrue(bookPush.second.contains("\"title\":\"Öğrenmenin Bilimi\""))
        assertTrue(bookPush.second.contains("\"is_demo\":true"), "booleans are sent as JSON booleans")
        assertFalse(bookPush.second.contains("user_id"), "user_id is set by the server from the JWT")
        assertEquals("Bearer jwt", bookPush.first.getHeader("Authorization"))
        assertTrue(requests.any { it.first.requestUrl!!.encodedPath.startsWith("/storage/v1/object/book-files/remote-user/$bookId/") })
        // Parents are pushed before children (foreign keys on the server).
        val order = requests.filter { it.first.method == "POST" }.map { it.first.requestUrl!!.encodedPath }
        assertTrue(order.indexOf("/rest/v1/books") < order.indexOf("/rest/v1/book_sources"))
    }

    @Test fun `pull merges remote rows with last-writer-wins`() = runTest {
        val bookId = graph.demoBook()
        val local = graph.db.books().get(bookId)!!
        remoteRows = mapOf(
            "books" to """[{"id":"$bookId","user_id":"remote-user","title":"Yeni Başlık","source_type":"MARKDOWN","processing_status":"READY",
                "processing_progress":100,"chapter_count":5,"word_count":10,"is_demo":true,"created_at":1,"updated_at":${local.updatedAt + 1000}}]""",
            "user_notes" to """[{"id":"remote-note","user_id":"remote-user","book_id":"$bookId","content":"Diğer cihazdan not","is_favorite":true,
                "in_review_queue":false,"created_at":5,"updated_at":5,"server_updated_at":"2026-10-18 10:00:00.123456+00"}]""",
        )
        graph.auth.signIn("a@b.c", "password1")
        graph.syncEngine.sync()
        assertEquals("Yeni Başlık", graph.db.books().get(bookId)!!.title, "newer remote edit wins")
        val note = graph.db.notes().get("remote-note")!!
        assertEquals(graph.users.userId(), note.userId, "remote rows are owned by the local profile")
        assertTrue(note.isFavorite)
        assertTrue(graph.db.sync().pending(10).isEmpty(), "pulled rows are not echoed back to the server")

        // An older remote version must not overwrite a newer local edit.
        graph.clock.advanceDays(1.0)
        graph.library.updateMetadata(bookId, "Yerel Başlık", null)
        remoteRows = mapOf("books" to remoteRows.getValue("books").replace("Yeni Başlık", "Eski Başlık"))
        requests.clear()
        graph.syncEngine.sync()
        assertEquals("Yerel Başlık", graph.db.books().get(bookId)!!.title)
        // The next pull asks only for rows newer than the last server timestamp seen (minus a safety overlap).
        val notesPull = requests.first { it.first.method == "GET" && it.first.requestUrl!!.encodedPath == "/rest/v1/user_notes" }.first
        assertEquals("gt.2026-10-18T09:55:00.123456Z", notesPull.requestUrl!!.queryParameter("server_updated_at"))
    }

    @Test fun `every write path is captured by triggers, including cascades and processor output`() = runTest {
        val bookId = graph.demoBook()
        val pending = graph.db.sync().pending(10_000)
        val entities = pending.map { it.entity }.toSet()
        listOf("books", "book_sources", "chapters", "concepts", "learning_items", "review_states", "users").forEach {
            assertTrue(it in entities, "missing outbox entries for $it")
        }
        graph.library.delete(bookId)
        assertTrue(graph.db.sync().pending(10_000).any { it.entity == "chapters" && it.op == "DELETE" })
    }

    @Test fun `sign-in snapshot queues the whole library in foreign-key order`() = runTest {
        graph.demoBook()
        graph.syncEngine.enqueueSnapshot()
        val order = graph.db.sync().pending(10_000).map { it.entity }.distinct()
        assertTrue(order.indexOf("books") < order.indexOf("chapters"))
        assertTrue(order.indexOf("chapters") < order.indexOf("concepts"))
        assertTrue(order.indexOf("learning_items") < order.indexOf("review_states"))
        assertTrue("file" in order)
    }

    @Test fun `without an account sync does nothing`() = runTest {
        graph.demoBook()
        assertEquals(0, graph.syncEngine.sync().pushed)
        assertTrue(requests.isEmpty())
    }
}
