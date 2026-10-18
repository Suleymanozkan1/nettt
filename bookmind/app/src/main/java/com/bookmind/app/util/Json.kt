package com.bookmind.app.util

import com.bookmind.core.ai.AiJson
import com.bookmind.core.ai.GenerationInfo
import com.bookmind.core.text.SourceRef
import kotlinx.serialization.builtins.ListSerializer

val AppJson = AiJson.json

fun List<SourceRef>.toJson(): String = AppJson.encodeToString(ListSerializer(SourceRef.serializer()), this)
fun parseRefs(json: String?): List<SourceRef> =
    json?.let { runCatching { AppJson.decodeFromString(ListSerializer(SourceRef.serializer()), it) }.getOrNull() }.orEmpty()

fun GenerationInfo.toJson(): String = AppJson.encodeToString(GenerationInfo.serializer(), this)
fun parseInfo(json: String?): GenerationInfo? =
    json?.let { runCatching { AppJson.decodeFromString(GenerationInfo.serializer(), it) }.getOrNull() }

fun List<String>.toJsonList(): String = AppJson.encodeToString(ListSerializer(kotlinx.serialization.serializer<String>()), this)
fun parseStringList(json: String?): List<String> =
    json?.let { runCatching { AppJson.decodeFromString(ListSerializer(kotlinx.serialization.serializer<String>()), it) }.getOrNull() }.orEmpty()
