package com.bookmind.core.text

import com.bookmind.core.i18n.CoreText

/**
 * Builds chapters from per-page PDF text. Uses the PDF outline (bookmarks) when present, otherwise heading
 * detection on page text, otherwise groups pages into sections of about [SECTION_WORDS] words. Running headers,
 * footers and page numbers are removed from every page, and the table of contents, index and bibliography are
 * left out (see [BackMatterFilter]). Page numbers are 1-based.
 */
object PdfStructureBuilder {
    data class OutlineEntry(val title: String, val pageIndex: Int)

    /** Minimum average characters per page below which the PDF is treated as scanned (needs OCR). */
    const val SCANNED_CHARS_PER_PAGE = 40

    /** Target size of a section when the PDF has no outline and no headings (sections end up 5,000–8,000 words). */
    const val SECTION_WORDS = 6_500

    fun build(pages: List<String>, outline: List<OutlineEntry>, sectionWords: Int = SECTION_WORDS, lang: String? = null): ExtractedBook {
        val t = CoreText.of(lang)
        val stripped = pages.map { stripRunningHeaders(it, pages) }
        // Contents/index/bibliography pages become empty; page indices stay valid for the outline.
        val skipped = BackMatterFilter.pdfPagesToSkip(stripped)
        val cleaned = stripped.mapIndexed { i, p -> if (i in skipped) "" else TextNormalizer.unwrapPdfLines(p) }
        val totalChars = stripped.sumOf { TextNormalizer.unwrapPdfLines(it).length }
        val needsOcr = pages.isNotEmpty() && totalChars / pages.size < SCANNED_CHARS_PER_PAGE
        if (needsOcr) {
            return ExtractedBook(null, null, null, emptyList(), listOf(t.pdfScanned), needsOcr = true)
        }
        val language = LanguageDetector.detect(cleaned.joinToString("\n"))
        val removed = if (skipped.isEmpty()) emptyList() else listOf(t.backMatterRemoved(skipped.size))

        val starts = outline
            .filter { it.pageIndex in pages.indices && it.title.isNotBlank() }
            .distinctBy { it.pageIndex }
            .sortedBy { it.pageIndex }
            .ifEmpty { detectHeadingPages(cleaned) }

        if (starts.size >= 2) {
            val chapters = mutableListOf<ExtractedChapter>()
            if (starts.first().pageIndex > 0) {
                val front = cleaned.subList(0, starts.first().pageIndex).joinToString("\n\n").trim()
                if (TextNormalizer.wordCount(front) >= 150) chapters += chapter(t.opening, cleaned, 0, starts.first().pageIndex - 1, t)
            }
            for ((i, s) in starts.withIndex()) {
                val endPage = (starts.getOrNull(i + 1)?.pageIndex ?: pages.size) - 1
                if (endPage < s.pageIndex) continue
                chapter(s.title.trim(), cleaned, s.pageIndex, endPage, t).takeIf { it.text.isNotBlank() }?.let { chapters += it }
            }
            val kept = chapters.filterNot(BackMatterFilter::isBackMatter)
            val dropped = if (kept.size < chapters.size) listOf(t.backMatterChapters(chapters.size - kept.size)) else emptyList()
            return ExtractedBook(null, null, language, kept, warnings = removed + dropped)
        }

        val chapters = wordSections(cleaned, sectionWords).map { range ->
            chapter(t.pages(range.first + 1, range.last + 1), cleaned, range.first, range.last, t)
        }.filter { it.text.isNotBlank() }
        return ExtractedBook(
            null, null, language, chapters,
            warnings = listOf(t.pdfNoOutline) + removed,
            syntheticChapters = true,
        )
    }

    /** Consecutive page ranges of about [target] words; the count is chosen so all ranges come out about equal. */
    internal fun wordSections(pages: List<String>, target: Int): List<IntRange> {
        val words = pages.map(TextNormalizer::wordCount)
        val count = Math.round(words.sum() / target.toDouble()).toInt().coerceAtLeast(1)
        val per = words.sum() / count
        val ranges = mutableListOf<IntRange>()
        var start = 0
        var sum = 0
        for ((i, w) in words.withIndex()) {
            sum += w
            if (sum >= per && ranges.size < count - 1) { ranges += start..i; start = i + 1; sum = 0 }
        }
        if (start < pages.size) ranges += start until pages.size
        return ranges
    }

    private fun chapter(title: String, pages: List<String>, from: Int, to: Int, t: CoreText) = ExtractedChapter(
        title = title,
        text = pages.subList(from, to + 1).joinToString("\n\n").trim(),
        startLocation = t.page(from + 1),
        endLocation = t.page(to + 1),
        pageStart = from + 1,
        pageEnd = to + 1,
    )

    private fun detectHeadingPages(pages: List<String>): List<OutlineEntry> = pages.mapIndexedNotNull { index, text ->
        val firstLines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(3).toList()
        firstLines.firstOrNull { ChapterDetector.isHeading(it) }?.let { OutlineEntry(it, index) }
    }

    /** Removes a first/last line that repeats on most pages (book title / author running headers, page numbers). */
    private fun stripRunningHeaders(page: String, all: List<String>): String {
        if (all.size < 6) return page
        val lines = page.lines().toMutableList()
        fun key(s: String) = s.trim().replace(Regex("\\d+"), "#")
        val firsts = all.mapNotNull { p -> p.lineSequence().firstOrNull { it.isNotBlank() }?.let(::key) }.groupingBy { it }.eachCount()
        val lasts = all.mapNotNull { p -> p.lines().lastOrNull { it.isNotBlank() }?.let(::key) }.groupingBy { it }.eachCount()
        val threshold = all.size * 0.5
        // Only short lines on multi-line pages can be headers/footers; never strip a page's only line of body text.
        if (lines.count { it.isNotBlank() } < 2) return page
        fun isShort(s: String) = s.trim().length <= 80
        val firstIdx = lines.indexOfFirst { it.isNotBlank() }
        if (isShort(lines[firstIdx]) && (firsts[key(lines[firstIdx])] ?: 0) > threshold) lines[firstIdx] = ""
        val lastIdx = lines.indexOfLast { it.isNotBlank() }
        if (lastIdx > firstIdx && isShort(lines[lastIdx]) &&
            ((lasts[key(lines[lastIdx])] ?: 0) > threshold || lines[lastIdx].trim().matches(Regex("\\d{1,4}")))
        ) lines[lastIdx] = ""
        return lines.joinToString("\n")
    }
}
