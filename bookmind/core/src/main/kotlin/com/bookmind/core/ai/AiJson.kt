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

    /** See [JsonSanitizer.extractObject]. */
    fun extractObject(raw: String): String? = JsonSanitizer.extractObject(raw)

    /** See [JsonSanitizer.repairTruncated]. */
    fun repairTruncated(raw: String): String? = JsonSanitizer.repairTruncated(raw)

    /** Decodes model output after [JsonSanitizer] cleaned it; [arrayKey] accepts a bare top-level array for that field. */
    fun <T> decode(serializer: KSerializer<T>, raw: String, arrayKey: String? = null): T {
        val obj = JsonSanitizer.sanitize(raw, arrayKey) ?: throw AiException.InvalidOutput("JSON bulunamadı")
        return try {
            json.decodeFromString(serializer, obj)
        } catch (e: SerializationException) {
            throw AiException.InvalidOutput(e.message?.take(200) ?: "ayrıştırılamadı")
        } catch (e: IllegalArgumentException) {
            throw AiException.InvalidOutput(e.message?.take(200) ?: "ayrıştırılamadı")
        }
    }
}
