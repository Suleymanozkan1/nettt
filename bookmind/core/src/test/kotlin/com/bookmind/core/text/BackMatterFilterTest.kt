package com.bookmind.core.text

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Contents, index and bibliography removal and word-sized PDF sections. */
class BackMatterFilterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val vocab = "öğrenme hafıza tekrar zaman bilgi zihin dikkat anlam bağlam deneme örnek kavram fikir yöntem soru cevap".split(" ")

    /** Prose lines whose words differ from page to page (identical first lines would count as running headers). */
    private fun prose(words: Int, seed: String = "metin") =
        (1..words).chunked(14).joinToString("\n") { line -> line.joinToString(" ") { vocab[(it * 7 + seed.hashCode().mod(97)) % vocab.size] } + "." }

    private val contents = "İçindekiler\n" + (1..12).joinToString("\n") { "Bölüm $it Hafıza ve öğrenme .......... ${it * 12}" }
    private val index = "Dizin\n" + listOf("akıcılık yanılsaması, 45, 67", "aralıklı tekrar, 12–19, 88", "Ebbinghaus, Hermann 3, 5", "hafıza, 23",
        "kararlılık, 34, 56", "test etkisi, 71–73", "unutma eğrisi, 2, 9, 14", "zihinsel yük, 101").joinToString("\n")
    private val bibliography = "Kaynakça\n" + listOf(
        "Ebbinghaus, H. (1885). Über das Gedächtnis. Leipzig: Duncker & Humblot.",
        "Roediger, H. L., & Karpicke, J. D. (2006). Test-enhanced learning. Psychological Science, 17(3), 249–255.",
        "Cepeda, N. J. (2006). Distributed practice in verbal recall tasks. Psychological Bulletin.",
        "Bjork, R. A. (1994). Memory and metamemory considerations. MIT Press.",
        "Sweller, J. (1988). Cognitive load during problem solving. Cognitive Science, 12, 257–285.",
        "Kornell, N. (2009). Optimising learning using flashcards. Applied Cognitive Psychology.",
        "Dunlosky, J. (2013). Improving students' learning. Psychological Science in the Public Interest.",
    ).joinToString("\n")
    private val history = "Tarih\n" + (1..10).joinToString("\n") { "Savaş $it, 19${10 + it} yılında başladı ve uzun yıllar sürdü; şehirler yeniden kuruldu." }

    @Test fun `titles in Turkish and English are recognised`() {
        assertEquals(BackMatterFilter.Kind.CONTENTS, BackMatterFilter.titleKind("İÇİNDEKİLER"))
        assertEquals(BackMatterFilter.Kind.CONTENTS, BackMatterFilter.titleKind("Table of Contents"))
        assertEquals(BackMatterFilter.Kind.INDEX, BackMatterFilter.titleKind("Kavram Dizini"))
        assertEquals(BackMatterFilter.Kind.INDEX, BackMatterFilter.titleKind("Index"))
        assertEquals(BackMatterFilter.Kind.BIBLIOGRAPHY, BackMatterFilter.titleKind("Kaynakça"))
        assertEquals(BackMatterFilter.Kind.BIBLIOGRAPHY, BackMatterFilter.titleKind("References"))
        assertEquals(null, BackMatterFilter.titleKind("Bölüm 3 – Kaynakların yönetimi"))
    }

    @Test fun `pdf contents, index and bibliography pages are dropped, prose is kept`() {
        val pages = listOf("Öğrenmenin Bilimi\nAda Yazar", contents) +
            (1..20).map { prose(300, "s$it") } + listOf(history) + (1..3).map { prose(300, "t$it") } +
            listOf(bibliography, bibliography.replace("Kaynakça\n", ""), index)
        val skipped = BackMatterFilter.pdfPagesToSkip(pages)
        assertEquals(mapOf(1 to BackMatterFilter.Kind.CONTENTS, 26 to BackMatterFilter.Kind.BIBLIOGRAPHY,
            27 to BackMatterFilter.Kind.BIBLIOGRAPHY, 28 to BackMatterFilter.Kind.INDEX), skipped)
        val book = PdfStructureBuilder.build(pages, emptyList())
        val all = book.chapters.joinToString("\n") { it.text }
        assertFalse(all.contains("İçindekiler") || all.contains("Kaynakça") || all.contains("Roediger") || all.contains("akıcılık yanılsaması, 45"))
        assertTrue(TextNormalizer.wordCount(all) >= 23 * 300, "all 23 prose pages are kept")
        assertTrue(all.contains("Savaş 3"), "a history page with years in the middle of the book is kept")
        assertTrue(book.warnings.any { it.contains("4 sayfa") }, "${book.warnings}")
    }

    @Test fun `a pdf without headings is split into sections of 5000 to 8000 words`() {
        val pages = (1..120).map { prose(320, "p$it") } // 38,400 words
        val book = PdfStructureBuilder.build(pages, emptyList())
        assertTrue(book.syntheticChapters)
        val sizes = book.chapters.map { TextNormalizer.wordCount(it.text) }
        assertTrue(sizes.all { it in 5_000..8_000 }, "$sizes")
        assertEquals(1, book.chapters.first().pageStart)
        assertEquals(120, book.chapters.last().pageEnd)
        assertEquals(38_400, sizes.sum())
    }

    @Test fun `an outline chapter named bibliography is dropped`() {
        val pages = (1..12).map { prose(200, "q$it") }
        val book = PdfStructureBuilder.build(pages, listOf(
            PdfStructureBuilder.OutlineEntry("Giriş", 0), PdfStructureBuilder.OutlineEntry("Ana Bölüm", 3), PdfStructureBuilder.OutlineEntry("Kaynaklar", 10),
        ))
        assertEquals(listOf("Giriş", "Ana Bölüm"), book.chapters.map { it.title })
    }

    @Test fun `markdown sections called contents or references are dropped`() {
        val md = "# Kitap\n\n## İçindekiler\n\n- Giriş\n- Sonuç\n\n## Giriş\n\n${prose(200)}\n\n## Sonuç\n\n${prose(200)}\n\n## References\n\n$bibliography"
        assertEquals(listOf("Giriş", "Sonuç"), MarkdownParser.parse(md).chapters.map { it.title })
    }

    @Test fun `epub documents marked as toc, index or bibliography are skipped`() {
        val f = tmp.newFile("b.epub")
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
            <metadata><dc:title>K</dc:title><dc:language>tr</dc:language></metadata>
            <manifest>
              <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
              <item id="toc" href="toc.xhtml" media-type="application/xhtml+xml"/>
              <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
              <item id="ref" href="refs.xhtml" media-type="application/xhtml+xml"/>
              <item id="idx" href="idx.xhtml" media-type="application/xhtml+xml"/>
            </manifest>
            <spine><itemref idref="nav"/><itemref idref="toc"/><itemref idref="c1"/><itemref idref="ref"/><itemref idref="idx"/></spine>
            <guide><reference type="toc" href="toc.xhtml"/></guide></package>"""
        fun doc(h: String, body: String) = "<html><body><h1>$h</h1>${body.lines().joinToString("") { "<p>$it</p>" }}</body></html>"
        ZipOutputStream(f.outputStream()).use { zip ->
            mapOf(
                "META-INF/container.xml" to container, "OEBPS/content.opf" to opf,
                "OEBPS/nav.xhtml" to """<html xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="c1.xhtml">Birinci</a></li></ol></nav>
                    <nav epub:type="landmarks"><ol><li><a epub:type="index" href="idx.xhtml">Dizin</a></li></ol></nav></body></html>""",
                "OEBPS/toc.xhtml" to doc("İçerik listesi", prose(60)),
                "OEBPS/c1.xhtml" to doc("Birinci", prose(300)),
                "OEBPS/refs.xhtml" to doc("Kaynakça", bibliography.removePrefix("Kaynakça\n")),
                "OEBPS/idx.xhtml" to doc("Sözlük", prose(80)),
            ).forEach { (n, c) -> zip.putNextEntry(ZipEntry(n)); zip.write(c.toByteArray()); zip.closeEntry() }
        }
        val book = EpubParser.parse(f)
        assertEquals(listOf("Birinci"), book.chapters.map { it.title })
    }
}
