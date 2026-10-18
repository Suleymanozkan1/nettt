package com.bookmind.core.text

object PlainTextParser {
    fun parse(text: String): ExtractedBook {
        val (chapters, synthetic) = ChapterDetector.split(text)
        val warnings = if (synthetic) listOf("Bölüm başlıkları bulunamadı; metin eşit büyüklükte kısımlara ayrıldı.") else emptyList()
        return ExtractedBook(null, null, LanguageDetector.detect(text), chapters, warnings, synthetic)
    }
}
