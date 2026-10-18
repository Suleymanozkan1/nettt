package com.bookmind.core.text

object TextNormalizer {
    private val hyphenatedBreak = Regex("(\\p{L})-\\n(\\p{Ll})")
    private val trailingSpaces = Regex("[ \\t\\u00A0]+\\n")
    private val manyBlankLines = Regex("\\n{3,}")
    private val inlineSpaces = Regex("[ \\t\\u00A0]{2,}")
    private val controlChars = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")

    /** Normalises line endings and whitespace while keeping paragraph breaks (blank lines). */
    fun normalize(raw: String): String {
        var t = raw.replace("\r\n", "\n").replace('\r', '\n')
        t = controlChars.replace(t, "")
        t = t.replace('­', ' ').replace("﻿", "")
        t = trailingSpaces.replace(t, "\n")
        t = inlineSpaces.replace(t, " ")
        t = manyBlankLines.replace(t, "\n\n")
        return t.trim()
    }

    /**
     * PDF text comes as hard-wrapped lines. Joins words split by a hyphen at the line end and merges wrapped
     * lines into paragraphs, keeping blank lines and lines that end a sentence as paragraph boundaries.
     */
    fun unwrapPdfLines(pageText: String): String {
        val t = hyphenatedBreak.replace(normalize(pageText), "$1$2")
        val out = StringBuilder()
        val lines = t.split('\n')
        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                out.append("\n\n"); continue
            }
            out.append(trimmed)
            val next = lines.getOrNull(i + 1)?.trim().orEmpty()
            val endsParagraph = next.isEmpty() || (trimmed.last() in ".!?:\"”»" && next.firstOrNull()?.isUpperCase() == true && trimmed.length < 60)
            out.append(if (endsParagraph) "\n" else " ")
        }
        return normalize(out.toString())
    }

    fun paragraphs(text: String): List<String> =
        text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }

    fun wordCount(text: String): Int = Regex("[\\p{L}\\p{N}]+").findAll(text).count()

    /** Turkish-aware lower-casing: "I" → "ı" and "İ" → "i" must not be mixed up for Turkish text. */
    fun foldCase(s: String, turkish: Boolean = true): String =
        if (turkish) s.replace('I', 'ı').replace('İ', 'i').lowercase() else s.lowercase()

    /** Removes diacritics-insensitive differences so "ogrenme" matches "öğrenme" when grading typed answers. */
    fun asciiFold(s: String): String {
        val map = mapOf('ç' to 'c', 'ğ' to 'g', 'ı' to 'i', 'ö' to 'o', 'ş' to 's', 'ü' to 'u', 'â' to 'a', 'î' to 'i', 'û' to 'u')
        val sb = StringBuilder(s.length)
        for (ch in foldCase(s)) sb.append(map[ch] ?: ch)
        return java.text.Normalizer.normalize(sb, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }
}
