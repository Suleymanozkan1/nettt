package com.bookmind.app

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bookmind.app.data.db.ProcessingStatus
import com.bookmind.app.data.files.ImportException
import com.bookmind.app.data.repo.DuplicateBookException
import com.bookmind.core.ai.AiException
import com.bookmind.core.ai.MockAiProvider
import com.bookmind.core.text.SourceType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class PipelineTest {
    private var graph = TestGraph()
    @After fun tearDown() = graph.close()

    @Test fun `demo book is extracted, analysed and turned into review cards`() = runTest {
        val id = graph.demoBook()
        val book = graph.db.books().get(id)!!
        assertEquals(ProcessingStatus.READY, book.processingStatus)
        assertEquals(SourceType.MARKDOWN, book.sourceType)
        assertEquals("INFORMATIONAL", book.category)
        val chapters = graph.db.chapters().forBook(id)
        assertEquals(listOf("Unutma Eğrisi", "Aralıklı Tekrar", "Aktif Hatırlama", "Bilişsel Yük", "Transfer"), chapters.map { it.title })
        assertTrue(chapters.all { it.processingStatus == ProcessingStatus.READY && !it.summary.isNullOrBlank() })
        val concepts = graph.db.concepts().forBook(id)
        assertTrue(concepts.size >= 10, "concepts: ${concepts.map { it.title }}")
        val items = graph.db.learning().allItems(graph.users.userId())
        assertEquals(concepts.distinctBy { it.normalizedTitle }.size, items.size)
        assertTrue(graph.db.learning().allStates(graph.users.userId()).all { it.nextReviewAt == null }, "new cards are not due until introduced")
    }

    @Test fun `processing is idempotent and resumes unfinished chapters`() = runTest {
        val id = graph.demoBook()
        val userId = graph.users.userId()
        val itemsBefore = graph.db.learning().allItems(userId).map { it.id }.toSet()
        graph.processor.process(id)
        assertEquals(itemsBefore, graph.db.learning().allItems(userId).map { it.id }.toSet(), "re-running must not duplicate cards")

        // Simulate an interruption after chapter 2: mark it unfinished, it alone is re-analysed.
        val ch = graph.db.chapters().forBook(id)[2]
        graph.db.chapters().upsert(ch.copy(processingStatus = ProcessingStatus.QUEUED, summary = null))
        graph.processor.process(id)
        assertEquals(ProcessingStatus.READY, graph.db.chapters().get(ch.id)!!.processingStatus)
        assertEquals(itemsBefore, graph.db.learning().allItems(userId).map { it.id }.toSet())
        assertTrue(graph.db.learning().allItems(userId).all { it.conceptId != null }, "cards are relinked to re-created concepts")
    }

    @Test fun `deleted chapter text is re-extracted without losing analysis`() = runTest {
        val id = graph.demoBook()
        val conceptsBefore = graph.db.concepts().forBook(id).map { it.id }.toSet()
        graph.files.resolve(graph.db.chapters().forBook(id).first().textFile).delete()
        graph.processor.process(id)
        assertTrue(graph.files.resolve(graph.db.chapters().forBook(id).first().textFile).exists())
        assertEquals(conceptsBefore, graph.db.concepts().forBook(id).map { it.id }.toSet())
    }

    @Test fun `importing the same book twice is detected`() = runTest {
        val id = graph.library.addDemoBook()
        assertEquals(id, graph.library.addDemoBook(), "demo import returns the existing book")
        val text = graph.context.assets.open("demo/ogrenmenin_bilimi.md").bufferedReader().readText()
        assertFailsWith<DuplicateBookException> { graph.library.importText("Başka ad", null, text, markdown = true) }
    }

    @Test fun `file import validates type, size and content`() = runTest {
        val dir = File(graph.context.cacheDir, "in").apply { mkdirs() }
        val txt = File(dir, "kitap.txt").apply { writeText("Bölüm 1\n\n" + "Bu bir cümledir ve öğrenme hakkındadır. ".repeat(80) + "\n\nBölüm 2\n\n" + "İkinci bölümde tekrar anlatılır. ".repeat(80)) }
        val bookId = graph.library.importFile(Uri.fromFile(txt))
        graph.processor.process(bookId)
        assertEquals(2, graph.db.chapters().forBook(bookId).size)

        val exe = File(dir, "virus.txt").apply { writeBytes(byteArrayOf(0x4D, 0x5A, 0x00, 0x00, 0x01)) }
        assertEquals(ImportException.Reason.UNSUPPORTED, assertFailsWith<ImportException> { graph.library.importFile(Uri.fromFile(exe)) }.reason)
        val empty = File(dir, "bos.txt").apply { writeBytes(ByteArray(0)) }
        assertEquals(ImportException.Reason.EMPTY, assertFailsWith<ImportException> { graph.library.importFile(Uri.fromFile(empty)) }.reason)
        val zip = File(dir, "x.zip").apply { ZipOutputStream(outputStream()).use { it.putNextEntry(ZipEntry("a")); it.write(1) } }
        assertEquals(ImportException.Reason.UNSUPPORTED, assertFailsWith<ImportException> { graph.library.importFile(Uri.fromFile(zip)) }.reason)
        // Rejected imports leave nothing behind.
        assertEquals(1, File(graph.context.filesDir, "books").listFiles()!!.size)
    }

    @Test fun `epub import goes through the same pipeline`() = runTest {
        val f = File(graph.context.cacheDir, "kitap.epub")
        ZipOutputStream(f.outputStream()).use { zip ->
            fun put(name: String, s: String) { zip.putNextEntry(ZipEntry(name)); zip.write(s.toByteArray()); zip.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="c.opf"/></rootfiles></container>""")
            put("c.opf", """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>EPUB Kitap</dc:title><dc:creator>Yazar</dc:creator></metadata>
                <manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="a"/><itemref idref="b"/></spine></package>""")
            val body = "<p>" + "Enflasyon, fiyatlar genel düzeyinin sürekli artmasıdır. Faiz oranları enflasyonu etkiler. ".repeat(20) + "</p>"
            put("a.xhtml", "<html><body><h1>Para</h1>$body</body></html>")
            put("b.xhtml", "<html><body><h1>Faiz</h1>$body</body></html>")
        }
        val id = graph.library.importFile(Uri.fromFile(f))
        graph.processor.process(id)
        val book = graph.db.books().get(id)!!
        assertEquals(SourceType.EPUB, book.sourceType)
        assertEquals("Yazar", book.author)
        assertEquals(listOf("Para", "Faiz"), graph.db.chapters().forBook(id).map { it.title })
        assertTrue(graph.db.concepts().forBook(id).any { it.title.contains("Enflasyon", ignoreCase = true) })
    }

    @Test fun `AI failures fall back to the local engine and are labelled`() = runTest {
        graph.close()
        val mock = MockAiProvider(script = { throw AiException.Timeout() })
        graph = TestGraph(provider = mock)
        val id = graph.demoBook()
        assertEquals(ProcessingStatus.READY, graph.db.books().get(id)!!.processingStatus)
        val generatedBy = graph.db.chapters().forBook(id).first().generatedBy!!
        assertTrue(generatedBy.contains("\"aiGenerated\":false"), generatedBy)
    }

    @Test fun `mock AI provider exercises the AI path end to end`() = runTest {
        graph.close()
        graph = TestGraph(provider = MockAiProvider())
        val id = graph.demoBook()
        assertTrue(graph.db.chapters().forBook(id).first().generatedBy!!.contains("\"aiGenerated\":true"))
        assertTrue(graph.db.concepts().forBook(id).isNotEmpty())
    }

    @Test fun `deleting a book removes files and derived data and leaves a tombstone`() = runTest {
        val id = graph.demoBook()
        graph.library.delete(id)
        assertEquals(false, File(graph.context.filesDir, "books/$id").exists())
        assertEquals(0, graph.db.chapters().forBook(id).size)
        assertEquals(0, graph.db.learning().allItems(graph.users.userId()).size)
        assertTrue(graph.db.books().get(id)!!.deletedAt != null)
        assertTrue(graph.db.books().all(graph.users.userId()).isEmpty())
    }
}
