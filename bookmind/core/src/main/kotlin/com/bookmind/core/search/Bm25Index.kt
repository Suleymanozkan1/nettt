package com.bookmind.core.search

import com.bookmind.core.ai.Lexicon
import com.bookmind.core.text.TextNormalizer
import kotlin.math.ln

/** A searchable piece of a chapter, with its character range so answers can cite the exact place. */
data class Passage(val chapterIndex: Int, val chapterTitle: String, val start: Int, val end: Int, val text: String)

/**
 * Classic Okapi BM25 keyword retrieval over book passages. Needs no model, no network and no index files:
 * a whole book is indexed in memory in milliseconds. Terms are the same crude stems the grader uses, so
 * Turkish suffixes ("kavramların" → "kavram") still match.
 */
class Bm25Index(val passages: List<Passage>, private val k1: Double = 1.4, private val b: Double = 0.75) {
    private val docs: List<Map<String, Int>> = passages.map { p -> terms(p.text).groupingBy { it }.eachCount() }
    private val lengths = docs.map { d -> d.values.sum() }
    private val avgLength = lengths.average().takeIf { !it.isNaN() && it > 0 } ?: 1.0
    private val documentFrequency: Map<String, Int> = HashMap<String, Int>().also { df -> docs.forEach { d -> d.keys.forEach { t -> df.merge(t, 1, Int::plus) } } }

    data class Hit(val passage: Passage, val score: Double)

    fun search(query: String, limit: Int = 5): List<Hit> {
        val q = terms(query).distinct()
        if (q.isEmpty() || passages.isEmpty()) return emptyList()
        val n = passages.size.toDouble()
        return passages.indices.map { i ->
            val d = docs[i]
            var score = 0.0
            for (t in q) {
                val f = d[t] ?: continue
                val df = documentFrequency[t] ?: continue
                val idf = ln(1 + (n - df + 0.5) / (df + 0.5))
                score += idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * lengths[i] / avgLength))
            }
            Hit(passages[i], score)
        }.filter { it.score > 0 }.sortedByDescending { it.score }.take(limit)
    }

    companion object {
        fun terms(text: String): List<String> = Lexicon.contentWords(text).map(Lexicon::stem)

        /**
         * Splits chapters into passages of roughly [targetChars] on paragraph boundaries; very long paragraphs
         * are cut on sentence ends. Offsets point into the original chapter text.
         */
        fun passages(chapters: List<Triple<Int, String, String>>, targetChars: Int = 700): List<Passage> = buildList {
            for ((index, title, text) in chapters) {
                var start = -1
                var end = 0
                val paragraphs = Regex("[^\\n]+(?:\\n(?!\\s*\\n)[^\\n]+)*").findAll(text).toList()
                for (m in paragraphs) {
                    if (start < 0) start = m.range.first
                    end = m.range.last + 1
                    if (end - start >= targetChars) {
                        add(Passage(index, title, start, end, text.substring(start, end).trim()))
                        start = -1
                    }
                }
                if (start >= 0 && end > start) add(Passage(index, title, start, end, text.substring(start, end).trim()))
            }
        }.filter { TextNormalizer.wordCount(it.text) >= 5 }
    }
}
