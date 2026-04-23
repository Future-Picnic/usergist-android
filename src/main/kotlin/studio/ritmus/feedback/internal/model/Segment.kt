package studio.ritmus.feedback.internal.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Lightweight, serialized segment representation evaluated on device.
 *
 * Mirrors `SerializedSegmentRules` from `@ritmus/sdk-core`. The server
 * is authoritative; this exists so the SDK can fire prompts instantly
 * without a network round-trip.
 */
@Serializable
internal data class SerializedSegmentRules(
    @SerialName("userProperties")
    val userProperties: List<UserPropertyRule>? = null,
    @SerialName("eventCounts")
    val eventCounts: List<EventCountRule>? = null,
)

/** A single user-property comparison in [SerializedSegmentRules]. */
@Serializable
internal data class UserPropertyRule(
    val key: String,
    val op: String,
    val value: JsonElement,
)

/** A single event-count comparison in [SerializedSegmentRules]. */
@Serializable
internal data class EventCountRule(
    @SerialName("eventName") val eventName: String,
    val op: String,
    val count: Int,
    @SerialName("windowDays") val windowDays: Int,
)
