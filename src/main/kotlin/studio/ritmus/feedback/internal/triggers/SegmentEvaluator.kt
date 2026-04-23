package studio.ritmus.feedback.internal.triggers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import studio.ritmus.feedback.internal.model.EventCountRule
import studio.ritmus.feedback.internal.model.SerializedSegmentRules
import studio.ritmus.feedback.internal.model.UserPropertyRule

/**
 * Snapshot of the user state used to evaluate [SerializedSegmentRules].
 *
 * Mirrors `UserState` in `@ritmus/sdk-core`. The SDK tracks local event
 * counts by `(eventName, windowDays)` so sliding windows are stable
 * without a server round-trip.
 */
internal data class UserState(
    val properties: Map<String, Any?> = emptyMap(),
    /** `eventName -> (windowDays -> count)`. */
    val eventCounts: Map<String, Map<Int, Int>> = emptyMap(),
    /** `eventName -> last occurrence (epoch millis)`. */
    val lastEventAt: Map<String, Long> = emptyMap(),
)

/**
 * Client-side evaluator for the `SerializedSegmentRules` subset sent in
 * armed triggers. Server remains authoritative; this is used for
 * instant-fire decisions.
 */
internal object SegmentEvaluator {

    fun matches(rules: SerializedSegmentRules?, user: UserState): Boolean {
        if (rules == null) return true
        val userProps = rules.userProperties
        if (!userProps.isNullOrEmpty()) {
            for (rule in userProps) {
                if (!evaluateUserProperty(rule, user)) return false
            }
        }
        val eventCounts = rules.eventCounts
        if (!eventCounts.isNullOrEmpty()) {
            for (rule in eventCounts) {
                if (!evaluateEventCount(rule, user)) return false
            }
        }
        return true
    }

    // ---------------- User-property evaluation ----------------

    private fun evaluateUserProperty(rule: UserPropertyRule, user: UserState): Boolean {
        val actual = user.properties[rule.key]
        return when (rule.op) {
            "eq" -> compareEq(actual, rule.value)
            "neq" -> !compareEq(actual, rule.value)
            "in" -> compareIn(actual, rule.value)
            "gt" -> compareNumeric(actual, rule.value) { a, b -> a > b }
            "gte" -> compareNumeric(actual, rule.value) { a, b -> a >= b }
            "lt" -> compareNumeric(actual, rule.value) { a, b -> a < b }
            "lte" -> compareNumeric(actual, rule.value) { a, b -> a <= b }
            else -> false
        }
    }

    private fun evaluateEventCount(rule: EventCountRule, user: UserState): Boolean {
        val bucket = user.eventCounts[rule.eventName]
        val count = bucket?.get(rule.windowDays) ?: 0
        return when (rule.op) {
            "eq" -> count == rule.count
            "gte" -> count >= rule.count
            "lte" -> count <= rule.count
            "gt" -> count > rule.count
            "lt" -> count < rule.count
            else -> false
        }
    }

    // ---------------- Value coercion ----------------

    private fun compareEq(actual: Any?, expected: JsonElement): Boolean {
        if (expected is JsonNull) return actual == null
        val expectedAny = jsonToAny(expected) ?: return false
        if (actual == null) return false
        return normaliseNumberOrSelf(actual) == normaliseNumberOrSelf(expectedAny)
    }

    private fun compareIn(actual: Any?, expected: JsonElement): Boolean {
        val list = (expected as? JsonArray) ?: return false
        if (actual == null) return false
        val actualNorm = normaliseNumberOrSelf(actual)
        return list.any { element ->
            val e = jsonToAny(element) ?: return@any false
            normaliseNumberOrSelf(e) == actualNorm
        }
    }

    private fun compareNumeric(
        actual: Any?,
        expected: JsonElement,
        op: (Double, Double) -> Boolean,
    ): Boolean {
        val a = toDoubleOrNull(actual) ?: return false
        val b = toDoubleOrNull(jsonToAny(expected)) ?: return false
        return op(a, b)
    }

    private fun jsonToAny(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            element.doubleOrNull != null -> element.doubleOrNull
            else -> element.content
        }
        is JsonArray -> element.map { jsonToAny(it) }
        is JsonObject -> element.mapValues { jsonToAny(it.value) }
    }

    private fun normaliseNumberOrSelf(value: Any): Any {
        return when (value) {
            is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            is Float, is Double -> (value as Number).toDouble()
            else -> value
        }
    }

    private fun toDoubleOrNull(value: Any?): Double? = when (value) {
        null -> null
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        is Boolean -> if (value) 1.0 else 0.0
        else -> null
    }
}
