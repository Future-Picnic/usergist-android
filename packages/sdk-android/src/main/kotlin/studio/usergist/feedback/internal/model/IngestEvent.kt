package studio.usergist.feedback.internal.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * A single event enqueued for ingest.
 *
 * Mirrors the `IngestEvent` shape from `@usergist/sdk-core`.
 */
@Serializable
internal data class IngestEvent(
    val name: String,
    /** ISO-8601 string with millisecond precision (UTC). */
    val timestamp: String,
    @SerialName("anonymousId")
    val anonymousId: String,
    @SerialName("externalId")
    val externalId: String? = null,
    val properties: JsonObject? = null,
    @SerialName("sessionId")
    val sessionId: String? = null,
    @SerialName("sdkVersion")
    val sdkVersion: String,
    @SerialName("appVersion")
    val appVersion: String? = null,
    val platform: String,
    @SerialName("eventId")
    val eventId: String = UUID.randomUUID().toString(),
    val purpose: EventPurpose = EventPurpose.ANALYTICS,
)

@Serializable
internal enum class EventPurpose {
    @SerialName("analytics") ANALYTICS,
    @SerialName("feedback") FEEDBACK,
}

/** API event shape. [EventPurpose] is local consent metadata, not wire data. */
@Serializable
internal data class WireIngestEvent(
    val eventId: String,
    val name: String,
    val timestamp: String,
    val anonymousId: String,
    val externalId: String? = null,
    val properties: JsonObject? = null,
    val sessionId: String? = null,
    val sdkVersion: String,
    val appVersion: String? = null,
    val platform: String,
)

internal fun IngestEvent.toWire(): WireIngestEvent = WireIngestEvent(
    eventId = eventId,
    name = name,
    timestamp = timestamp,
    anonymousId = anonymousId,
    externalId = externalId,
    properties = properties,
    sessionId = sessionId,
    sdkVersion = sdkVersion,
    appVersion = appVersion,
    platform = platform,
)
