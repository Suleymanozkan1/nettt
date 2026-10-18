package com.bookmind.core.text

/** Markdown books: level-1/level-2 headings become chapters; formatting syntax is stripped from the body. */
object MarkdownParser {
    private val heading = Regex("^(#{1,2})\\s+(.+?)\\s*#*\\s*$")

    fun parse(markdown: String): ExtractedBook {
        val text = TextNormalizer.normalize(markdown)
        val lines = text.split('\n')
        var bookTitle: String? = null
        val chapters = mutableListOf<ExtractedChapter>()
        var currentTitle: String? = null
        var currentStart = 1
        val body = StringBuilder()
        val levelOneCount = lines.count { it.startsWith("# ") }
        // With a single H1 it is the book title and H2s are chapters; otherwise H1s are chapters.
        val chapterLevel = if (levelOneCount <= 1) 2 else 1

        fun flush() {
            val content = stripInline(body.toString()).trim()
            // Front matter before the first chapter heading (a one-line note, a dedication) is not a chapter.
            val isTinyFrontMatter = currentTitle == null && TextNormalizer.wordCount(content) < 40
            if (content.isNotEmpty() && !isTinyFrontMatter) {
                chapters += ExtractedChapter(currentTitle ?: "Giriş", content, startLocation = "satır $currentStart")
            }
            body.clear()
        }

        var inCode = false
        for ((i, line) in lines.withIndex()) {
            if (line.trimStart().startsWith("```")) { inCode = !inCode; body.append(line).append('\n'); continue }
            val m = if (inCode) null else heading.matchEntire(line)
            if (m != null) {
                val level = m.groupValues[1].length
                val title = stripInline(m.groupValues[2])
                if (level == 1 && chapterLevel == 2) {
                    if (bookTitle == null) bookTitle = title
                    continue
                }
                if (level == chapterLevel) {
                    flush()
                    currentTitle = title
                    currentStart = i + 1
                    continue
                }
            }
            body.append(line).append('\n')
        }
        flush()
        val all = chapters.joinToString("\n\n") { it.text }
        if (chapters.size <= 1) {
            val (split, synthetic) = ChapterDetector.split(all)
            return ExtractedBook(bookTitle, null, LanguageDetector.detect(all), split, syntheticChapters = synthetic)
        }
        return ExtractedBook(bookTitle, null, LanguageDetector.detect(all), chapters)
    }

    fun stripInline(s: String): String = s
        .replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
        .replace(Regex("(?<![\\w*])[*_](\\S.*?)[*_](?![\\w*])"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("^>\\s?", RegexOption.MULTILINE), "")
        .replace(Regex("^#{3,6}\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "• ")
        .replace(Regex("<[^>]+>"), "")
}
