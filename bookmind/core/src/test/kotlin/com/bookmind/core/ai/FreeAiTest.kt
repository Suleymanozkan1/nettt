package com.bookmind.core.ai

import com.bookmind.core.TestFixtures
import com.bookmind.core.search.Bm25Index
import com.bookmind.core.text.MarkdownParser
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Free, local AI paths: self-hosted server, on-device model, and the AI-free building blocks. */
class FreeAiTest {
    private val server = MockWebServer().apply { start() }
    @After fun tearDown() = server.shutdown()

    private val request = AiRequest(AiTask.SUMMARY, "summary/3", "sys", "user", maxOutputTokens = 300, temperature = 0.2)

    @Test fun `local server provider speaks the OpenAI-compatible chat API with JSON mode and costs nothing`() = runTest {
        server.enqueue(MockResponse().setBody("""{"model":"qwen2.5","choices":[{"message":{"role":"assistant","content":"{\"a\":1}"}}],"usage":{"prompt_tokens":12,"completion_tokens":3}}"""))
        val p = LocalServerProvider(server.url("/").toString(), "qwen2.5")
        val r = p.complete(request)
        assertEquals("{\"a\":1}", r.text)
        assertEquals(0L, r.costMicros)
        assertEquals(12, r.inputTokens)
        assertTrue(p.free)
        val sent = server.takeRequest()
        assertEquals("/v1/chat/completions", sent.path)
        assertEquals(null, sent.getHeader("Authorization"), "no API key is ever sent")
        val body = sent.body.readUtf8()
        listOf("\"model\":\"qwen2.5\"", "\"role\":\"system\"", "\"role\":\"user\"", "\"max_tokens\":300", "\"response_format\":{\"type\":\"json_object\"}")
            .forEach { assertTrue(body.contains(it), "$it missing in $body") }
    }

