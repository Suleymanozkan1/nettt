package com.bookmind.core.ai.summary

import com.bookmind.core.text.Sentences
import com.bookmind.core.text.TextNormalizer
import kotlin.math.ceil

/** A chapter as the summary pipeline sees it. */
data class ChapterText(val index: Int, val title: String, val text: String) {
    val words: Int by lazy { TextNormalizer.wordCount(text) }
}

/** A chapter, or one piece of a chapter that was too long for a single request. */
data class ChunkPart(
    val chapterIndex: Int,
    val title: String,
    val text: String,
    /** 1-based piece number and count; 1/1 for a whole chapter. */
    val piece: Int = 1,
    val pieces: Int = 1,
) {
    val words: Int by lazy { TextNormalizer.wordCount(text) }
}

/** One request to the model: consecutive parts with together about [SummaryConfig.chunkMinWords]–[SummaryConfig.chunkMaxWords] words. */
data class SummaryChunk(val index: Int, val parts: List<ChunkPart>) {
    val words: Int get() = parts.sumOf { it.words }
}

/**
 * Splits a book into requests of roughly 5,000–8,000 words. Chapter boundaries (from headings, the PDF outline or
 * the EPUB spine) are kept: a chapter longer than the maximum is cut into equal pieces on paragraph (or, failing
 * that, sentence) boundaries, and short consecutive chapters share one request while each still gets its own
 * summary. Nothing is dropped or reordered.
 */
object ChunkPlanner {
    fun plan(chapters: List<ChapterText>, minWords: Int, maxWords: Int): List<SummaryChunk> {
        require(minWords in 1..maxWords) { "minWords must be between 1 and maxWords" }
        val parts = chapters.filter { it.text.isNotBlank() }.flatMap { split(it, maxWords) }
        val chunks = mutableListOf<MutableList<ChunkPart>>()
        var words = 0
        for (p in parts) {
            val current = chunks.lastOrNull()
            if (current != null && words < minWords && words + p.words <= maxWords) {
                current += p
                words += p.words
            } else {
                chunks += mutableListOf(p)
                words = p.words
            }
        }
        // A short tail goes into the previous request when that stays reasonably sized.
        if (chunks.size >= 2) {
            val last = chunks.last().sumOf { it.words }
            val previous = chunks[chunks.size - 2].sumOf { it.words }
            if (last < minWords / 2 && previous + last <= maxWords * 5 / 4) chunks[chunks.size - 2] += chunks.removeAt(chunks.lastIndex)
        }
        return chunks.mapIndexed { i, c -> SummaryChunk(i, c) }
    }

    /** Cuts a long chapter into ceil(words / max) pieces of similar size. */
    internal fun split(chapter: ChapterText, maxWords: Int): List<ChunkPart> {
        if (chapter.words <= maxWords) return listOf(ChunkPart(chapter.index, chapter.title, chapter.text))
        val count = ceil(chapter.words / maxWords.toDouble()).toInt()
        val target = chapter.words / count
        val units = units(chapter.text, target)
        val pieces = mutableListOf<StringBuilder>()
        var words = 0
        for (u in units) {
            val w = TextNormalizer.wordCount(u)
            if (pieces.isEmpty() || (words >= target && pieces.size < count)) { pieces += StringBuilder(); words = 0 }
            pieces.last().append(u).append("\n\n")
            words += w
        }
        return pieces.mapIndexed { i, sb -> ChunkPart(chapter.index, chapter.title, sb.toString().trim(), i + 1, pieces.size) }
    }

    /** Paragraphs, with any paragraph longer than [target] words broken into sentences (PDF text often has none). */
    private fun units(text: String, target: Int): List<String> =
        TextNormalizer.paragraphs(text).flatMap { p ->
            if (TextNormalizer.wordCount(p) <= target) listOf(p)
            else Sentences.split(p).map { it.text }.ifEmpty { p.split(Regex("\\s+")).chunked(200).map { it.joinToString(" ") } }
        }
}
