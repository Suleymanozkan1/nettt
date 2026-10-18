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

    fun <T> decode(serializer: KSerializer<T>, raw: String): T {
        val obj = extractObject(raw) ?: throw AiException.InvalidOutput("JSON bulunamadı")
        return try {
            json.decodeFromString(serializer, obj)
        } catch (e: SerializationException) {
            throw AiException.InvalidOutput(e.message?.take(200) ?: "ayrıştırılamadı")
        } catch (e: IllegalArgumentException) {
            throw AiException.InvalidOutput(e.message?.take(200) ?: "ayrıştırılamadı")
        }
    }
}
