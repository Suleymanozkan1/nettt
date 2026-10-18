package com.bookmind.core.text

/** Tiny stop-word based detector. Enough to pick UI/prompt language for Turkish vs. English books. */
object LanguageDetector {
    private val turkish = setOf(
        "ve", "bir", "bu", "da", "de", "ile", "için", "çok", "olarak", "gibi", "daha", "ama", "ne", "en", "olan",
        "kadar", "sonra", "her", "şey", "değil", "veya", "ancak", "ki", "mi", "biz", "ben", "onun", "bunu",
    )
    private val english = setOf(
        "the", "and", "of", "to", "a", "in", "is", "that", "it", "for", "as", "with", "was", "on", "be", "this",
        "are", "by", "not", "or", "from", "but", "have", "an", "they", "which", "you", "we",
    )

    fun detect(text: String, sampleChars: Int = 20_000): String? {
        val words = Regex("\\p{L}+").findAll(text.take(sampleChars)).map { TextNormalizer.foldCase(it.value) }.toList()
        if (words.size < 20) return null
        val tr = words.count { it in turkish } + words.count { w -> w.any { it in "çğışöü" } } / 3
        val en = words.count { it in english }
        return when {
            tr == 0 && en == 0 -> null
            tr >= en -> "tr"
            else -> "en"
        }
    }
}
