package studio.usergist.feedback.internal.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Helpers for converting between `Map<String, Any?>` (the public
 * property-bag shape) and `JsonObject` (the serialization shape).
 *
 * Event/identify properties intentionally mirror the React Native reference:
 * at most 100 non-PII keys, scalar values only, and bounded strings. This is
 * stricter than generic JSON because the ingest schema rejects arrays/maps.
 */
internal object AnyMap {

    /** Coerces a user-supplied property bag into a [JsonObject]. */
    fun toJsonObject(map: Map<String, Any?>?): JsonObject? {
        if (map == null) return null
        val entries = LinkedHashMap<String, JsonElement>(minOf(map.size, 100))
        for ((key, value) in map.entries.take(100)) {
            if (key.isEmpty() || key.length > 120 || isPiiKey(key)) continue
            toEventScalar(value)?.let { entries[key] = it }
        }
        return JsonObject(entries)
    }

    private fun isPiiKey(key: String): Boolean =
        Regex("(?:^|[._])(email|phone|ssn|tax_id)$", RegexOption.IGNORE_CASE).containsMatchIn(key)

    private fun toEventScalar(value: Any?): JsonElement? = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is String -> JsonPrimitive(value.take(10_000))
        is Byte -> JsonPrimitive(value.toInt())
        is Short -> JsonPrimitive(value.toInt())
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Float -> if (value.isFinite()) JsonPrimitive(value.toDouble()) else null
        is Double -> if (value.isFinite()) JsonPrimitive(value) else null
        is Number -> value.toDouble().takeIf { it.isFinite() }?.let(::JsonPrimitive)
        else -> null
    }

    /** Decodes a [JsonObject] back into a `Map<String, Any?>`. */
    fun fromJsonObject(obj: JsonObject?): Map<String, Any?>? {
        if (obj == null) return null
        val result = LinkedHashMap<String, Any?>(obj.size)
        for ((key, value) in obj) {
            result[key] = fromJsonElement(value)
        }
        return result
    }

    private fun toJsonElement(value: Any?): JsonElement {
        if (value == null) return JsonNull
        return when (value) {
            is JsonElement -> value
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Short -> JsonPrimitive(value.toInt())
            is Byte -> JsonPrimitive(value.toInt())
            is Float -> if (value.isFinite()) JsonPrimitive(value.toDouble()) else JsonNull
            is Double -> if (value.isFinite()) JsonPrimitive(value) else JsonNull
            is Number -> JsonPrimitive(value.toDouble())
            is String -> JsonPrimitive(value)
            is Array<*> -> JsonArray(value.map { toJsonElement(it) })
            is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
            is Map<*, *> -> {
                val entries = LinkedHashMap<String, JsonElement>()
                for ((k, v) in value) {
                    if (k is String) entries[k] = toJsonElement(v)
                }
                JsonObject(entries)
            }
            else -> JsonPrimitive(value.toString())
        }
    }

    private fun fromJsonElement(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> primitiveToAny(element)
        is JsonArray -> element.map { fromJsonElement(it) }
        is JsonObject -> {
            val result = LinkedHashMap<String, Any?>()
            for ((k, v) in element) {
                result[k] = fromJsonElement(v)
            }
            result
        }
    }

    private fun primitiveToAny(p: JsonPrimitive): Any? {
        if (p.isString) return p.content
        p.booleanOrNull?.let { return it }
        p.longOrNull?.let { return it }
        p.doubleOrNull?.let { return it }
        return p.content
    }
}
