package com.bookmind.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import com.bookmind.app.ai.GeminiConfig
import com.bookmind.app.data.settings.AiMode
import com.bookmind.app.data.summary.BookSummaryRepository
import com.bookmind.app.data.summary.BookSummaryRepository.Outcome
import com.bookmind.app.data.summary.BookSummaryRepository.Phase
import com.bookmind.app.work.WorkScheduler
import com.bookmind.core.ai.AiException
import com.bookmind.core.ai.summary.SummaryConfig
import com.bookmind.core.text.TextNormalizer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Book summaries with Gemini (a fake model here): chapter by chapter, saved, resumable and never repeated. */
@RunWith(AndroidJUnit4::class)
class BookSummaryTest {
    private val fake = FakeGemini()
    private var graph = TestGraph(summaryModel = fake)

    // Settings live in a process-wide DataStore: start every test with the defaults (Gemini on).
    @org.junit.Before fun setUp() = kotlinx.coroutines.runBlocking { graph.settings.clear() }
    @After fun tearDown() { kotlinx.coroutines.runBlocking { graph.settings.clear() }; graph.close() }

    /** A book of [chapters] chapters with [words] words each, imported and processed. */
    private suspend fun book(chapters: Int, words: Int): String {
        val text = (1..chapters).joinToString("\n\n") { c ->
            "Bölüm $c\n\n" + (1..words).chunked(60).joinToString("\n\n") { p -> p.joinToString(" ") { "kelime$c-$it" } + "." }
        }
        val id = graph.library.importText("Uzun Kitap", "Yazar", text, markdown = false)
        graph.processor.process(id)
        return id
    }

    @Test fun `summarises every chapter, writes a book summary and saves it on the phone`() = runTest {
        val id = book(chapters = 6, words = 900)
        assertIs<Outcome.Done>(graph.summaries.run(id))
        val s = assertNotNull(graph.summaries.summary(id))
        assertTrue(s.complete)
        assertEquals(6, s.chapters.size)
        s.chapters.forEach { c -> assertTrue(c.words.toDouble() / c.sourceWords in 0.08..0.14, "${c.title}: ${c.words}/${c.sourceWords}") }
        // 5,400 words → chunks of 500–2,000 words in the test settings; plus one request for the whole book.
        assertEquals(graph.summaries.status(id).total + 1, fake.calls)
        assertEquals(Phase.DONE, graph.summaries.status(id).phase)
        assertTrue(s.overall!!.startsWith("## Kitabın ana fikri"))
        // Plain text was sent, never a PDF, and the book text is marked as data.
        assertTrue(fake.prompts.first().contains("<part id=\"0\" chapter=\"Bölüm 1\""))
        assertTrue(graph.context.filesDir.resolve("book-summaries").listFiles()!!.any { it.name == "${s.contentHash}.json" })
    }

    @Test fun `the same book is never sent twice, not even after deleting and adding it again`() = runTest {
        val id = book(chapters = 3, words = 800)
        graph.summaries.run(id)
        val calls = fake.calls
        assertIs<Outcome.Done>(graph.summaries.run(id))
        graph.library.delete(id)
        val again = book(chapters = 3, words = 800)
        assertIs<Outcome.Done>(graph.summaries.run(again))
        assertEquals(calls, fake.calls, "no new requests")
        assertTrue(graph.summaries.summary(again)!!.complete)
    }

    @Test fun `a rate limit pauses the run and it continues where it stopped`() = runTest {
        val id = book(chapters = 6, words = 900)
        // The second request hits the daily quota.
        fake.failWith = { call -> if (call == 2) AiException.QuotaExceeded(graph.clock.now() + 3_600_000) else null }
        val first = graph.summaries.run(id)
        assertIs<Outcome.RetryLater>(first)
        assertEquals(graph.clock.now() + 3_600_000, first.at)
        val waiting = graph.summaries.status(id)
        assertEquals(Phase.WAITING_FOR_LIMIT, waiting.phase)
        assertEquals(1, waiting.done)
        assertEquals(1, graph.summaries.summary(id)!!.chunksDone.size, "the first request was saved")
        fake.failWith = null
        val before = fake.calls
        assertIs<Outcome.Done>(graph.summaries.run(id))
        assertEquals(waiting.total - 1 + 1, fake.calls - before, "only the remaining requests and the book summary")
    }

    @Test fun `errors are reported, bad keys are not retried`() = runTest {
        val id = book(chapters = 2, words = 700)
        fake.failWith = { AiException.Server(503, "overloaded") }
        val busy = graph.summaries.run(id)
        assertIs<Outcome.Failed>(busy)
        assertTrue(busy.retryable)
        assertEquals(Phase.FAILED, graph.summaries.status(id).phase)
        fake.failWith = { AiException.Unauthorized() }
        val bad = graph.summaries.run(id)
        assertIs<Outcome.Failed>(bad)
        assertTrue(!bad.retryable)
    }

    @Test fun `without a key or with AI off nothing is sent`() = runTest {
        graph.close()
        graph = TestGraph(summaryModel = fake, gemini = GeminiConfig("", "fake-gemini", "low", 1, SummaryConfig()))
        val id = book(chapters = 2, words = 600)
        assertEquals(BookSummaryRepository.Unavailable.NO_API_KEY, graph.summaries.unavailable())
        assertIs<Outcome.Skipped>(graph.summaries.run(id))
        graph.close()
        graph = TestGraph(summaryModel = fake)
        val id2 = book(chapters = 2, words = 600)
        graph.settings.update { it.copy(aiMode = AiMode.LOCAL) }
        assertEquals(BookSummaryRepository.Unavailable.AI_OFF, graph.summaries.unavailable())
        graph.summaries.request(id2)
        assertIs<Outcome.Skipped>(graph.summaries.run(id2))
        assertEquals(0, fake.calls)
        assertTrue(graph.workManager.getWorkInfosForUniqueWork(WorkScheduler.summaryName(id2)).get().isEmpty())
    }

    @Test fun `a processed book is queued for summarising in the background, once`() = runTest {
        val id = book(chapters = 2, words = 600)
        graph.summaries.autoStart(id)
        graph.summaries.autoStart(id)
        val work = graph.workManager.getWorkInfosForUniqueWork(WorkScheduler.summaryName(id)).get()
        assertEquals(1, work.size)
        assertEquals(WorkInfo.State.ENQUEUED, work.single().state, "waits for a network connection")
        assertEquals(Phase.QUEUED, graph.summaries.status(id).phase)
        // Deleting everything also deletes saved summaries.
        graph.summaries.run(id)
        graph.account.deleteEverything()
        assertNull(graph.context.filesDir.resolve("book-summaries").listFiles()?.firstOrNull())
    }

    @Test fun `the summary keeps chapter order and the word counts of the book`() = runTest {
        val id = book(chapters = 5, words = 500)
        graph.summaries.run(id)
        val s = graph.summaries.summary(id)!!
        assertEquals((0 until 5).toList(), s.chapters.map { it.chapterIndex })
        assertEquals(s.chapters.sumOf { it.sourceWords }, s.bookWords)
        assertTrue(TextNormalizer.wordCount(s.overall!!) > 0)
    }
}
