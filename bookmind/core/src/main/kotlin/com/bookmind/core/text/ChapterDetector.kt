package com.bookmind.core.text

import com.bookmind.core.i18n.CoreText

/**
 * Finds chapter boundaries in plain text. Recognises common Turkish and English headings
 * ("Bölüm 3", "3. Bölüm", "ÜÇÜNCÜ BÖLÜM", "Chapter 3", "CHAPTER III", "Part One", "Kısım 2") and falls back
 * to fixed-size synthetic sections when a book has no detectable headings.
 */
object ChapterDetector {
    private val ordinalWordsTr = "birinci|ikinci|üçüncü|dördüncü|beşinci|altıncı|yedinci|sekizinci|dokuzuncu|onuncu|" +
        "on birinci|on ikinci|on üçüncü|on dördüncü|on beşinci|yirminci"
    private val numberWordsEn = "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|" +
        "fifteen|sixteen|seventeen|eighteen|nineteen|twenty"
    private const val ROMAN = "[ivxlcdm]+"

    private val headingPatterns = listOf(
        Regex("^(bölüm|kısım|ünite|chapter|part|section)\\s+(\\d{1,3}|$ROMAN|$numberWordsEn)\\b[\\s.:\\-–—]*(.{0,80})$", RegexOption.IGNORE_CASE),
        Regex("^(\\d{1,3}|$ROMAN)\\.?\\s*(bölüm|kısım|ünite)\\b[\\s.:\\-–—]*(.{0,80})$", RegexOption.IGNORE_CASE),
        Regex("^($ordinalWordsTr)\\s+(bölüm|kısım)\\b[\\s.:\\-–—]*(.{0,80})$", RegexOption.IGNORE_CASE),
        Regex("^(önsöz|giriş|sonsöz|sonuç|prologue|epilogue|introduction|preface|conclusion|foreword|afterword)\\s*$", RegexOption.IGNORE_CASE),
    )

    data class Heading(val lineIndex: Int, val charOffset: Int, val title: String)

    fun isHeading(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t.length > 100) return false
        return headingPatterns.any { it.matches(t) }
    }

    fun findHeadings(text: String): List<Heading> {
        val headings = mutableListOf<Heading>()
        var offset = 0
        val lines = text.split('\n')
        for ((i, line) in lines.withIndex()) {
            val prevBlank = i == 0 || lines[i - 1].isBlank()
            if (prevBlank && isHeading(line)) {
                // Merge a following short subtitle line ("Bölüm 1\nBaşlangıç") into the heading.
                val next = lines.getOrNull(i + 1)?.trim().orEmpty()
                val title = if (next.isNotEmpty() && next.length <= 70 && !isHeading(next) && next.last() !in ".,;") {
                    "${line.trim()} – $next"
                } else line.trim()
                headings += Heading(i, offset, title)
            }
            offset += line.length + 1
        }
        return dropTableOfContents(headings, text.length)
    }

    /**
     * A table of contents lists all headings within a few lines of each other. Real chapters are far apart,
     * so headings separated by tiny gaps at the start of the book are discarded.
     */
    private fun dropTableOfContents(headings: List<Heading>, textLength: Int): List<Heading> {
        if (headings.size < 3) return headings
        val minGap = (textLength / (headings.size * 20)).coerceIn(200, 2_000)
        val kept = mutableListOf<Heading>()
        for ((i, h) in headings.withIndex()) {
            val nextOffset = headings.getOrNull(i + 1)?.charOffset ?: textLength
            if (nextOffset - h.charOffset >= minGap || i == headings.lastIndex) kept += h
        }
        return kept
    }

    fun split(text: String, lang: String? = null, fallbackTitle: (Int) -> String = { CoreText.of(lang).part(it + 1) }, targetSectionChars: Int = 12_000): Pair<List<ExtractedChapter>, Boolean> {
        val t = CoreText.of(lang)
        val normalized = TextNormalizer.normalize(text)
        val headings = findHeadings(normalized)
        if (headings.size >= 2) {
            val chapters = mutableListOf<ExtractedChapter>()
            val preface = normalized.substring(0, headings.first().charOffset).trim()
            if (TextNormalizer.wordCount(preface) >= 80) {
                chapters += ExtractedChapter(t.opening, preface, startLocation = t.line(1))
            }
            for ((i, h) in headings.withIndex()) {
                val end = headings.getOrNull(i + 1)?.charOffset ?: normalized.length
                val body = normalized.substring(h.charOffset, end).lines().drop(1).joinToString("\n").trim()
                if (body.isBlank()) continue
                chapters += ExtractedChapter(h.title, body, startLocation = t.line(h.lineIndex + 1))
            }
            if (chapters.isNotEmpty()) return chapters to false
        }
        return syntheticSections(normalized, targetSectionChars, fallbackTitle) to true
    }

    /** Splits into roughly equal sections on paragraph boundaries. */
    fun syntheticSections(text: String, targetChars: Int, title: (Int) -> String): List<ExtractedChapter> {
        val paragraphs = TextNormalizer.paragraphs(text)
        if (paragraphs.isEmpty()) return emptyList()
        val sections = mutableListOf<ExtractedChapter>()
        val current = StringBuilder()
        for (p in paragraphs) {
            if (current.isNotEmpty() && current.length + p.length > targetChars) {
                sections += ExtractedChapter(title(sections.size), current.toString().trim())
                current.clear()
            }
            current.append(p).append("\n\n")
        }
        if (current.isNotBlank()) sections += ExtractedChapter(title(sections.size), current.toString().trim())
        return sections
    }
}
