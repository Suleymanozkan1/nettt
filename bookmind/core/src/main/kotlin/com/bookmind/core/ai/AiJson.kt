package com.bookmind.core.ai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

object AiJson {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * Models sometimes wrap JSON in prose or ``` fences, or truncate it. Finds the first balanced JSON object
     * (respecting strings and escapes) and returns it, or null if there is none.
     */
    fun extractObject(raw: String): String? {
        val text = raw.replace(Regex("```(?:json)?"), "")
        val start = text.indexOf('{')
        if (start < 0) return null
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
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
            }
        }
        return null
    }

    /**
     * Recovers the complete part of a JSON object that was cut off (a small on-device model reached its output
     * limit): drops the unfinished trailing element and closes the open brackets. Null if nothing usable is left.
     */
    fun repairTruncated(raw: String): String? {
        val text = raw.replace(Regex("```(?:json)?"), "")
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

    fun <T> decode(serializer: KSerializer<T>, raw: String): T {
        val obj = extractObject(raw) ?: repairTruncated(raw) ?: throw AiException.InvalidOutput("JSON bulunamadı")
        return try {
            json.decodeFromString(serializer, obj)
        } catch (e: SerializationException) {
            throw AiException.InvalidOutput(e.message?.take(200) ?: "ayrıştırılamadı")
        } catch (e: IllegalArgumentException) {
            throw AiException.InvalidOutput(e.message?.take(200) ?: "ayrıştırılamadı")
        }
    }
}
