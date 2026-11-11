package net.alummahnow.core.bridge.studio.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Lenient readers for api-videos JSON (fields are often optional or loosely typed). */
internal object J {
    val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): JsonElement = json.parseToJsonElement(body)
}

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
internal fun JsonObject.o(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.a(key: String): JsonArray = (this[key] as? JsonArray) ?: JsonArray(emptyList())

internal fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
internal fun JsonObject.l(key: String): Long = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0L
internal fun JsonObject.d(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.b(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let {
    it.booleanOrNull
        ?: it.contentOrNull?.toBooleanStrictOrNull()
}

internal fun JsonObject.strMap(key: String): Map<String, String> =
    o(key)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()

internal fun JsonObject.strList(key: String): List<String> = when (val v = this[key]) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    is JsonPrimitive -> v.contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    else -> emptyList()
}

/** `{success, result}` envelopes and bare bodies alike. */
internal fun JsonElement.unwrap(): JsonElement = (this as? JsonObject)?.let { it["result"] ?: it["data"] } ?: this
