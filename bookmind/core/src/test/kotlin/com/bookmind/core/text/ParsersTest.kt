package com.bookmind.core.text

import com.bookmind.core.TestFixtures
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsersTest {
    private fun para(n: Int, word: String = "kelime") = (1..n).joinToString(" ") { "$word$it" } + "."

    @Test fun `markdown h2 headings become chapters and the single h1 is the title`() {
        val book = MarkdownParser.parse(TestFixtures.demoBookMarkdown)
        assertEquals("Öğrenmenin Bilimi", book.title)
        assertEquals(listOf("Unutma Eğrisi", "Aralıklı Tekrar", "Aktif Hatırlama", "Bilişsel Yük", "Transfer"), book.chapters.map { it.title })
        assertEquals("tr", book.language)
        assertFalse(book.syntheticChapters)
        assertTrue(book.chapters[0].text.startsWith("Unutma eğrisi, öğrenilen"))
        assertFalse(book.chapters.any { it.text.contains("**") || it.text.contains("## ") })
    }

    @Test fun `markdown inline formatting is stripped`() {
        assertEquals("bold and italic link code", MarkdownParser.stripInline("**bold** and *italic* [link](http://x) `code`"))
    }

    @Test fun `plain text chapters are detected in Turkish and English`() {
        val text = buildString {
            append("İÇİNDEKİLER\n\nBölüm 1\nBölüm 2\nBölüm 3\n\n")
            append("Bölüm 1\nBaşlangıç\n\n${para(300)}\n\n")
            append("2. Bölüm: Orta\n\n${para(300)}\n\n")
            append("CHAPTER III\n\n${para(300)}\n")
        }
        val book = PlainTextParser.parse(text)
        assertEquals(3, book.chapters.size, book.chapters.map { it.title }.toString())
        assertEquals("Bölüm 1 – Başlangıç", book.chapters[0].title)
        assertTrue(book.chapters[1].title.startsWith("2. Bölüm"))
        assertEquals("CHAPTER III", book.chapters[2].title)
        assertFalse(book.syntheticChapters)
    }

    @Test fun `text without headings is split into synthetic sections on paragraph boundaries`() {
        val text = (1..40).joinToString("\n\n") { para(80, "p$it-") }
        val book = PlainTextParser.parse(text)
        assertTrue(book.syntheticChapters)
        assertTrue(book.chapters.size >= 2)
        assertEquals("Kısım 1", book.chapters[0].title)
        assertTrue(book.warnings.isNotEmpty())
        assertEquals(TextNormalizer.wordCount(text), book.chapters.sumOf { TextNormalizer.wordCount(it.text) })
    }

    @Test fun `pdf outline defines chapters with page ranges`() {
        val pages = (1..10).map { "Sayfa $it içeriği. " + para(60) }
        val book = PdfStructureBuilder.build(pages, listOf(PdfStructureBuilder.OutlineEntry("Giriş", 0), PdfStructureBuilder.OutlineEntry("İkinci", 4)))
        assertEquals(2, book.chapters.size)
        assertEquals(1, book.chapters[0].pageStart); assertEquals(4, book.chapters[0].pageEnd)
        assertEquals(5, book.chapters[1].pageStart); assertEquals(10, book.chapters[1].pageEnd)
        assertFalse(book.needsOcr)
    }

    @Test fun `pdf without outline uses heading pages`() {
        val pages = (1..6).map { i -> if (i == 1 || i == 4) "Bölüm ${if (i == 1) 1 else 2}\n\n" + para(80) else para(80) }
        val book = PdfStructureBuilder.build(pages, emptyList())
        assertEquals(listOf("Bölüm 1", "Bölüm 2"), book.chapters.map { it.title })
        assertEquals(4, book.chapters[1].pageStart)
    }

    @Test fun `scanned pdf is flagged for OCR`() {
        val book = PdfStructureBuilder.build(List(12) { " \n 3 " }, emptyList())
        assertTrue(book.needsOcr)
        assertTrue(book.chapters.isEmpty())
    }

    @Test fun `pdf line unwrapping joins hyphenated words`() {
        val out = TextNormalizer.unwrapPdfLines("Öğren-\nme süreci uzun\nbir yolculuktur.")
        assertEquals("Öğrenme süreci uzun bir yolculuktur.", out)
    }

    @Test fun `running headers repeated on most pages are removed`() {
        val pages = (1..8).map { "KİTAP ADI\n${para(40)}\n$it" }
        val book = PdfStructureBuilder.build(pages, emptyList())
        assertFalse(book.chapters.any { it.text.contains("KİTAP ADI") })
    }

    @Test fun `sentence splitter keeps abbreviations and decimals together`() {
        val s = Sentences.split("Dr. Ayşe geldi. Oran 3.5 oldu! Peki ya J. R. R. Tolkien? Son.")
        assertEquals(listOf("Dr. Ayşe geldi.", "Oran 3.5 oldu!", "Peki ya J. R. R. Tolkien?", "Son."), s.map { it.text })
        val text = "Bir. İki."
        val second = Sentences.split(text)[1]
        assertEquals("İki.", text.substring(second.start, second.end))
    }

    @Test fun `chunker respects size, overlap and offsets`() {
        val text = (1..200).joinToString(" ") { "Cümle numarası $it burada bitiyor." }
        val chunks = Chunker.chunk(0, text, maxChars = 1_000, overlapChars = 100)
        assertTrue(chunks.size > 5)
        assertTrue(chunks.all { it.text.length <= 1_000 })
        chunks.zipWithNext().forEach { (a, b) -> assertTrue(b.charStart < a.charEnd, "chunks overlap") }
        assertEquals(text.length, chunks.last().charEnd)
        assertEquals(listOf(0), Chunker.chunk(0, "kısa", 1_000, 100).map { it.index })
    }

    @Test fun `file type sniffing trusts magic bytes over extensions`() {
        assertEquals(SourceType.PDF, FileTypeSniffer.detect("kitap.txt", "%PDF-1.7".toByteArray()))
        assertEquals(SourceType.EPUB, FileTypeSniffer.detect("kitap.epub", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2)))
        assertNull(FileTypeSniffer.detect("arsiv.zip", byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2)))
        assertEquals(SourceType.MARKDOWN, FileTypeSniffer.detect("notlar.md", "# Başlık".toByteArray()))
        assertEquals(SourceType.TXT, FileTypeSniffer.detect("kitap.txt", "Merhaba".toByteArray()))
        assertNull(FileTypeSniffer.detect("resim.txt", byteArrayOf(0x00, 0x01, 0x02)))
        assertNull(FileTypeSniffer.detect("program.exe", "MZ".toByteArray()))
    }

    @Test fun `text decoding handles utf8 bom and windows-1254`() {
        assertEquals("Şeker", FileTypeSniffer.decodeText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Şeker".toByteArray()))
        assertEquals("Işık ğüş", FileTypeSniffer.decodeText("Işık ğüş".toByteArray(charset("windows-1254"))))
    }

    @Test fun `turkish case folding and ascii folding`() {
        assertEquals("ışık", TextNormalizer.foldCase("IŞIK"))
        assertEquals("istanbul", TextNormalizer.foldCase("İSTANBUL"))
        assertEquals("ogrenme", TextNormalizer.asciiFold("Öğrenme"))
    }

    @Test fun `language detection`() {
        assertEquals("tr", LanguageDetector.detect(TestFixtures.demoBookMarkdown))
        assertEquals("en", LanguageDetector.detect("The cat sat on the mat and it was happy. ".repeat(20)))
        assertNull(LanguageDetector.detect("çok kısa"))
    }

    @Test fun `checksum is stable`() {
        assertEquals(Checksums.sha256("abc"), Checksums.sha256("abc".byteInputStream()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Checksums.sha256("abc"))
    }

    @Test fun `source ref label`() {
        assertEquals("Bölüm 3 – Transfer, s. 12–14", SourceRef(2, "Transfer", 12, 14).label())
        assertNotNull(SourceRef(0).label())
    }
}
