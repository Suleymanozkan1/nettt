package com.bookmind.core.text

/**
 * Builds chapters from per-page PDF text. Uses the PDF outline (bookmarks) when present, otherwise heading
 * detection on page text, otherwise groups pages into sections. Page numbers are 1-based.
 */
object PdfStructureBuilder {
    data class OutlineEntry(val title: String, val pageIndex: Int)

    /** Minimum average characters per page below which the PDF is treated as scanned (needs OCR). */
    const val SCANNED_CHARS_PER_PAGE = 40

    fun build(pages: List<String>, outline: List<OutlineEntry>, pagesPerSection: Int = 15): ExtractedBook {
        val cleaned = pages.map { TextNormalizer.unwrapPdfLines(stripRunningHeaders(it, pages)) }
        val totalChars = cleaned.sumOf { it.length }
        val needsOcr = pages.isNotEmpty() && totalChars / pages.size < SCANNED_CHARS_PER_PAGE
        if (needsOcr) {
            return ExtractedBook(null, null, null, emptyList(), listOf("PDF taranmış görünüyor; metin çıkarmak için OCR gerekli."), needsOcr = true)
        }
        val language = LanguageDetector.detect(cleaned.joinToString("\n"))

        val starts = outline
            .filter { it.pageIndex in pages.indices && it.title.isNotBlank() }
            .distinctBy { it.pageIndex }
            .sortedBy { it.pageIndex }
            .ifEmpty { detectHeadingPages(cleaned) }

        if (starts.size >= 2) {
            val chapters = mutableListOf<ExtractedChapter>()
            if (starts.first().pageIndex > 0) {
                val front = cleaned.subList(0, starts.first().pageIndex).joinToString("\n\n").trim()
                if (TextNormalizer.wordCount(front) >= 150) chapters += chapter("Başlangıç", cleaned, 0, starts.first().pageIndex - 1)
            }
            for ((i, s) in starts.withIndex()) {
                val endPage = (starts.getOrNull(i + 1)?.pageIndex ?: pages.size) - 1
                if (endPage < s.pageIndex) continue
                chapter(s.title.trim(), cleaned, s.pageIndex, endPage).takeIf { it.text.isNotBlank() }?.let { chapters += it }
            }
            return ExtractedBook(null, null, language, chapters)
        }

        val chapters = cleaned.indices.chunked(pagesPerSection).map { range ->
            chapter("Sayfa ${range.first() + 1}–${range.last() + 1}", cleaned, range.first(), range.last())
        }.filter { it.text.isNotBlank() }
        return ExtractedBook(
            null, null, language, chapters,
            warnings = listOf("PDF içinde içindekiler/başlık bulunamadı; sayfalar gruplandı."),
            syntheticChapters = true,
        )
    }

    private fun chapter(title: String, pages: List<String>, from: Int, to: Int) = ExtractedChapter(
        title = title,
        text = pages.subList(from, to + 1).joinToString("\n\n").trim(),
        startLocation = "s. ${from + 1}",
        endLocation = "s. ${to + 1}",
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
