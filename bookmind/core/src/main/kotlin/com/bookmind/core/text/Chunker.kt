package com.bookmind.core.text

/**
 * Splits chapter text into overlapping chunks that fit an AI request. Boundaries fall on paragraphs (or
 * sentences for very long paragraphs) and every chunk records its character range for source references.
 */
object Chunker {
    data class Chunk(val chapterIndex: Int, val index: Int, val text: String, val charStart: Int, val charEnd: Int)

    fun chunk(chapterIndex: Int, text: String, maxChars: Int = 6_000, overlapChars: Int = 300): List<Chunk> {
        require(maxChars > overlapChars * 2) { "maxChars must be well above overlap" }
        if (text.length <= maxChars) return listOf(Chunk(chapterIndex, 0, text, 0, text.length))

        val chunks = mutableListOf<Chunk>()
        var start = 0
        while (start < text.length) {
            var end = (start + maxChars).coerceAtMost(text.length)
            if (end < text.length) {
                val window = text.substring(start, end)
                val paragraphBreak = window.lastIndexOf("\n\n")
                val sentenceBreak = maxOf(window.lastIndexOf(". "), window.lastIndexOf("? "), window.lastIndexOf("! "))
                end = when {
                    paragraphBreak > maxChars / 2 -> start + paragraphBreak
                    sentenceBreak > maxChars / 2 -> start + sentenceBreak + 1
                    else -> window.lastIndexOf(' ').takeIf { it > maxChars / 2 }?.let { start + it } ?: end
                }
            }
            chunks += Chunk(chapterIndex, chunks.size, text.substring(start, end).trim(), start, end)
            if (end >= text.length) break
            start = (end - overlapChars).coerceAtLeast(start + 1)
            // Start the next chunk on a word boundary.
            while (start < end && !text[start - 1].isWhitespace()) start++
        }
        return chunks
    }
}
