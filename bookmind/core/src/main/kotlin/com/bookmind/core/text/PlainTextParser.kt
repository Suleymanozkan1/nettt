package com.bookmind.core.text

import com.bookmind.core.i18n.CoreText

object PlainTextParser {
    fun parse(text: String, lang: String? = null): ExtractedBook {
        val (chapters, synthetic) = ChapterDetector.split(text, lang)
        val warnings = if (synthetic) listOf(CoreText.of(lang).noHeadings) else emptyList()
        return ExtractedBook(null, null, LanguageDetector.detect(text), chapters, warnings, synthetic)
    }
}
