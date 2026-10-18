package com.bookmind.core.text

object Sentences {
    private val abbreviations = setOf(
        "dr", "prof", "doç", "yrd", "örn", "vb", "vs", "bkz", "s", "sf", "no", "mr", "mrs", "ms", "st", "vol",
        "e.g", "i.e", "etc", "fig", "ch", "cf", "vd", "çev", "haz", "yy", "m.ö", "m.s",
    )

    data class Span(val text: String, val start: Int, val end: Int)

    /** Splits text into sentences, keeping the character offsets so a sentence can be cited later. */
    fun split(text: String): List<Span> {
        val result = mutableListOf<Span>()
        var start = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val isTerminator = c == '.' || c == '!' || c == '?' || c == '…'
            val isParagraphBreak = c == '\n' && i + 1 < text.length && text[i + 1] == '\n'
            if (isTerminator || isParagraphBreak) {
                var end = i + 1
                while (end < text.length && text[end] in ".!?…\"”’»)") end++
                val nextIsBoundary = end >= text.length || text[end].isWhitespace()
                if (isParagraphBreak || (nextIsBoundary && !endsWithAbbreviation(text, start, i) && !isDecimal(text, i))) {
                    addSpan(text, start, end, result)
                    start = end
                    i = end
                    continue
                }
            }
            i++
        }
        addSpan(text, start, text.length, result)
        return result
    }

    private fun addSpan(text: String, start: Int, end: Int, out: MutableList<Span>) {
        if (start >= end) return
        val raw = text.substring(start, end)
        val lead = raw.length - raw.trimStart().length
        val trimmed = raw.trim()
        if (trimmed.isNotEmpty()) out += Span(trimmed.replace(Regex("\\s+"), " "), start + lead, start + lead + trimmed.length)
    }

    private fun endsWithAbbreviation(text: String, sentenceStart: Int, dotIndex: Int): Boolean {
        if (text[dotIndex] != '.') return false
        var j = dotIndex - 1
        while (j >= sentenceStart && (text[j].isLetter() || text[j] == '.')) j--
        val word = text.substring(j + 1, dotIndex)
        if (word.isEmpty()) return false
        // A single capital letter is an initial ("J. R. R. Tolkien"), not a sentence end.
        return word.lowercase() in abbreviations || (word.length == 1 && word[0].isUpperCase())
    }

    private fun isDecimal(text: String, i: Int): Boolean =
        text[i] == '.' && i > 0 && text[i - 1].isDigit() && text.getOrNull(i + 1)?.isDigit() == true
}
