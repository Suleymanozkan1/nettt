package com.bookmind.core.ai

/**
 * Cleans what a small on-device model returns before it is parsed: removes a byte-order mark and ``` fences, finds
 * the JSON object inside surrounding prose, wraps a bare top-level array when the caller expects one, drops
 * trailing commas, and recovers the complete part of an answer that was cut off at the output limit.
 * Works outside string literals only, so text inside the answer (quotes, braces, commas) is never altered.
 */
object JsonSanitizer {
    private val fences = Regex("```(?:json|JSON)?")

    /**
     * Returns a JSON object string, or null if nothing usable is there. [arrayKey]: when the model answers with a
     * bare array (`[{"speaker":"A",…}]`) instead of the requested object, it is wrapped as `{"arrayKey": […]}`.
     */
    fun sanitize(raw: String, arrayKey: String? = null): String? {
        val text = raw.removePrefix("﻿").replace(fences, "")
        val objStart = text.indexOf('{')
        val arrStart = text.indexOf('[')
        val candidate = if (arrayKey != null && arrStart >= 0 && (objStart < 0 || arrStart < objStart)) {
            (balanced(text, arrStart) ?: repairTruncated("{\"$arrayKey\":" + text.substring(arrStart))?.let { return removeTrailingCommas(it) })
                ?.let { "{\"$arrayKey\":$it}" }
        } else {
            if (objStart < 0) return null
            balanced(text, objStart) ?: repairTruncated(text)
        }
        return candidate?.let(::removeTrailingCommas)
    }

    /** The first balanced JSON object in [raw] (respecting strings and escapes), or null. */
    fun extractObject(raw: String): String? {
        val text = raw.replace(fences, "")
        val start = text.indexOf('{')
        return if (start < 0) null else balanced(text, start)
    }

    /** The balanced object or array that opens at [start], or null if it never closes. */
    private fun balanced(text: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
            }
        }
        return null
    }

    /**
     * Recovers the complete part of a JSON object that was cut off (the model reached its output limit): drops the
     * unfinished trailing element and closes the open brackets. Null if nothing usable is left.
     */
    fun repairTruncated(raw: String): String? {
        val text = raw.replace(fences, "")
        val start = text.indexOf('{')
        if (start < 0) return null
        val stack = StringBuilder()
        var inString = false
        var escaped = false
        var cut = -1
        var cutStack = ""
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> {
                    // An unfinished element of an array can be dropped whole (cut before it); an unfinished object
                    // value cannot (it would leave a dangling key), so the enclosing comma is used instead.
                    val prev = text.substring(start, i).trimEnd().lastOrNull()
                    // (After a comma the comma itself is already the cut point.)
                    if (prev == '[') { cut = i; cutStack = stack.toString() }
                    stack.append(c)
                }
                '}', ']' -> { if (stack.isNotEmpty()) stack.setLength(stack.length - 1); if (stack.isEmpty()) return text.substring(start, i + 1) }
                // Everything before a comma is complete. Only array items and top-level fields are cut points, so a
                // half-written item ({"heading":"B"} without its body) is dropped whole rather than kept incomplete.
                ',' -> if (stack.length == 1 || stack.last() == '[') { cut = i; cutStack = stack.toString() }
            }
        }
        if (cut < 0 || cutStack.isEmpty()) return null
        val closers = cutStack.reversed().map { if (it == '{') '}' else ']' }.joinToString("")
        return text.substring(start, cut).trimEnd() + closers
    }

    /** `{"a":1,}` → `{"a":1}` and `[1,2,]` → `[1,2]`, outside string literals. */
    fun removeTrailingCommas(json: String): String {
        val out = StringBuilder(json.length)
        var inString = false
        var escaped = false
        for ((i, c) in json.withIndex()) {
            if (inString) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            if (c == '"') inString = true
            if (c == ',') {
                val next = json.substring(i + 1).firstOrNull { !it.isWhitespace() }
                if (next == '}' || next == ']') continue
            }
            out.append(c)
        }
        return out.toString()
    }
}
