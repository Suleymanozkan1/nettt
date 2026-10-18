package com.bookmind.core.text

import kotlinx.serialization.Serializable

enum class SourceType { PDF, EPUB, TXT, MARKDOWN, MANUAL }

/**
 * Result of text extraction, before anything is persisted. Locations are kept per chapter so every later
 * summary, concept or question can point back to where its content came from.
 */
data class ExtractedBook(
    val title: String?,
    val author: String?,
    val language: String?,
    val chapters: List<ExtractedChapter>,
    val warnings: List<String> = emptyList(),
    /** True when headings were not found and chapters are synthetic fixed-size sections. */
    val syntheticChapters: Boolean = false,
    /** True when the source looked like a scan (little or no extractable text). */
    val needsOcr: Boolean = false,
) {
    val totalChars: Int get() = chapters.sumOf { it.text.length }
}

data class ExtractedChapter(
    val title: String,
    val text: String,
    /** Human readable location, e.g. "s. 12" for PDF pages, "bölüm-3.xhtml" for EPUB, "satır 120" for text. */
    val startLocation: String? = null,
    val endLocation: String? = null,
    val pageStart: Int? = null,
    val pageEnd: Int? = null,
)

/**
 * A pointer into the book. Stored as JSON on summaries, concepts and questions. [quote] is limited to a short
 * excerpt so we never store long verbatim passages of copyrighted text alongside generated content.
 */
@Serializable
data class SourceRef(
    val chapterIndex: Int,
    val chapterTitle: String? = null,
    val pageStart: Int? = null,
    val pageEnd: Int? = null,
    val charStart: Int? = null,
    val charEnd: Int? = null,
    val quote: String? = null,
    /** False when a reference from a model could not be matched against the book text. */
    val verified: Boolean = true,
) {
    fun label(): String = buildString {
        append("Bölüm ").append(chapterIndex + 1)
        chapterTitle?.takeIf { it.isNotBlank() }?.let { append(" – ").append(it) }
        if (pageStart != null) {
            append(", s. ").append(pageStart)
            if (pageEnd != null && pageEnd != pageStart) append("–").append(pageEnd)
        }
    }

    companion object {
        const val MAX_QUOTE_CHARS = 200
    }
}
