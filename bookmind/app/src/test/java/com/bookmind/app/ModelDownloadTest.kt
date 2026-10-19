package com.bookmind.app

import com.bookmind.app.ai.ModelCatalog
import com.bookmind.app.ai.ModelDownloader
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-app model download: resume, restart, verification, and the pinned free-model catalog. */
class ModelDownloadTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer().apply { start() }
    @After fun tearDown() = server.shutdown()

    private val data = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private val downloader = ModelDownloader(OkHttpClient())
    private fun body(from: Int = 0) = Buffer().write(data, from, data.size - from)

    @Test fun `downloads, reports progress and verifies the checksum`() = runTest {
        server.enqueue(MockResponse().setBody(body()))
        val part = tmp.newFile("m.part").apply { delete() }
        var last = 0L
        downloader.download(server.url("/m").toString(), data.size.toLong(), part) { last = it }
        assertEquals(data.size.toLong(), last)
        assertTrue(downloader.verify(part, data.size.toLong(), sha))
        assertNull(server.takeRequest().getHeader("Range"))
    }

    @Test fun `an interrupted download resumes with a range request`() = runTest {
        val part = tmp.newFile("m.part")
        part.writeBytes(data.copyOf(120_000))
        server.enqueue(MockResponse().setResponseCode(206).setBody(body(120_000)))
        downloader.download(server.url("/m").toString(), data.size.toLong(), part) {}
        assertEquals("bytes=120000-", server.takeRequest().getHeader("Range"))
        assertTrue(downloader.verify(part, data.size.toLong(), sha))
    }

    @Test fun `a server that ignores the range restarts from zero instead of corrupting the file`() = runTest {
        val part = tmp.newFile("m.part")
        part.writeBytes(ByteArray(50_000) { 7 })
        server.enqueue(MockResponse().setBody(body()))
        downloader.download(server.url("/m").toString(), data.size.toLong(), part) {}
        assertTrue(downloader.verify(part, data.size.toLong(), sha))
    }

    @Test fun `errors keep the partial file, corrupt files fail verification`() = runTest {
        val part = tmp.newFile("m.part")
        part.writeBytes(data.copyOf(10_000))
        server.enqueue(MockResponse().setResponseCode(503))
        assertFailsWith<IOException> { downloader.download(server.url("/m").toString(), data.size.toLong(), part) {} }
        assertEquals(10_000, part.length(), "partial download is kept for the next attempt")
        server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(ByteArray(5) { 1 })))
        assertFailsWith<IOException> { downloader.download(server.url("/m").toString(), data.size.toLong(), part) {} }
        val wrong = tmp.newFile("w.part").apply { writeBytes(data.copyOf().also { it[5] = 0 }) }
        assertFalse(downloader.verify(wrong, data.size.toLong(), sha))
    }

    @Test fun `catalog models are free, pinned and chosen by the phone's memory`() {
        ModelCatalog.all.forEach { m ->
            assertEquals("Apache-2.0", m.license)
            assertTrue(m.url.startsWith("https://huggingface.co/litert-community/") && "/resolve/${m.revision}/" in m.url, m.url)
            assertTrue(m.revision.matches(Regex("[0-9a-f]{40}")) && m.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(m.fileName.endsWith(".litertlm") && m.bytes > 100_000_000)
        }
        assertEquals(ModelCatalog.GEMMA_4_E2B, ModelCatalog.recommended(7.4))
        assertEquals(ModelCatalog.GEMMA_4_E2B, ModelCatalog.recommended(5.5))
        assertEquals(ModelCatalog.QWEN_3_06B, ModelCatalog.recommended(3.6))
        assertNull(ModelCatalog.recommended(1.8))
        assertFalse(ModelCatalog.fits(ModelCatalog.GEMMA_4_E4B, 7.4))
    }
}
