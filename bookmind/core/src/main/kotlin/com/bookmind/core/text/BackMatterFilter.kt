package com.bookmind.core.text

/**
 * Finds the parts of a book that are not reading text: the table of contents, the index and the bibliography.
 * They would only waste summary length (and API tokens) on page numbers and references. Detection uses the
 * section title when there is one ("İçindekiler", "Dizin", "Kaynakça", "Contents", "Index", "References" …) and
 * otherwise the shape of the lines (most lines end in page numbers, or look like literature references).
 * Shape-only detection is limited to the front or back of the book, where these sections live, so a history
 * chapter full of years in the middle of a book is never mistaken for a bibliography.
 */
object BackMatterFilter {
    enum class Kind { CONTENTS, INDEX, BIBLIOGRAPHY }

    private val titles = mapOf(
        Kind.CONTENTS to Regex("^(içindekiler|içerik|contents|table of contents)$"),
        Kind.INDEX to Regex("^((kavram|kişi|ad|konu|genel|özel ad(lar)?)\\s+)?(dizin(i)?|index|indeks|subject index|name index)$"),
        Kind.BIBLIOGRAPHY to Regex("^(kaynakça|kaynaklar|bibliyografya|yararlanılan kaynaklar|başvuru kaynakları|bibliography|references|works cited|selected bibliography|further reading)$"),
    )

    /** "Bölüm 3 ...... 45", "Giriş 7", "3. Hafıza 112" */
    private val tocLine = Regex("^.{2,120}?[\\s.·…_-]{1,}\\(?\\d{1,4}\\)?$")
    /** "hafıza, 12, 45–47" / "Ebbinghaus, Hermann 23" */
    private val indexLine = Regex("^[^\\d]{2,70}?[,;:]?\\s+\\d{1,4}([\\s,;–-]+\\d{1,4})*(\\s*(vd|ff?|n)\\.?)?$")
    /** "Ebbinghaus, H. (1885). Über das Gedächtnis." / "Smith J, 2010, ..." */
    private val referenceLine = Regex("^[\\p{Lu}][\\p{L}'’-]+,\\s*(\\p{Lu}\\.|\\p{Lu}[\\p{L}]+).{0,200}?((19|20)\\d{2}[a-z]?)")

    fun titleKind(title: String): Kind? {
        val raw = title.trim().trimEnd('.', ':').replace(Regex("\\s+"), " ")
        // Turkish folding turns "INDEX" into "ındex", so the plain lower case is tried as well.
        val forms = setOf(TextNormalizer.foldCase(raw), raw.lowercase())
        return titles.entries.firstOrNull { e -> forms.any { e.value.matches(it) } }?.key
    }

    /** Share of the non-empty lines of [text] that have the line shape of [kind]. */
    private fun share(kind: Kind, lines: List<String>): Double {
        if (lines.isEmpty()) return 0.0
        val test: (String) -> Boolean = when (kind) {
            // A reference starts with "Surname, I." and has a year somewhere after it.
            Kind.BIBLIOGRAPHY -> { l -> referenceLine.containsMatchIn(l) }
            Kind.INDEX -> { l -> indexLine.matches(l) }
            Kind.CONTENTS -> { l -> tocLine.matches(l) }
        }
        return lines.count(test).toDouble() / lines.size
    }

    private fun lines(text: String) = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Classifies a block of text (a PDF page or a short EPUB document) by the share of lines with the typical shape. */
    fun shapeKind(text: String): Kind? {
        val lines = lines(text)
        if (lines.size < 6) return null
        // A heading at the top decides on its own.
        lines.take(2).firstNotNullOfOrNull { titleKind(it) }?.let { return it }
        return when {
            share(Kind.BIBLIOGRAPHY, lines) >= 0.5 -> Kind.BIBLIOGRAPHY
            share(Kind.INDEX, lines) >= 0.6 && lines.average { it.length } < 60 -> Kind.INDEX
            share(Kind.CONTENTS, lines) >= 0.6 -> Kind.CONTENTS
            else -> null
        }
    }

    private fun List<String>.average(f: (String) -> Int) = if (isEmpty()) 0.0 else sumOf(f).toDouble() / size

    /**
     * Page numbers (0-based) of a PDF to leave out. Shape-based contents pages are only looked for in the first 20 %
     * of the book (or last 10 %, where Turkish books often put the contents), index and bibliography pages only in
     * the last 30 %. A page that starts such a section by title also removes the pages that continue it.
     */
    fun pdfPagesToSkip(pages: List<String>): Map<Int, Kind> {
        val n = pages.size
        if (n < 8) return emptyMap()
        val skip = linkedMapOf<Int, Kind>()
        var running: Kind? = null
        for ((i, page) in pages.withIndex()) {
            val front = i < n * 0.2
            val back = i >= n * 0.7
            val kind = shapeKind(page)
            val startsSection = page.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(2).firstNotNullOfOrNull { titleKind(it) }
            val accepted = when (kind) {
                Kind.CONTENTS -> if (front || i >= n * 0.9 || startsSection != null) kind else null
                Kind.INDEX, Kind.BIBLIOGRAPHY -> if (back || startsSection != null && i >= n * 0.5) kind else null
                null -> null
            }
            // A continuation page of the section before it still has a good share of its typical lines.
            val continued = running != null && kind == null && startsSection == null && (back || running == Kind.CONTENTS && front) &&
                ChapterDetector.findHeadings(page).isEmpty() && share(running!!, lines(page)) >= 0.3
            when {
                accepted != null -> { skip[i] = accepted; running = accepted }
                continued -> skip[i] = running!!
                else -> running = null
            }
        }
        return skip
    }

    /** True when a chapter (from headings, the PDF outline or the EPUB spine) is a contents, index or bibliography section. */
    fun isBackMatter(chapter: ExtractedChapter): Boolean =
        titleKind(chapter.title) != null || (TextNormalizer.wordCount(chapter.text) < 6_000 && shapeKind(chapter.text) != null)

    /** Drops contents, index and bibliography chapters (never all chapters) and says so in the warnings. */
    fun clean(book: ExtractedBook, lang: String?): ExtractedBook {
        val kept = book.chapters.filterNot(::isBackMatter)
        if (kept.size == book.chapters.size || kept.isEmpty()) return book
        return book.copy(chapters = kept, warnings = book.warnings + com.bookmind.core.i18n.CoreText.of(lang).backMatterChapters(book.chapters.size - kept.size))
    }
}