    @Test fun `local server errors are typed and retryable only when transient`() = runTest {
        val p = LocalServerProvider(server.url("/v1").toString(), "m")
        server.enqueue(MockResponse().setResponseCode(503).setBody("loading model"))
        assertTrue(assertFailsWith<AiException.Server> { p.complete(request) }.retryable)
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        assertFailsWith<AiException.InvalidOutput> { p.complete(request) }
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"llama3.2"},{"id":"qwen2.5"}]}"""))
        assertEquals(listOf("llama3.2", "qwen2.5"), p.listModels())
        assertEquals("/v1/models", server.let { it.takeRequest(); it.takeRequest(); it.takeRequest() }.path)
        // Nothing listening (the computer is off): a network error, retried with backoff, then the local fallback.
        val port = java.net.ServerSocket(0).use { it.localPort }
        val e = assertFailsWith<AiException.Network> { LocalServerProvider("http://127.0.0.1:$port", "m").complete(request) }
        assertTrue(e.retryable)
    }

    @Test fun `only private network addresses are accepted for the local model`() {
        listOf("http://localhost:11434", "127.0.0.1:8080", "http://192.168.1.20:11434/v1", "http://10.0.2.2:1234", "172.20.3.4:8080",
            "http://my-pc.local:11434", "http://[::1]:11434", "http://[fd12::5]:8080")
            .forEach { assertTrue(LocalEndpoint.parse(it).isSuccess, it) }
        listOf("https://api.openai.com", "http://8.8.8.8:11434", "http://example.com:11434", "http://172.32.0.1", "ftp://192.168.1.2", "not a url")
            .forEach { assertTrue(LocalEndpoint.parse(it).isFailure, it) }
        assertEquals("http://192.168.1.20:11434/", LocalEndpoint.parse("192.168.1.20:11434/v1/").getOrThrow().toString())
    }

    @Test fun `on-device provider formats the model's chat template and strips the end marker`() = runTest {
        var seen = ""
        val p = OnDeviceProvider("gemma3-1b", ChatTemplate.GEMMA) { prompt -> seen = prompt; "{\"ok\":true}<end_of_turn>\n<start_of_turn>user" }
        assertEquals("{\"ok\":true}", p.complete(request).text)
        assertTrue(seen.startsWith("<start_of_turn>user\nsys") && seen.endsWith("<start_of_turn>model\n"))
        assertEquals(ChatTemplate.CHATML, ChatTemplate.forModelName("Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"))
        assertEquals(ChatTemplate.GEMMA, ChatTemplate.forModelName("gemma3-1b-it-int4.task"))
        val failing = OnDeviceProvider("m", ChatTemplate.CHATML) { throw IllegalStateException("model file missing") }
        assertFailsWith<AiException.Server> { failing.complete(request) }
    }

    @Test fun `prompts are trimmed to a small model's budget by shortening book text only`() {
        val long = "x".repeat(10_000)
        val req = AiRequest(AiTask.SUMMARY, "v", "SYSTEM", "Instructions first.\n<book_text source=\"a\">\n$long\n</book_text>\nReturn JSON: {}")
        val fitted = LearningAi.fitToBudget(req, 3_000)
        assertTrue(fitted.system.length + fitted.user.length <= 3_000, "${fitted.user.length}")
        assertTrue(fitted.user.startsWith("Instructions first.") && fitted.user.endsWith("Return JSON: {}"))
        assertTrue(fitted.user.contains("[…]"))
        assertEquals(req, LearningAi.fitToBudget(req, 100_000))
    }

    @Test fun `a failing local model falls back to the on-device engine with a visible warning, never to a paid service`() = runTest {
        val local = OnDeviceProvider("m", ChatTemplate.CHATML) { throw IllegalStateException("crashed") }
        val chapter = LocalHeuristicEngine.ChapterInput(0, "Unutma Eğrisi", MarkdownParser.parse(TestFixtures.demoBookMarkdown).chapters[0].text)
        val r = LearningAi({ local }, baseBackoffMs = 1).analyzeChapter(LearningAi.Book("K", null, "tr", BookGenre.INFORMATIONAL), chapter, chapter.text, "")
        r as AiResult.Ok
        assertFalse(r.info.aiGenerated)
        assertTrue(r.warnings.single().contains("yerel yöntem"))
    }

    private val book = MarkdownParser.parse(TestFixtures.demoBookMarkdown)
    private val passages = Bm25Index.passages(book.chapters.mapIndexed { i, c -> Triple(i, c.title, c.text) })

    @Test fun `bm25 finds the passage that answers a question, with Turkish suffixes`() {
        val index = Bm25Index(passages)
        val hit = index.search("Kararlılık nedir, algoritmalar neyi hesaplar?").first()
        assertEquals(1, hit.passage.chapterIndex, hit.passage.text)
        assertTrue(hit.passage.text.contains("Kararlılık"))
        assertEquals(2, index.search("tanıdıklık hissi yanılsaması").first().passage.chapterIndex)
        assertTrue(index.search("kuantum kromodinamiği").isEmpty())
        passages.forEach { p -> assertEquals(p.text, book.chapters[p.chapterIndex].text.substring(p.start, p.end).trim()) }
    }

    @Test fun `extractive answers quote the book and say so when the book has no answer`() {
        val engine = LocalHeuristicEngine()
        val hits = Bm25Index(passages).search("Test etkisi nedir?").map { it.passage }
        val a = engine.answer("Test etkisi nedir?", hits)
        assertTrue(a.inBook && a.citations.isNotEmpty())
        assertTrue(a.answer.contains("Test etkisi"), a.answer)
        assertFalse(engine.answer("Uzay yolculuğu nasıl yapılır?", emptyList()).inBook)
        assertTrue(OutputValidator.answer(BookAnswer("x", citations = listOf(0, 99)), hits).value.citations == listOf(0))
    }

    @Test fun `faithfulness check flags invented numbers and names but accepts those in the book`() {
        val src = listOf("Hermann Ebbinghaus on dokuzuncu yüzyılda hafızasını ölçtü. Katılımcıların yüzde 90'ı hatırladı; 1885 yılında yayımlandı.")
        val ok = Faithfulness.check(listOf("Bu deneyde Ebbinghaus 1885'te sonuçlarını yayımladı ve oran 90 idi."), src)
        assertTrue(ok.clean, ok.toString())
        val bad = Faithfulness.check(listOf("Bunu 1923'te Sigmund Freud doğruladı ve 3 deney yaptı."), src)
        assertEquals(listOf("1923", "3"), bad.unsupportedNumbers)
        assertEquals(listOf("Sigmund", "Freud"), bad.unsupportedNames)
    }

    @Test fun `textrank picks central sentences without near-duplicates, deterministically`() {
        val engine = LocalHeuristicEngine()
        val text = book.chapters[0].text + "\n\nUnutma eğrisi, öğrenilen bir bilginin zaman içinde ne kadar hızlı kaybolduğunu gösteren bir modeldir."
        val ks = engine.keySentences(text, 4)
        assertEquals(ks, engine.keySentences(text, 4))
        assertEquals(1, ks.count { it.text.startsWith("Unutma eğrisi, öğrenilen") }, "duplicate sentence chosen twice")
        assertTrue(ks.zipWithNext().all { (a, b) -> a.start < b.start })
    }
}
