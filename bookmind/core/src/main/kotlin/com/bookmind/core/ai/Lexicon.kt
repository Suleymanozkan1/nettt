package com.bookmind.core.ai

import com.bookmind.core.text.TextNormalizer

/** Tokenisation helpers shared by the on-device engine and the answer grader. */
object Lexicon {
    val stopwords: Set<String> = (
        "ve veya ile ama fakat ancak çünkü ki de da bu şu o bir iki her hiç çok daha en gibi kadar için olarak olan " +
            "olduğu olduğunu olur olmak oldu olsa ise mi mı mu mü ne neden nasıl nerede hangi bunu bunun buna şunu onu onun ona " +
            "biz siz onlar ben sen kendi kendine sonra önce şimdi yine artık hem ya bile değil var yok gerek diye tüm bütün " +
            "bazı birçok çoğu yani ayrıca böyle şöyle öyle ise dolayı göre karşı üzere arasında içinde üzerinde altında " +
            "the a an and or but of to in on at by for with from as is are was were be been being this that these those it its " +
            "they them their we our you your he she his her i me my not no so if then than there here which who whom what when " +
            "where why how all any each some such can could would should will may might must do does did done have has had " +
            "into about over under more most other also only just very"
        ).split(' ').filter { it.isNotBlank() }.toSet()

    private val wordRegex = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)?")

    fun words(text: String): List<String> = wordRegex.findAll(text).map { it.value }.toList()

    /** Lower-cased content words (no stop words, no very short tokens). */
    fun contentWords(text: String): List<String> = words(text)
        .map { TextNormalizer.foldCase(it.substringBefore('\'').substringBefore('’')) }
        .filter { it.length >= 3 && it !in stopwords && !it.all(Char::isDigit) }

    /**
     * Crude stem: Turkish is agglutinative ("kavramların" → "kavram"), so the first characters carry the root.
     * Good enough for overlap scoring; never shown to users.
     */
    fun stem(word: String): String {
        val w = TextNormalizer.asciiFold(word)
        return if (w.length <= 6) w else w.take(6)
    }

    fun stems(text: String): Set<String> = contentWords(text).map(::stem).toSet()
}
