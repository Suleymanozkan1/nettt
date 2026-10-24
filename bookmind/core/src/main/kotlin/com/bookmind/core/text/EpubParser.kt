package com.bookmind.core.text

import com.bookmind.core.i18n.CoreText

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * EPUB 2/3 reader. Reads container.xml → OPF (metadata, manifest, spine) → navigation (nav.xhtml or toc.ncx)
 * and turns each spine document into text. Uses [ZipFile] so large books are read entry by entry instead of
 * being loaded into memory at once. DRM-protected books are reported, not decrypted.
 */
object EpubParser {
    /** Single XHTML entries above this size are rejected (zip bomb / malformed file protection). */
    const val MAX_ENTRY_BYTES = 20L * 1024 * 1024

    class EpubException(message: String) : IOException(message)

    fun parse(file: File, lang: String? = null): ExtractedBook = ZipFile(file).use { zip ->
        val t = CoreText.of(lang)
        if (zip.getEntry("META-INF/encryption.xml") != null && hasEncryptedContent(zip)) {
            throw EpubException("EPUB is DRM-protected")
        }
        val containerXml = zip.readText("META-INF/container.xml") ?: throw EpubException("container.xml not found")
        val opfPath = Jsoup.parse(containerXml, "", Parser.xmlParser()).selectFirst("rootfile")?.attr("full-path")
            ?.takeIf { it.isNotBlank() } ?: throw EpubException("OPF path not found")
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = Jsoup.parse(zip.readText(opfPath) ?: throw EpubException("OPF unreadable"), "", Parser.xmlParser())

        val title = opf.selectFirst("metadata > dc|title, metadata > title")?.text()?.trim()?.ifBlank { null }
        val author = opf.select("metadata > dc|creator, metadata > creator").joinToString(", ") { it.text().trim() }.ifBlank { null }
        val language = opf.selectFirst("metadata > dc|language, metadata > language")?.text()?.trim()?.take(2)?.lowercase()

        val manifest = opf.select("manifest > item").associate { it.attr("id") to (it.attr("href") to it.attr("media-type")) }
        val navHref = opf.select("manifest > item").firstOrNull { it.attr("properties").split(' ').contains("nav") }?.attr("href")
        val ncxId = opf.selectFirst("spine")?.attr("toc")
        val tocTitles = readToc(zip, opfDir, navHref, ncxId?.let { manifest[it]?.first })
        // Documents the book itself marks as contents, index or bibliography (EPUB 2 guide, EPUB 3 landmarks).
        val backMatter = backMatterDocuments(zip, opf, opfDir, navHref) + listOfNotNull(navHref?.let { resolve(opfDir, it) })

        val chapters = mutableListOf<ExtractedChapter>()
        val warnings = mutableListOf<String>()
        for (itemref in opf.select("spine > itemref")) {
            if (itemref.attr("linear") == "no") continue
            val (href, mediaType) = manifest[itemref.attr("idref")] ?: continue
            if (!mediaType.contains("html")) continue
            val path = resolve(opfDir, href)
            if (path in backMatter) continue
            val html = zip.readText(path) ?: run { warnings += t.epubMissing(href); null } ?: continue
            val doc = Jsoup.parse(html)
            val text = htmlToText(doc)
            if (TextNormalizer.wordCount(text) < 40) continue // cover, copyright, blank pages
            val chapterTitle = tocTitles[path] ?: doc.selectFirst("h1, h2, h3")?.text()?.trim()?.take(120)?.ifBlank { null }
                ?: doc.title().trim().ifBlank { null }
                ?: t.chapter(chapters.size + 1)
            chapters += ExtractedChapter(chapterTitle, text, startLocation = href.substringAfterLast('/'))
        }
        if (chapters.isEmpty()) warnings += t.epubNoText
        BackMatterFilter.clean(ExtractedBook(title, author, language ?: LanguageDetector.detect(chapters.joinToString(" ") { it.text }), chapters, warnings), lang)
    }

    private val backMatterTypes = setOf("toc", "index", "bibliography")

