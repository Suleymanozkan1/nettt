package com.bookmind.core.ai

import com.bookmind.core.text.TextNormalizer

/**
 * Deterministic faithfulness check for generated text (no second model needed): every number and every
 * proper name a model writes must occur somewhere in the source text. Anything that does not is reported so
 * the reader can treat it as an inference or a mistake. Cheap enough to run on every AI summary.
 */
object Faithfulness {
    private val number = Regex("(?<![\\p{L}\\d])\\d+(?:[.,]\\d+)?(?![\\p{L}\\d])")
    private val word = Regex("[\\p{L}][\\p{L}'’-]*")

    data class Report(val unsupportedNumbers: List<String>, val unsupportedNames: List<String>) {
        val clean get() = unsupportedNumbers.isEmpty() && unsupportedNames.isEmpty()
        val items get() = unsupportedNumbers + unsupportedNames
    }

    fun check(generated: List<String>, sources: List<String>): Report {
        val source = TextNormalizer.asciiFold(sources.joinToString("\n"))
        val sourceNumbers = number.findAll(source).map { normalizeNumber(it.value) }.toSet()
        val numbers = generated.flatMap { g -> number.findAll(g).map { it.value } }
            .filter { normalizeNumber(it) !in sourceNumbers }.distinct()
        val names = generated.flatMap { properNames(it) }
            .filter { n -> !source.contains(TextNormalizer.asciiFold(n)) }.distinct()
        return Report(numbers.take(10), names.take(10))
    }

    private fun normalizeNumber(n: String) = n.replace(',', '.').trimEnd('0').trimEnd('.')

    /** Capitalised words that do not start a sentence: names of people, places, works. */
    private fun properNames(text: String): List<String> = buildList {
        var sentenceStart = true
        for (m in word.findAll(text)) {
            val w = m.value
            val before = text.substring(0, m.range.first).trimEnd()
            sentenceStart = before.isEmpty() || before.last() in ".!?:\n“\"(«"
            if (!sentenceStart && w.length >= 3 && w[0].isUpperCase() && w.drop(1).any { it.isLowerCase() } &&
                TextNormalizer.foldCase(w) !in Lexicon.stopwords
            ) add(w.trim('\'', '’', '-'))
        }
    }
}
