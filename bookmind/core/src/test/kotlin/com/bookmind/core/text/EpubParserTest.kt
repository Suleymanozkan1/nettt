package com.bookmind.core.text

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EpubParserTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun epub(entries: Map<String, String>): File {
        val f = tmp.newFile("book.epub")
        ZipOutputStream(f.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype")); zip.write("application/epub+zip".toByteArray()); zip.closeEntry()
            entries.forEach { (name, content) -> zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() }
        }
        return f
    }

    private val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
        <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""

    private fun opf(withNav: Boolean) = """<?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
          <metadata><dc:title>Deneme Kitabı</dc:title><dc:creator>Ada Yazar</dc:creator><dc:language>tr-TR</dc:language></metadata>
          <manifest>
            ${if (withNav) "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" else ""}
            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            <item id="cover" href="text/cover.xhtml" media-type="application/xhtml+xml"/>
            <item id="c1" href="text/ch%201.xhtml" media-type="application/xhtml+xml"/>
            <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
            <item id="img" href="img/a.png" media-type="image/png"/>
          </manifest>
          <spine toc="ncx"><itemref idref="cover"/><itemref idref="c1"/><itemref idref="c2"/></spine>
        </package>"""

    private fun chapter(h: String, words: Int) =
        "<html><head><title>$h</title><style>p{}</style></head><body><h1>$h</h1>" +
            "<p>${(1..words).joinToString(" ") { "kelime$it" }}.</p><ul><li><p>Liste öğesi içinde paragraf.</p></li></ul><script>alert(1)</script></body></html>"

    @Test fun `parses metadata, spine order and nav titles`() {
        val f = epub(
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf(withNav = true),
                "OEBPS/nav.xhtml" to """<html xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol>
                    <li><a href="text/ch%201.xhtml">Birinci Bölüm</a></li><li><a href="text/ch2.xhtml#start">İkinci Bölüm</a></li></ol></nav></body></html>""",
                "OEBPS/text/cover.xhtml" to "<html><body><p>Kapak</p></body></html>",
                "OEBPS/text/ch 1.xhtml" to chapter("H1 Başlık", 80),
                "OEBPS/text/ch2.xhtml" to chapter("H2 Başlık", 80),
            ),
        )
        val book = EpubParser.parse(f)
        assertEquals("Deneme Kitabı", book.title)
        assertEquals("Ada Yazar", book.author)
        assertEquals("tr", book.language)
        assertEquals(listOf("Birinci Bölüm", "İkinci Bölüm"), book.chapters.map { it.title })
        val text = book.chapters[0].text
        assertTrue(text.contains("kelime80"))
        assertTrue(!text.contains("alert"))
        assertEquals(1, Regex("Liste öğesi").findAll(text).count(), "nested <p> inside <li> must not be duplicated")
    }

    @Test fun `falls back to ncx and then to headings`() {
        val f = epub(
            mapOf(
                "META-INF/container.xml" to container,
                "OEBPS/content.opf" to opf(withNav = false),
                "OEBPS/toc.ncx" to """<ncx><navMap><navPoint><navLabel><text>NCX Bir</text></navLabel><content src="text/ch%201.xhtml"/></navPoint></navMap></ncx>""",
                "OEBPS/text/cover.xhtml" to "<html><body></body></html>",
                "OEBPS/text/ch 1.xhtml" to chapter("H1", 80),
                "OEBPS/text/ch2.xhtml" to chapter("Başlıktan Gelen", 80),
            ),
        )
        assertEquals(listOf("NCX Bir", "Başlıktan Gelen"), EpubParser.parse(f).chapters.map { it.title })
    }

    @Test fun `drm protected books are rejected`() {
        val f = epub(
            mapOf(
                "META-INF/container.xml" to container,
                "META-INF/encryption.xml" to """<encryption><EncryptedData><EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/></EncryptedData></encryption>""",
                "OEBPS/content.opf" to opf(true),
            ),
        )
        assertFailsWith<EpubParser.EpubException> { EpubParser.parse(f) }
    }

    @Test fun `missing container is a clear error`() {
        assertFailsWith<EpubParser.EpubException> { EpubParser.parse(epub(mapOf("x.txt" to "y"))) }
    }

    @Test fun `path resolution normalises dot segments and stays inside the archive`() {
        assertEquals("OEBPS/text/a.xhtml", EpubParser.resolve("OEBPS", "text/./a.xhtml"))
        assertEquals("OEBPS/a.xhtml", EpubParser.resolve("OEBPS/text", "../a.xhtml"))
        assertEquals("etc/passwd", EpubParser.resolve("", "../../etc/passwd"))
    }
}