    private fun backMatterDocuments(zip: ZipFile, opf: Document, opfDir: String, navHref: String?): Set<String> {
        val paths = mutableSetOf<String>()
        opf.select("guide > reference").forEach { r ->
            if (r.attr("type").lowercase() in backMatterTypes) paths += resolve(opfDir, r.attr("href").substringBefore('#'))
        }
        if (navHref != null) {
            val navPath = resolve(opfDir, navHref)
            val navDir = navPath.substringBeforeLast('/', "")
            zip.readText(navPath)?.let { html ->
                Jsoup.parse(html).select("nav").filter { it.attr("epub:type") == "landmarks" }.flatMap { it.select("a[href]") }.forEach { a ->
                    if (a.attr("epub:type").split(' ').any { it.lowercase() in backMatterTypes }) paths += resolve(navDir, a.attr("href").substringBefore('#'))
                }
            }
        }
        return paths
    }

    private fun readToc(zip: ZipFile, opfDir: String, navHref: String?, ncxHref: String?): Map<String, String> {
        val titles = linkedMapOf<String, String>()
        if (navHref != null) {
            val navPath = resolve(opfDir, navHref)
            val navDir = navPath.substringBeforeLast('/', "")
            zip.readText(navPath)?.let { html ->
                val doc = Jsoup.parse(html)
                val toc = doc.select("nav").firstOrNull { it.attr("epub:type") == "toc" || it.id() == "toc" } ?: doc.selectFirst("nav")
                toc?.select("a[href]")?.forEach { a ->
                    val target = resolve(navDir, a.attr("href").substringBefore('#'))
                    if (target !in titles && a.text().isNotBlank()) titles[target] = a.text().trim()
                }
            }
        }
        if (titles.isEmpty() && ncxHref != null) {
            val ncxPath = resolve(opfDir, ncxHref)
            val ncxDir = ncxPath.substringBeforeLast('/', "")
            zip.readText(ncxPath)?.let { xml ->
                Jsoup.parse(xml, "", Parser.xmlParser()).select("navPoint").forEach { np ->
                    val label = np.selectFirst("navLabel > text")?.text()?.trim().orEmpty()
                    val src = np.selectFirst("content")?.attr("src")?.substringBefore('#') ?: return@forEach
                    val target = resolve(ncxDir, src)
                    if (target !in titles && label.isNotBlank()) titles[target] = label
                }
            }
        }
        return titles
    }

    fun htmlToText(doc: Document): String {
        doc.select("script, style, nav[epub:type=toc], aside[epub:type=footnote]").remove()
        val sb = StringBuilder()
        for (el in doc.body()?.select("h1, h2, h3, h4, h5, h6, p, li, blockquote, pre, td, dt, dd") ?: emptyList()) {
            // Skip elements whose text is already covered by a matched ancestor (e.g. <p> inside <li>).
            if (el.parents().any { it.normalName() in setOf("p", "li", "blockquote", "pre", "td", "dd") }) continue
            val t = el.wholeText().replace(Regex("\\s+"), " ").trim()
            if (t.isNotEmpty()) sb.append(t).append("\n\n")
        }
        if (sb.isBlank()) sb.append(doc.body()?.text().orEmpty())
        return TextNormalizer.normalize(sb.toString())
    }

    private fun hasEncryptedContent(zip: ZipFile): Boolean {
        val xml = zip.readText("META-INF/encryption.xml") ?: return false
        // Font obfuscation (IDPF/Adobe) is allowed; anything else means encrypted content documents.
        return Jsoup.parse(xml, "", Parser.xmlParser()).select("EncryptionMethod").any {
            val alg = it.attr("Algorithm")
            !alg.contains("obfuscation", ignoreCase = true) && !alg.endsWith("#font")
        }
    }

    internal fun resolve(base: String, href: String): String {
        val decoded = URLDecoder.decode(href, "UTF-8")
        val parts = (if (base.isEmpty()) decoded else "$base/$decoded").split('/')
        val stack = ArrayDeque<String>()
        for (p in parts) when (p) {
            "", "." -> Unit
            ".." -> stack.removeLastOrNull()
            else -> stack.addLast(p)
        }
        return stack.joinToString("/")
    }

    private fun ZipFile.readText(name: String): String? {
        val entry = getEntry(name) ?: return null
        if (entry.size > MAX_ENTRY_BYTES) throw EpubException("EPUB entry too large: $name")
        return getInputStream(entry).use { input ->
            val bytes = input.readNBytesCompat(MAX_ENTRY_BYTES + 1)
            if (bytes.size > MAX_ENTRY_BYTES) throw EpubException("EPUB entry too large: $name")
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            if (total > limit) break
        }
        return out.toByteArray()
    }
}
